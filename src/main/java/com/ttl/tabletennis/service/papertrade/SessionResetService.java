package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.util.CorrelationContext;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.scrape.HardRockScoreStreamClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * Closes the active run and starts a fresh one without deleting historical
 * evidence. The legacy {@code clearHistory} request parameter is accepted for
 * API compatibility but intentionally ignored: a normal reset is archival,
 * never destructive.
 * The new session boots with a calibrated adaptive snapshot so
 * the placement loop has a profile to consume on the first sync.
 *
 * <p>Thirteenth §4 slice — finishes the SessionService row. Composes the
 * already-extracted lifecycle ({@link SessionLifecycleService}) and
 * snapshot ({@link SessionSnapshotService}) services. Live stream tracking is
 * cleared, but settled evidence and prior run ledgers remain immutable.
 *
 * <p>Adaptive profile resolution is passed in via a
 * {@code Function<PaperTradeSession, AdaptiveProfile>} so the caller
 * ({@code PaperTradingService} today) keeps owning the sample-load chain
 * ({@code loadAdaptiveDecisionSamples} → back-fill → {@code AdaptiveProfileBuilder})
 * which has consumers outside the reset path.
 */
@Service
public class SessionResetService {

    private final PaperTradeSessionRepository sessionRepository;
    private final SessionLifecycleService sessionLifecycleService;
    private final SessionSnapshotService sessionSnapshotService;
    private final HardRockScoreStreamClient hardRockScoreStreamClient;

    public SessionResetService(PaperTradeSessionRepository sessionRepository,
                               SessionLifecycleService sessionLifecycleService,
                               SessionSnapshotService sessionSnapshotService,
                               HardRockScoreStreamClient hardRockScoreStreamClient) {
        this.sessionRepository = sessionRepository;
        this.sessionLifecycleService = sessionLifecycleService;
        this.sessionSnapshotService = sessionSnapshotService;
        this.hardRockScoreStreamClient = hardRockScoreStreamClient;
    }

    @Transactional
    public PaperTradingSessionDto resetSession(Double startingBankroll,
                                                String label,
                                                boolean clearHistory,
                                                int openLimit,
                                                int recentLimit,
                                                ExposureMetricsBuilder.ExposureCaps exposureCaps,
                                                Function<PaperTradeSession, AdaptiveProfile> adaptiveProfileResolver,
                                                Function<com.ttl.tabletennis.domain.PaperTradeBet, String> trackingStateResolver) {
        try (CorrelationContext.Scope ignored = CorrelationContext.openIfAbsent(null)) {
            hardRockScoreStreamClient.clearTracking();
            List<PaperTradeSession> activeSessions =
                    sessionRepository.findByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
            if (!activeSessions.isEmpty()) {
                sessionLifecycleService.closeSessions(activeSessions, LocalDateTime.now());
                // The database enforces a single ACTIVE session. Force the
                // CLOSED updates out before inserting the successor so the
                // reset is deterministic even when Hibernate would otherwise
                // reorder the update and insert within this transaction.
                sessionRepository.flush();
            }

            PaperTradeSession created = sessionLifecycleService.createSession(startingBankroll, label);
            AdaptiveProfile profile = adaptiveProfileResolver.apply(created);
            profile.applyTo(created, LocalDateTime.now());
            sessionLifecycleService.saveSession(created);
            return sessionSnapshotService.buildSessionDto(
                    created, openLimit, recentLimit, exposureCaps, trackingStateResolver);
        }
    }
}
