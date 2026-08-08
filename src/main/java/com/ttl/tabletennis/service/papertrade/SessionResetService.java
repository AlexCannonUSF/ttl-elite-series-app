package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.util.CorrelationContext;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.ModelCallViewerReviewRepository;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.service.PaperTradingShadowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * Orchestrates the two reset flows: "close active + start fresh" (default)
 * and "wipe history + start fresh" (when {@code clearHistory} is true).
 * Either way the new session boots with a calibrated adaptive snapshot so
 * the placement loop has a profile to consume on the first sync.
 *
 * <p>Thirteenth §4 slice — finishes the SessionService row. Composes the
 * already-extracted lifecycle ({@link SessionLifecycleService}) and
 * snapshot ({@link SessionSnapshotService}) services with the four
 * repositories whose bulk-delete operations the legacy
 * {@code clearHistory=true} branch wipes.
 *
 * <p>Adaptive profile resolution is passed in via a
 * {@code Function<PaperTradeSession, AdaptiveProfile>} so the caller
 * ({@code PaperTradingService} today) keeps owning the sample-load chain
 * ({@code loadAdaptiveDecisionSamples} → back-fill → {@code AdaptiveProfileBuilder})
 * which has consumers outside the reset path.
 */
@Service
public class SessionResetService {

    private final PaperTradingShadowService paperTradingShadowService;
    private final TrackedMatchObservationRepository trackedMatchObservationRepository;
    private final PaperTradeDecisionSampleRepository decisionSampleRepository;
    private final PaperTradeModelCallRepository modelCallRepository;
    private final ModelCallViewerReviewRepository modelCallViewerReviewRepository;
    private final PaperTradeBetRepository betRepository;
    private final PaperTradeSessionRepository sessionRepository;
    private final SessionLifecycleService sessionLifecycleService;
    private final SessionSnapshotService sessionSnapshotService;

    public SessionResetService(PaperTradingShadowService paperTradingShadowService,
                               TrackedMatchObservationRepository trackedMatchObservationRepository,
                               PaperTradeDecisionSampleRepository decisionSampleRepository,
                               PaperTradeModelCallRepository modelCallRepository,
                               ModelCallViewerReviewRepository modelCallViewerReviewRepository,
                               PaperTradeBetRepository betRepository,
                               PaperTradeSessionRepository sessionRepository,
                               SessionLifecycleService sessionLifecycleService,
                               SessionSnapshotService sessionSnapshotService) {
        this.paperTradingShadowService = paperTradingShadowService;
        this.trackedMatchObservationRepository = trackedMatchObservationRepository;
        this.decisionSampleRepository = decisionSampleRepository;
        this.modelCallRepository = modelCallRepository;
        this.modelCallViewerReviewRepository = modelCallViewerReviewRepository;
        this.betRepository = betRepository;
        this.sessionRepository = sessionRepository;
        this.sessionLifecycleService = sessionLifecycleService;
        this.sessionSnapshotService = sessionSnapshotService;
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
            if (clearHistory) {
                paperTradingShadowService.clearAll();
                trackedMatchObservationRepository.deleteAllInBatch();
                decisionSampleRepository.deleteAllInBatch();
                modelCallViewerReviewRepository.deleteAllInBatch();
                modelCallRepository.deleteAllInBatch();
                betRepository.deleteAllInBatch();
                sessionRepository.deleteAllInBatch();
                PaperTradeSession created = sessionLifecycleService.createSession(startingBankroll, label);
                AdaptiveProfile profile = adaptiveProfileResolver.apply(created);
                profile.applyTo(created, LocalDateTime.now());
                sessionLifecycleService.saveSession(created);
                return sessionSnapshotService.buildSessionDto(
                        created, openLimit, recentLimit, exposureCaps, trackingStateResolver);
            }

            List<PaperTradeSession> activeSessions =
                    sessionRepository.findByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
            if (!activeSessions.isEmpty()) {
                activeSessions.forEach(active -> active.setStatus(PaperTradeSession.STATUS_CLOSED));
                sessionLifecycleService.saveSessions(activeSessions);
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
