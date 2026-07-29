package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningSampleQualityTests {

    @Test
    void officialBinaryOutcomeWithLockedIdentitiesIsEligible() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("OFFICIAL_RESULT");

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertEquals(1.0, assessment.confidence());
        assertTrue(assessment.calibrationEligible());
        assertNull(assessment.exclusionReason());
    }

    @Test
    void databaseOutcomeNeedsResultIdentityForHighConfidence() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("DATABASE_MATCH");

        LearningSampleQuality.Assessment withoutResultId = LearningSampleQuality.assess(bet);
        assertEquals(0.82, withoutResultId.confidence());
        assertFalse(withoutResultId.calibrationEligible());
        assertEquals("LOW_CONFIDENCE_SETTLEMENT", withoutResultId.exclusionReason());

        bet.setResultMatchId(99L);
        LearningSampleQuality.Assessment withResultId = LearningSampleQuality.assess(bet);
        assertEquals(0.96, withResultId.confidence());
        assertTrue(withResultId.calibrationEligible());
    }

    @Test
    void heuristicScoreGuessRemainsTelemetryOnly() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("HEURISTIC_SCORE");
        bet.setSettlementReason("LAST_SCORE_INFERENCE");
        bet.setLastScoreConfidence(0.99);

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertEquals(0.70, assessment.confidence());
        assertFalse(assessment.calibrationEligible());
        assertEquals("LOW_CONFIDENCE_SETTLEMENT", assessment.exclusionReason());
    }

    @Test
    void outcomeIdentityMustMatchTheTrackedPlayers() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("OFFICIAL_RESULT");
        bet.setWinnerPlayerId(999L);

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertFalse(assessment.calibrationEligible());
        assertEquals("INVALID_WINNER_IDENTITY", assessment.exclusionReason());
    }

    @Test
    void priceRegimesUseConservativeDeadband() {
        assertEquals("UNDERDOG", LearningSampleQuality.priceRegime(0.45));
        assertEquals("BALANCED", LearningSampleQuality.priceRegime(0.50));
        assertEquals("FAVORITE", LearningSampleQuality.priceRegime(0.55));
    }

    private PaperTradeBet resolvedBet() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setStatus(PaperTradeBet.STATUS_WON);
        bet.setPlayer1Id(1L);
        bet.setPlayer2Id(2L);
        bet.setSidePlayerId(1L);
        bet.setWinnerPlayerId(1L);
        return bet;
    }
}
