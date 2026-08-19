package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.ModelLearningAuditDto;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelLearningAuditServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-29T20:00:00Z");
    private static final LocalDateTime EVENT_TIME = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Test
    void auditSeparatesTrustedLabelsFromTelemetryAndReportsRealClvCoverage() {
        PaperTradeLearningSampleRepository learningRepository = mock(PaperTradeLearningSampleRepository.class);
        TrackedMatchObservationRepository observationRepository = mock(TrackedMatchObservationRepository.class);
        ModelLearningAuditService service = new ModelLearningAuditService(
                learningRepository,
                observationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        PaperTradeLearningSample won = sample(PaperTradeBet.STATUS_WON, true, 0.60, 8.0);
        won.setClosingDecimalOdds(1.80);
        PaperTradeLearningSample lost = sample(PaperTradeBet.STATUS_LOST, true, 0.60, -10.0);
        lost.setSideOrientation("P2");
        PaperTradeLearningSample lowConfidence = sample(PaperTradeBet.STATUS_WON, false, 0.70, 7.0);
        lowConfidence.setLearningExclusionReason("LOW_CONFIDENCE_SETTLEMENT");
        PaperTradeLearningSample nonBinary = sample(PaperTradeBet.STATUS_VOIDED, false, 0.50, 0.0);
        nonBinary.setLearningExclusionReason("NON_BINARY_OUTCOME");
        when(learningRepository.findLearningEvidenceAfter(any()))
                .thenReturn(List.of(won, lost, lowConfidence, nonBinary));

        TrackedMatchObservation score = new TrackedMatchObservation();
        score.setProvisionalOutcomeMethod("CURRENT_SCORE_LEADER");
        score.setProvisionalOutcomeConfidence(0.70);
        score.setProvisionalCorrect(Boolean.TRUE);
        when(observationRepository.findByProvisionalResolvedAtAfterAndProvisionalCorrectIsNotNull(any()))
                .thenReturn(List.of(score));

        ModelLearningAuditDto audit = service.snapshot(180);

        assertEquals(NOW, audit.generatedAt());
        assertEquals(4, audit.outcomeQuality().totalSamples());
        assertEquals(2, audit.outcomeQuality().trustedSettledSamples());
        assertEquals(2, audit.outcomeQuality().excludedSettledSamples());
        assertEquals(2, audit.outcomeQuality().calibrationEligible());
        assertEquals(1, audit.outcomeQuality().lowConfidenceExcluded());
        assertEquals(1, audit.outcomeQuality().nonBinaryExcluded());
        assertEquals(50.0, audit.outcomeQuality().eligibleCoveragePct());
        assertEquals(2, audit.outcomeQuality().exclusionReasons().size());
        assertEquals(2, audit.calibrationEvidence().rawSampleSize());
        assertEquals(2.0, audit.calibrationEvidence().effectiveSampleSize());
        assertEquals(0.60, audit.calibrationEvidence().meanPredicted());
        assertEquals(0.50, audit.calibrationEvidence().observedWinRate());
        assertEquals(0.26, audit.calibrationEvidence().brierScore());
        assertEquals(1, audit.scoreRules().size());
        assertEquals(1.0, audit.scoreRules().get(0).accuracy());
        assertEquals(2, audit.clv().eligibleBets());
        assertEquals(1, audit.clv().closingLineSamples());
        assertEquals(50.0, audit.clv().coveragePct());
        assertNotNull(audit.clv().stakeWeightedClvPct());
        assertEquals(11.11, audit.clv().stakeWeightedClvPct(), 1.0e-9);
    }

    @Test
    void emptyEvidenceProducesStableZeroValuedReport() {
        PaperTradeLearningSampleRepository learningRepository = mock(PaperTradeLearningSampleRepository.class);
        TrackedMatchObservationRepository observationRepository = mock(TrackedMatchObservationRepository.class);
        when(learningRepository.findLearningEvidenceAfter(any())).thenReturn(List.of());
        when(observationRepository.findByProvisionalResolvedAtAfterAndProvisionalCorrectIsNotNull(any()))
                .thenReturn(List.of());
        ModelLearningAuditService service = new ModelLearningAuditService(
                learningRepository,
                observationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        ModelLearningAuditDto audit = service.snapshot(1);

        assertEquals(7, audit.windowDays(), "window is clamped to a meaningful minimum");
        assertEquals(0, audit.outcomeQuality().totalSamples());
        assertEquals(0.0, audit.calibrationEvidence().effectiveSampleSize());
        assertEquals(0, audit.clv().closingLineSamples());
    }

    private PaperTradeLearningSample sample(String status,
                                            boolean eligible,
                                            double modelProbability,
                                            double pnl) {
        PaperTradeLearningSample sample = new PaperTradeLearningSample();
        sample.setStatus(status);
        sample.setCalibrationEligible(eligible);
        sample.setSettlementConfidence(1.0);
        sample.setModelProbability(modelProbability);
        sample.setImpliedProbability(0.50);
        sample.setStake(10.0);
        sample.setProfitLoss(pnl);
        sample.setEventOccurredAt(EVENT_TIME);
        sample.setTopTrigger("Recent Form");
        sample.setPriceRegime("BALANCED");
        sample.setSideOrientation("P1");
        sample.setFeatureContributions("Elo Rating Delta=0.4000|Recent Form=-0.1000");
        return sample;
    }
}
