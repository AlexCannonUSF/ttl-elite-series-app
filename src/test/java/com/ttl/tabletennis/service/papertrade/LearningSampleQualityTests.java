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
        bet.setSettlementAmbiguityScore(0.0);

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertEquals(1.0, assessment.confidence());
        assertTrue(assessment.calibrationEligible());
        assertNull(assessment.exclusionReason());
    }

    @Test
    void databaseOutcomeNeedsResultIdentityForHighConfidence() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("DATABASE_MATCH");
        bet.setSettlementAmbiguityScore(0.0);

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
    void explicitSettlementConfidencePreventsWeakEvidenceFromEnteringCalibration() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("SETTLED_FROM_OFFICIAL_RESULT_V3");
        bet.setSettlementAmbiguityScore(0.0);
        bet.setSettlementConfidence(0.84);

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertEquals(0.84, assessment.confidence());
        assertFalse(assessment.calibrationEligible());
        assertEquals("LOW_CONFIDENCE_SETTLEMENT", assessment.exclusionReason());
    }

    @Test
    void targetedCompletionRequiresIndependentSupportForCalibration() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementReason("V3_PRIMARY_TARGETED_COMPLETION_SIGNAL");
        bet.setLastScoreConfidence(0.89);
        bet.setScoreEvidenceQuality("DECISION_GRADE");
        bet.setSettlementEvidenceSourceCount(1);

        assertFalse(LearningSampleQuality.assess(bet).calibrationEligible());

        bet.setSettlementEvidenceSourceCount(2);
        LearningSampleQuality.Assessment supported = LearningSampleQuality.assess(bet);
        assertEquals(0.90, supported.confidence());
        assertTrue(supported.calibrationEligible());
    }

    @Test
    void v3ScoreBackedOutcomeRequiresDecisionGradeAgreementForCalibration() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("SETTLED_FROM_SCORE_BACKED_V3");
        bet.setSettlementReason("V3_PRIMARY_SCORE_BACKED_FINISHED");
        bet.setScoreEvidenceConfidence(0.97);
        bet.setScoreEvidenceQuality("DECISION_GRADE");
        bet.setScoreEvidenceAgreeingSources(1);

        LearningSampleQuality.Assessment singleSource = LearningSampleQuality.assess(bet);
        assertEquals(0.89, singleSource.confidence());
        assertFalse(singleSource.calibrationEligible());

        bet.setScoreEvidenceAgreeingSources(2);
        LearningSampleQuality.Assessment agreed = LearningSampleQuality.assess(bet);
        assertEquals(0.97, agreed.confidence());
        assertTrue(agreed.calibrationEligible());

        bet.setScoreEvidenceContradictory(true);
        LearningSampleQuality.Assessment contradicted = LearningSampleQuality.assess(bet);
        assertEquals(0.89, contradicted.confidence());
        assertFalse(contradicted.calibrationEligible());
    }

    @Test
    void heuristicReasonCannotBecomeTrustedBecauseSourceSaysScoreBacked() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("SETTLED_FROM_SCORE_BACKED_V3");
        bet.setSettlementReason("V3_PRIMARY_LAST_SCORE_HEURISTIC");
        bet.setScoreEvidenceConfidence(0.99);
        bet.setScoreEvidenceQuality("DECISION_GRADE");
        bet.setScoreEvidenceAgreeingSources(2);

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertEquals(0.45, assessment.confidence());
        assertFalse(assessment.calibrationEligible());
    }

    @Test
    void outcomeIdentityMustMatchTheTrackedPlayers() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("OFFICIAL_RESULT");
        bet.setSettlementAmbiguityScore(0.0);
        bet.setWinnerPlayerId(999L);

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertFalse(assessment.calibrationEligible());
        assertEquals("INVALID_WINNER_IDENTITY", assessment.exclusionReason());
    }

    @Test
    void ambiguousArchiveSettlementIsQuarantinedEvenWhenOfficial() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("SETTLED_FROM_OFFICIAL_RESULT_V3");
        bet.setSettlementConfidence(0.98);
        bet.setSettlementAmbiguityScore(0.30);

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertFalse(assessment.learningEligible());
        assertEquals("AMBIGUOUS_ARCHIVE_SETTLEMENT", assessment.exclusionReason());

        bet.setSettlementAmbiguityScore(0.29);
        assertTrue(LearningSampleQuality.assess(bet).learningEligible());
    }

    @Test
    void archiveWithoutPersistedIdentityAssessmentRemainsTelemetryOnly() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("OFFICIAL_RESULT");

        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);

        assertFalse(assessment.learningEligible());
        assertEquals("UNVERIFIED_ARCHIVE_SETTLEMENT", assessment.exclusionReason());

        bet.setSettlementReason("SETTLED_FROM_OFFICIAL_RESULT_FEED_IDENTITY");
        assertTrue(LearningSampleQuality.assess(bet).learningEligible());
    }

    @Test
    void contradictoryOrWinnerConflictingEvidenceNeverBecomesALabel() {
        PaperTradeBet bet = resolvedBet();
        bet.setSettlementSource("OFFICIAL_RESULT");
        bet.setSettlementAmbiguityScore(0.0);
        bet.setScoreEvidenceContradictory(true);

        assertEquals("CONTRADICTORY_SCORE_EVIDENCE",
                LearningSampleQuality.assess(bet).exclusionReason());

        bet.setScoreEvidenceContradictory(false);
        bet.setScoreEvidenceInferredWinnerId(2L);
        assertEquals("EVIDENCE_WINNER_CONFLICT",
                LearningSampleQuality.assess(bet).exclusionReason());
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
