package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.service.PaperTradingShadowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.locks.ReentrantLock;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;

/**
 * Session lifecycle primitives — find/create the active session, persist
 * one or many sessions, and mirror writes through {@link PaperTradingShadowService}.
 *
 * <p>Eleventh §4 slice — first foundation piece of the SessionService row.
 * Picked the lifecycle cluster (vs. the bigger {@code resetSession} /
 * {@code buildSessionDto} flows) because it's the smallest cohesive
 * grouping with a single shared dependency surface
 * ({@code PaperTradeSessionRepository} + {@code PaperTradingShadowService}
 * + the {@code defaultStartingBankroll @Value}) and the most external
 * consumers — every placement loop and integrity method calls
 * {@link #getOrCreateActiveSession()}. Extracting it now means
 * {@code PaperTradingService} (and any future {@code SessionService}) both
 * delegate here rather than duplicating the find-or-create logic.
 *
 * <p>Behaviour preservation: the find-or-create chain, the
 * {@code clamp(startingBankroll, 100, 1_000_000)} guard, the default-label
 * ({@code "Paper Session " + LocalDate.now()}), and the shadow-service
 * mirror are all verbatim from the original {@code PaperTradingService}
 * helpers.
 */
@Service
public class SessionLifecycleService {

    /**
     * Serializes the empty-ledger find/create window inside this JVM. The lock
     * is deliberately retained until the surrounding transaction commits so
     * another scheduler cannot observe the still-uncommitted insert.
     */
    private static final ReentrantLock ACTIVE_SESSION_CREATION_LOCK = new ReentrantLock(true);

    /** Lower / upper clamp on starting bankroll — matches the original {@code clamp(..., 100.0, 1_000_000.0)} guard. */
    private static final double STARTING_BANKROLL_MIN = 100.0;
    private static final double STARTING_BANKROLL_MAX = 1_000_000.0;

    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradingShadowService paperTradingShadowService;
    private SessionLedgerReconciler sessionLedgerReconciler;

    @Value("${ttl.paper.startingBankroll:1000.0}")
    private double defaultStartingBankroll;

    @Value("${ttl.odds.defaultModelFamily:ENSEMBLE}")
    private String requestedModelVersion;

    @Value("${ttl.paper.policyVersion:accuracy-guardrails-r1}")
    private String policyVersion;

    @Value("${app.build.revision:${GIT_COMMIT:workspace}}")
    private String codeRevision;

    public SessionLifecycleService(PaperTradeSessionRepository sessionRepository,
                                   PaperTradingShadowService paperTradingShadowService) {
        this.sessionRepository = sessionRepository;
        this.paperTradingShadowService = paperTradingShadowService;
    }

    @Autowired(required = false)
    void setSessionLedgerReconciler(SessionLedgerReconciler sessionLedgerReconciler) {
        this.sessionLedgerReconciler = sessionLedgerReconciler;
    }

