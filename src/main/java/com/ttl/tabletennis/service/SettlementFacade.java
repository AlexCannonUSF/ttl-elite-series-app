package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SettlementFacade {

    private static final Logger log = LoggerFactory.getLogger(SettlementFacade.class);

    private final PaperTradingService paperTradingService;
    private final MeterRegistry meterRegistry;
    private final PaperTradeBetRepository betRepository;
    private final SettlementDiffLogService settlementDiffLogService;
    private final ScoreTruthAdvisoryService scoreTruthAdvisoryService;
    private final Optional<ScoreTruthPrimaryService> scoreTruthPrimaryService;
    private final boolean shadowDiffEnabled;

    @Autowired
    public SettlementFacade(@Lazy PaperTradingService paperTradingService,
                            MeterRegistry meterRegistry,
                            PaperTradeBetRepository betRepository,
                            SettlementDiffLogService settlementDiffLogService,
                            ScoreTruthAdvisoryService scoreTruthAdvisoryService,
                            Optional<ScoreTruthPrimaryService> scoreTruthPrimaryService,
                            @Value("${ttl.shadowDiff.enabled:true}") boolean shadowDiffEnabled) {
        this.paperTradingService = paperTradingService;
        this.meterRegistry = meterRegistry;
        this.betRepository = betRepository;
        this.settlementDiffLogService = settlementDiffLogService;
        this.scoreTruthAdvisoryService = scoreTruthAdvisoryService;
        this.scoreTruthPrimaryService = scoreTruthPrimaryService == null ? Optional.empty() : scoreTruthPrimaryService;
        this.shadowDiffEnabled = shadowDiffEnabled;
    }

    public SettlementFacade(@Lazy PaperTradingService paperTradingService,
                            MeterRegistry meterRegistry,
                            PaperTradeBetRepository betRepository,
                            SettlementDiffLogService settlementDiffLogService,
                            ScoreTruthAdvisoryService scoreTruthAdvisoryService,
                            boolean shadowDiffEnabled) {
        this(paperTradingService, meterRegistry, betRepository, settlementDiffLogService,
                scoreTruthAdvisoryService, Optional.empty(), shadowDiffEnabled);
    }

    public PaperTradingService.SettlementStats settleOpenBets(PaperTradeSession session,
                                                              List<LiveOddsRecommendationDto> rows) {
        List<PaperTradeBet> trackedOpenBets = loadTrackedOpenBets(session);
        boolean primary = scoreTruthPrimaryService.isPresent() && scoreTruthPrimaryService.get().active();

        if (meterRegistry == null) {
            PaperTradingService.SettlementStats result = invokeSettlement(session, rows, trackedOpenBets, primary);
            recordIdentityDiffs(trackedOpenBets);
            if (!primary) {
                recordScoreTruthAdvisories(trackedOpenBets);
            }
            recordScoreTruthDiffs(trackedOpenBets);
            return result;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PaperTradingService.SettlementStats result = invokeSettlement(session, rows, trackedOpenBets, primary);
            recordIdentityDiffs(trackedOpenBets);
            if (!primary) {
                recordScoreTruthAdvisories(trackedOpenBets);
            }
            recordScoreTruthDiffs(trackedOpenBets);
            return result;
        } finally {
            String mode = primary ? "primary" : "legacy";
            meterRegistry.counter("ttl.facade.calls", "facade", "settlement", "operation", "settleOpenBets", "mode", mode).increment();
            sample.stop(meterRegistry.timer("ttl.facade.duration", "facade", "settlement", "operation", "settleOpenBets"));
        }
    }

    private PaperTradingService.SettlementStats invokeSettlement(PaperTradeSession session,
                                                                  List<LiveOddsRecommendationDto> rows,
                                                                  List<PaperTradeBet> trackedOpenBets,
                                                                  boolean primary) {
        if (primary) {
            ScoreTruthPrimaryService.ClosureStats closure = scoreTruthPrimaryService.get().closeOpenBets(trackedOpenBets);
            log.info("[score-truth-primary] closed session={} settled={} voided={} held={} reviewed={} skipped={}",
                    session == null ? null : session.getId(),
                    closure.settled(), closure.voided(), closure.held(), closure.reviewed(), closure.skipped());
            // Legacy fallthrough: bets that v3 couldn't close (held/reviewed/skipped)
            // get a second pass through the heuristic-based legacy closer, which
            // reads bet.lastObservedScore + the current odds row directly via
            // ScoreWinnerResolver. Without this, a bet whose match completed
            // off-feed (no completion signal in observations, no targeted
            // confirmation source) sits OPEN indefinitely — exactly the "all
            // night with no settlements" failure mode we hit in production.
            //
            // Safe to compose: settleOpenBetsLegacy re-queries STATUS_OPEN bets
            // from the database, so bets v3 already settled/voided are
            // automatically excluded.
            PaperTradingService.SettlementStats legacyExtra = PaperTradingService.SettlementStats.empty();
            int unclosed = closure.held() + closure.reviewed() + closure.skipped();
            if (unclosed > 0) {
                try {
                    legacyExtra = paperTradingService.settleOpenBetsLegacy(session, rows);
                    log.info("[settlement-fallthrough] legacy fallthrough ran on {} unclosed v3 bets: settled={} voided={}",
                            unclosed, legacyExtra.settled(), legacyExtra.voided());
                } catch (RuntimeException ex) {
                    log.warn("[settlement-fallthrough] legacy fallthrough failed; keeping bets open", ex);
                }
                if (meterRegistry != null) {
                    meterRegistry.counter("ttl.settlement.fallthrough.settled").increment(legacyExtra.settled());
                    meterRegistry.counter("ttl.settlement.fallthrough.voided").increment(legacyExtra.voided());
                }
            }
            return new PaperTradingService.SettlementStats(
                    closure.settled() + legacyExtra.settled(),
                    closure.voided() + legacyExtra.voided());
        }
        return paperTradingService.settleOpenBetsLegacy(session, rows);
    }

    private List<PaperTradeBet> loadTrackedOpenBets(PaperTradeSession session) {
        if (!shadowDiffEnabled || betRepository == null || session == null || session.getId() == null) {
            return List.of();
        }
        try {
            return betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(session.getId(), PaperTradeBet.STATUS_OPEN);
        } catch (RuntimeException ex) {
            log.warn("[shadow-diff] unable to load open bets for session {}", session.getId(), ex);
            return List.of();
        }
    }

    private void recordIdentityDiffs(List<PaperTradeBet> trackedOpenBets) {
        if (!shadowDiffEnabled || settlementDiffLogService == null || trackedOpenBets.isEmpty()) {
            return;
        }
        try {
            int recorded = settlementDiffLogService.recordIdentityReplay(trackedOpenBets);
            if (meterRegistry != null && recorded > 0) {
                meterRegistry.counter("ttl.settlement.diff.rows.logged", "mode", "identity").increment(recorded);
            }
        } catch (RuntimeException ex) {
            log.warn("[shadow-diff] unable to record identity replay rows", ex);
        }
    }

    private void recordScoreTruthDiffs(List<PaperTradeBet> trackedOpenBets) {
        if (!shadowDiffEnabled || settlementDiffLogService == null || trackedOpenBets.isEmpty()) {
            return;
        }
        try {
            boolean advisoryActive = scoreTruthAdvisoryService != null && scoreTruthAdvisoryService.active();
            int recorded = settlementDiffLogService.recordScoreTruthReplay(trackedOpenBets, !advisoryActive);
            if (meterRegistry != null && recorded > 0) {
                meterRegistry.counter("ttl.settlement.diff.rows.logged", "mode", "score-truth").increment(recorded);
            }
        } catch (RuntimeException ex) {
            log.warn("[shadow-diff] unable to record score-truth replay rows", ex);
        }
    }

    private void recordScoreTruthAdvisories(List<PaperTradeBet> trackedOpenBets) {
        if (scoreTruthAdvisoryService == null || trackedOpenBets.isEmpty()) {
            return;
        }
        try {
            int recorded = scoreTruthAdvisoryService.recordAdvisoryDecisions(trackedOpenBets);
            if (meterRegistry != null && recorded > 0) {
                meterRegistry.counter("ttl.score_truth.advisory.rows.logged").increment(recorded);
            }
        } catch (RuntimeException ex) {
            log.warn("[score-truth-advisory] unable to record advisory rows", ex);
        }
    }
}
