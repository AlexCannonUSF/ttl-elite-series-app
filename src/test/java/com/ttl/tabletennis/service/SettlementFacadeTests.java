package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class SettlementFacadeTests {

    @Test
    void delegatesLegacySettlementPathWithoutChangingResult() {
        PaperTradingService paperTradingService = mock(PaperTradingService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        SettlementDiffLogService settlementDiffLogService = mock(SettlementDiffLogService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SettlementFacade settlementFacade = new SettlementFacade(
                paperTradingService,
                meterRegistry,
                betRepository,
                settlementDiffLogService,
                null,
                true
        );

        PaperTradeSession session = new PaperTradeSession();
        List<LiveOddsRecommendationDto> rows = List.of();
        PaperTradingService.SettlementStats settlementStats = new PaperTradingService.SettlementStats(3, 1);

        when(paperTradingService.settleOpenBetsLegacy(session, rows)).thenReturn(settlementStats);

        assertSame(settlementStats, settlementFacade.settleOpenBets(session, rows));
        verify(paperTradingService).settleOpenBetsLegacy(session, rows);
        verifyNoInteractions(settlementDiffLogService);
        assertEquals(
                1.0,
                meterRegistry.get("ttl.facade.calls").tag("facade", "settlement").tag("operation", "settleOpenBets").counter().count()
        );
    }

    @Test
    void recordsShadowDiffRowsForTrackedOpenBetsWhenSessionIdIsPresent() {
        PaperTradingService paperTradingService = mock(PaperTradingService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        SettlementDiffLogService settlementDiffLogService = mock(SettlementDiffLogService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SettlementFacade settlementFacade = new SettlementFacade(
                paperTradingService,
                meterRegistry,
                betRepository,
                settlementDiffLogService,
                null,
                true
        );

        PaperTradeSession session = mock(PaperTradeSession.class);
        when(session.getId()).thenReturn(55L);
        List<LiveOddsRecommendationDto> rows = List.of();
        List<PaperTradeBet> trackedBets = List.of(mock(PaperTradeBet.class));
        PaperTradingService.SettlementStats settlementStats = new PaperTradingService.SettlementStats(2, 0);

        when(betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(55L, PaperTradeBet.STATUS_OPEN)).thenReturn(trackedBets);
        when(paperTradingService.settleOpenBetsLegacy(session, rows)).thenReturn(settlementStats);
        when(settlementDiffLogService.recordIdentityReplay(trackedBets)).thenReturn(1);
        when(settlementDiffLogService.recordScoreTruthReplay(trackedBets, true)).thenReturn(1);

        assertSame(settlementStats, settlementFacade.settleOpenBets(session, rows));

        verify(betRepository).findBySessionIdAndStatusOrderByPlacedAtAsc(55L, PaperTradeBet.STATUS_OPEN);
        verify(settlementDiffLogService).recordIdentityReplay(trackedBets);
        verify(settlementDiffLogService).recordScoreTruthReplay(trackedBets, true);
        assertEquals(
                1.0,
                meterRegistry.get("ttl.settlement.diff.rows.logged").tag("mode", "identity").counter().count()
        );
        assertEquals(
                1.0,
                meterRegistry.get("ttl.settlement.diff.rows.logged").tag("mode", "score-truth").counter().count()
        );
    }

    @Test
    void advisoryModePersistsAdvisoryRowsAndKeepsShadowDiffAuditSingleSourced() {
        PaperTradingService paperTradingService = mock(PaperTradingService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        SettlementDiffLogService settlementDiffLogService = mock(SettlementDiffLogService.class);
        ScoreTruthAdvisoryService advisoryService = mock(ScoreTruthAdvisoryService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SettlementFacade settlementFacade = new SettlementFacade(
                paperTradingService,
                meterRegistry,
                betRepository,
                settlementDiffLogService,
                advisoryService,
                true
        );

        PaperTradeSession session = mock(PaperTradeSession.class);
        when(session.getId()).thenReturn(56L);
        List<LiveOddsRecommendationDto> rows = List.of();
        List<PaperTradeBet> trackedBets = List.of(mock(PaperTradeBet.class));
        PaperTradingService.SettlementStats settlementStats = new PaperTradingService.SettlementStats(1, 0);

        when(betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(56L, PaperTradeBet.STATUS_OPEN)).thenReturn(trackedBets);
        when(paperTradingService.settleOpenBetsLegacy(session, rows)).thenReturn(settlementStats);
        when(settlementDiffLogService.recordIdentityReplay(trackedBets)).thenReturn(1);
        when(advisoryService.active()).thenReturn(true);
        when(advisoryService.recordAdvisoryDecisions(trackedBets)).thenReturn(1);
        when(settlementDiffLogService.recordScoreTruthReplay(trackedBets, false)).thenReturn(1);

        assertSame(settlementStats, settlementFacade.settleOpenBets(session, rows));

        verify(advisoryService).recordAdvisoryDecisions(trackedBets);
        verify(settlementDiffLogService).recordScoreTruthReplay(trackedBets, false);
        assertEquals(1.0, meterRegistry.get("ttl.score_truth.advisory.rows.logged").counter().count());
    }

    @Test
    void primarySettlementRunsWhenShadowDiffIsDisabledAndNeverCallsLegacy() {
        PaperTradingService paperTradingService = mock(PaperTradingService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        SettlementDiffLogService settlementDiffLogService = mock(SettlementDiffLogService.class);
        ScoreTruthPrimaryService primaryService = mock(ScoreTruthPrimaryService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SettlementFacade settlementFacade = new SettlementFacade(
                paperTradingService,
                meterRegistry,
                betRepository,
                settlementDiffLogService,
                null,
                Optional.of(primaryService),
                false
        );

        PaperTradeSession session = mock(PaperTradeSession.class);
        when(session.getId()).thenReturn(77L);
        List<PaperTradeBet> openBets = List.of(mock(PaperTradeBet.class));
        when(primaryService.active()).thenReturn(true);
        when(betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(77L, PaperTradeBet.STATUS_OPEN))
                .thenReturn(openBets);
        when(primaryService.closeOpenBets(openBets))
                .thenReturn(new ScoreTruthPrimaryService.ClosureStats(2, 1, 0, 0, 0));

        PaperTradingService.SettlementStats stats = settlementFacade.settleOpenBets(session, List.of());

        assertEquals(2, stats.settled());
        assertEquals(1, stats.voided());
        verify(paperTradingService).refreshOpenBetScoreEvidence(session, List.of());
        verify(primaryService).closeOpenBets(openBets);
        verify(paperTradingService, never()).settleOpenBetsLegacy(session, List.of());
        verifyNoInteractions(settlementDiffLogService);
    }
}
