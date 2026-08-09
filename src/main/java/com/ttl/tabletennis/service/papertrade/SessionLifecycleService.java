package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.service.PaperTradingShadowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

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

    /** Lower / upper clamp on starting bankroll — matches the original {@code clamp(..., 100.0, 1_000_000.0)} guard. */
    private static final double STARTING_BANKROLL_MIN = 100.0;
    private static final double STARTING_BANKROLL_MAX = 1_000_000.0;

    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradingShadowService paperTradingShadowService;

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

    /** Find the newest ACTIVE session or lazily create one with default settings. */
    @Transactional
    public PaperTradeSession getOrCreateActiveSession() {
        return sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE)
                .orElseGet(() -> createSession(null, null));
    }

    /**
     * Placement-only variant that obtains a database write lock on the active
     * ledger row. Call only from an existing transaction.
     */
    @Transactional
    public PaperTradeSession getOrCreateActiveSessionForUpdate() {
        return sessionRepository.findLatestByStatusForUpdate(PaperTradeSession.STATUS_ACTIVE)
                .orElseGet(() -> createSession(null, null));
    }

    /**
     * Create a fresh ACTIVE session with the supplied starting bankroll and
     * label (both nullable — null bankroll falls back to the {@code @Value}
     * default; null label uses {@code "Paper Session <today>"}). Persists +
     * mirrors through the shadow service.
     */
    @Transactional
    public PaperTradeSession createSession(Double startingBankroll, String label) {
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

    /** Visible for testing: lets unit tests stub the {@code @Value}-injected default. */
    void overrideDefaultStartingBankrollForTest(double bankroll) {
        this.defaultStartingBankroll = bankroll;
    }
}
