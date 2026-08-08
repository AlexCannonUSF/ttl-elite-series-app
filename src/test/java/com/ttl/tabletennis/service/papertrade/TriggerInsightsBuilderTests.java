package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TriggerInsightsBuilderTests {

    @Test
    void triggerRoiUsesOnlyLearningEligibleSettlements() {
        PaperTradeBet trusted = settled("Recent Form", "OFFICIAL_RESULT", "", 8.0);
        PaperTradeBet heuristic = settled("Recent Form", "HEURISTIC_SCORE", "LAST_SCORE_INFERENCE", 25.0);

        List<PaperTradingSessionDto.TriggerInsightDto> insights =
                TriggerInsightsBuilder.buildTopTriggers(List.of(trusted, heuristic));

        assertEquals(1, insights.size());
        assertEquals(1, insights.get(0).count());
        assertEquals(8.0, insights.get(0).pnl());
    }

    @Test
    void ambiguousOfficialArchiveDoesNotEnterTriggerLedger() {
        PaperTradeBet ambiguous = settled("Elo", "OFFICIAL_RESULT", "", 10.0);
        ambiguous.setSettlementAmbiguityScore(0.45);

        assertEquals(List.of(), TriggerInsightsBuilder.buildTopTriggers(List.of(ambiguous)));
    }

    private PaperTradeBet settled(String trigger, String source, String reason, double pnl) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setStatus(PaperTradeBet.STATUS_WON);
        bet.setPlayer1Id(1L);
        bet.setPlayer2Id(2L);
        bet.setSidePlayerId(1L);
        bet.setWinnerPlayerId(1L);
        bet.setSettlementSource(source);
        bet.setSettlementReason(reason);
        if (source.contains("OFFICIAL") || source.contains("DATABASE")) {
            bet.setSettlementAmbiguityScore(0.0);
        }
        bet.setTopTrigger(trigger);
        bet.setProfitLoss(pnl);
        bet.setStake(10.0);
        bet.setEdge(0.08);
        bet.setModelProbability(0.58);
        bet.setImpliedProbability(0.50);
        return bet;
    }
}