    /** Find the newest ACTIVE session or lazily create one with default settings. */
    @Transactional
    public PaperTradeSession getOrCreateActiveSession() {
        try (CreationLock ignored = lockCreationUntilTransactionCompletes()) {
            return sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE)
                    .orElseGet(() -> createSession(null, null));
        }
    }

    /**
     * Placement-only variant that obtains a database write lock on the active
     * ledger row. Call only from an existing transaction.
     */
    @Transactional
    public PaperTradeSession getOrCreateActiveSessionForUpdate() {
        try (CreationLock ignored = lockCreationUntilTransactionCompletes()) {
            return sessionRepository.findLatestByStatusForUpdate(PaperTradeSession.STATUS_ACTIVE)
                    .orElseGet(() -> createSession(null, null));
        }
    }

    /**
     * Create a fresh ACTIVE session with the supplied starting bankroll and
     * label (both nullable — null bankroll falls back to the {@code @Value}
     * default; null label uses {@code "Paper Session <today>"}). Persists +
     * mirrors through the shadow service.
     */
    @Transactional
    public PaperTradeSession createSession(Double startingBankroll, String label) {
        try (CreationLock ignored = lockCreationUntilTransactionCompletes()) {
            double start = startingBankroll == null
                    ? clamp(defaultStartingBankroll, STARTING_BANKROLL_MIN, STARTING_BANKROLL_MAX)
                    : clamp(startingBankroll, STARTING_BANKROLL_MIN, STARTING_BANKROLL_MAX);

            PaperTradeSession session = new PaperTradeSession();
            session.setStatus(PaperTradeSession.STATUS_ACTIVE);
            session.setLabel(StringUtils.hasText(label) ? label.trim() : "Paper Session " + LocalDate.now());
            session.setRequestedModelVersion(StringUtils.hasText(requestedModelVersion)
                    ? requestedModelVersion.trim() : "ENSEMBLE");
            session.setPolicyVersion(StringUtils.hasText(policyVersion) ? policyVersion.trim() : "accuracy-guardrails-r1");
            session.setCodeRevision(StringUtils.hasText(codeRevision) ? codeRevision.trim() : "workspace");
            session.setStartingBankroll(round2(start));
            session.setCurrentBankroll(round2(start));
            session.setPeakBankroll(round2(start));
            session.setRealizedPnl(0.0);
            session.setTotalStaked(0.0);
            session.setTotalReturned(0.0);
            session.setTotalBets(0);
            session.setWins(0);
            session.setLosses(0);
            session.setPushes(0);
            session.setSimulationRowsScanned(0);
            session.setSimulationBetsPlaced(0);
            session.setSimulationBetsSettled(0);
            session.setSimulationBetsVoided(0);
            session.setAdaptiveSampleSize(0);
            session.setAdaptiveEdgeShift(0.0);
            session.setAdaptiveSelectionScoreShift(0.0);
            session.setAdaptiveStakeMultiplier(1.0);
            session.setAdaptiveCalibrationError(0.0);
            session.setAdaptiveRoiSignal(0.0);
            session.setAdaptiveUpdatedAt(null);
            session.setLastSyncAt(null);
            session.setClosedAt(null);
            return saveSession(session);
        }
    }

    private static CreationLock lockCreationUntilTransactionCompletes() {
        ACTIVE_SESSION_CREATION_LOCK.lock();
        boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
        if (deferred) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    ACTIVE_SESSION_CREATION_LOCK.unlock();
                }
            });
        }
        return new CreationLock(deferred);
    }

    private record CreationLock(boolean deferred) implements AutoCloseable {
        @Override
        public void close() {
            if (!deferred) ACTIVE_SESSION_CREATION_LOCK.unlock();
        }
    }

    /** Persist a session and mirror the write through the shadow service. */
    @Transactional
    public PaperTradeSession saveSession(PaperTradeSession session) {
        PaperTradeSession saved = sessionRepository.save(session);
        paperTradingShadowService.mirrorSession(saved);
        return saved;
    }

    /** Bulk save with mirrored writes — used during session closeout / reset flows. */
    @Transactional
    public List<PaperTradeSession> saveSessions(List<PaperTradeSession> sessions) {
        List<PaperTradeSession> saved = sessionRepository.saveAll(sessions);
        paperTradingShadowService.mirrorSessions(saved);
        return saved;
    }

    /** Close and checksum the final run counters exactly once. */
    @Transactional
    public List<PaperTradeSession> closeSessions(List<PaperTradeSession> sessions, LocalDateTime requestedClosedAt) {
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        LocalDateTime closedAt = requestedClosedAt == null ? LocalDateTime.now() : requestedClosedAt;
        for (PaperTradeSession session : sessions) {
            if (session == null || !PaperTradeSession.STATUS_ACTIVE.equals(session.getStatus())) continue;
            if (sessionLedgerReconciler != null) {
                sessionLedgerReconciler.reconcile(session);
            }
            session.setStatus(PaperTradeSession.STATUS_CLOSED);
            session.setClosedAt(closedAt);
            freezeRunSummary(session);
        }
        return saveSessions(sessions);
    }

    /**
     * A stopped application is the end of the observation window. Close the
     * active run explicitly so a later restart cannot silently append calls
     * to an old artifact/policy version.
     */
    @EventListener(ContextClosedEvent.class)
    @Transactional
    public void closeActiveSessionOnShutdown() {
        sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE)
                .ifPresent(session -> {
                    if (sessionLedgerReconciler != null) {
                        sessionLedgerReconciler.reconcile(session);
                    }
                    session.setStatus(PaperTradeSession.STATUS_CLOSED);
                    session.setClosedAt(LocalDateTime.now());
                    freezeRunSummary(session);
                    saveSession(session);
                });
    }

    private static void freezeRunSummary(PaperTradeSession session) {
        if (session == null || StringUtils.hasText(session.getFrozenRunSummary())) {
            return;
        }
        StringJoiner summary = new StringJoiner("|");
        summary.add("summaryVersion=1")
                .add("sessionId=" + value(session.getId()))
                .add("label=" + value(session.getLabel()))
                .add("status=" + value(session.getStatus()))
                .add("createdAt=" + value(session.getCreatedAt()))
                .add("closedAt=" + value(session.getClosedAt()))
                .add("requestedModel=" + value(session.getRequestedModelVersion()))
                .add("effectiveModel=" + value(session.getEffectiveModelVersion()))
                .add("artifactChecksum=" + value(session.getEffectiveArtifactChecksum()))
                .add("featureSchemaChecksum=" + value(session.getFeatureSchemaChecksum()))
                .add("calibrationId=" + value(session.getCalibrationId()))
                .add("policyVersion=" + value(session.getPolicyVersion()))
                .add("codeRevision=" + value(session.getCodeRevision()))
                .add("startingBankroll=" + session.getStartingBankroll())
                .add("currentBankroll=" + session.getCurrentBankroll())
                .add("realizedPnl=" + session.getRealizedPnl())
                .add("totalStaked=" + session.getTotalStaked())
                .add("totalReturned=" + session.getTotalReturned())
                .add("totalBets=" + session.getTotalBets())
                .add("wins=" + session.getWins())
                .add("losses=" + session.getLosses())
                .add("pushes=" + session.getPushes())
                .add("rowsScanned=" + session.getSimulationRowsScanned())
                .add("betsPlaced=" + session.getSimulationBetsPlaced())
                .add("betsSettled=" + session.getSimulationBetsSettled())
                .add("betsVoided=" + session.getSimulationBetsVoided());
        String frozen = summary.toString();
        session.setFrozenRunSummary(frozen);
        session.setFrozenRunSummaryChecksum(sha256(frozen));
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString()
                .replace("%", "%25")
                .replace("|", "%7C")
                .replace("\n", "%0A")
                .replace("\r", "%0D");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Visible for testing: lets unit tests stub the {@code @Value}-injected default. */
    void overrideDefaultStartingBankrollForTest(double bankroll) {
        this.defaultStartingBankroll = bankroll;
    }
}
