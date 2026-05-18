package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SettlementFacade {

    private static final Logger log = LoggerFactory.getLogger(SettlementFacade.class);

    private final PaperTradingService paperTradingService;
    private final MeterRegistry meterRegistry;
    private final PaperTradeBetRepository betRepository;
    private final SettlementDiffLogService settlementDiffLogService;
    private final ScoreTruthAdvisoryService scoreTruthAdvisoryService;
    private final boolean shadowDiffEnabled;

    public SettlementFacade(@Lazy PaperTradingService paperTradingService,
                            MeterRegistry meterRegistry,
                            PaperTradeBetRepository betRepository,
                            SettlementDiffLogService settlementDiffLogService,
                            ScoreTruthAdvisoryService scoreTruthAdvisoryService,
                            @Value("${ttl.shadowDiff.enabled:true}") boolean shadowDiffEnabled) {
        this.paperTradingService = paperTradingService;
        this.meterRegistry = meterRegistry;
        this.betRepository = betRepository;
        this.settlementDiffLogService = settlementDiffLogService;
        this.scoreTruthAdvisoryService = scoreTruthAdvisoryService;
        this.shadowDiffEnabled = shadowDiffEnabled;
    }

    public PaperTradingService.SettlementStats settleOpenBets(PaperTradeSession session,
                                                              List<LiveOddsRecommendationDto> rows) {
        List<PaperTradeBet> trackedOpenBets = loadTrackedOpenBets(session);
        if (meterRegistry == null) {
            PaperTradingService.SettlementStats result = paperTradingService.settleOpenBetsLegacy(session, rows);
            recordIdentityDiffs(trackedOpenBets);
            recordScoreTruthAdvisories(trackedOpenBets);
            recordScoreTruthDiffs(trackedOpenBets);
            return result;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PaperTradingService.SettlementStats result = paperTradingService.settleOpenBetsLegacy(session, rows);
            recordIdentityDiffs(trackedOpenBets);
            recordScoreTruthAdvisories(trackedOpenBets);
            recordScoreTruthDiffs(trackedOpenBets);
            return result;
        } finally {
            meterRegistry.counter("ttl.facade.calls", "facade", "settlement", "operation", "settleOpenBets").increment();
            sample.stop(meterRegistry.timer("ttl.facade.duration", "facade", "settlement", "operation", "settleOpenBets"));
        }
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
