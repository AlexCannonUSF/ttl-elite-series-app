package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.dto.MlQualityDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MlQualityServiceTests {

    private static final Instant NOW = Instant.parse("2026-05-18T12:00:00Z");

    @Test
    void snapshotPopulatesTrainingAndRecentBlocks() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);

        when(facade.latestTrainingReport()).thenReturn(trainingReport(
                new ModelTrainingReportDto.CalibrationBinDto(0.0, 0.5, 200, 0.30, 0.28),
                new ModelTrainingReportDto.CalibrationBinDto(0.5, 1.0, 200, 0.75, 0.78)
        ));
        when(repo.findCalibrationEligibleAfter(any())).thenReturn(buildSamples(0.20, 0.25, 0.40, 0.55, 0.70, 0.85, 0.92));

        MlQualityService service = new MlQualityService(facade, repo, fixedClock());
        MlQualityDto snapshot = service.snapshot(14, 5);

        assertEquals(14, snapshot.windowDays());
        assertNotNull(snapshot.training());
        assertEquals(400, snapshot.training().sampleCount());
        assertNotNull(snapshot.training().ece());

        assertNotNull(snapshot.recent());
        assertEquals(7, snapshot.recent().sampleCount());
        assertNotNull(snapshot.recent().ece());
        assertNotNull(snapshot.recent().brierScore());
        assertEquals(5, snapshot.recent().bins().size());
    }

    @Test
    void emptyRecentSettledYieldsEmptySnapshot() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(facade.latestTrainingReport()).thenReturn(null);
        when(repo.findCalibrationEligibleAfter(any())).thenReturn(List.of());

        MlQualityService service = new MlQualityService(facade, repo, fixedClock());
        MlQualityDto snapshot = service.snapshot(7, 10);

        assertEquals(0, snapshot.recent().sampleCount());
        assertTrue(snapshot.recent().bins().isEmpty());
        assertEquals(0, snapshot.training().sampleCount());
        assertNotNull(snapshot.dailyVolume());
        assertFalse(snapshot.dailyVolume().isEmpty()); // day series is filled even when empty
        // Every histogram bucket present
        assertEquals(10, snapshot.probabilityHistogram().size());
        assertTrue(snapshot.probabilityHistogram().stream().allMatch(b -> b.count() == 0));
    }

    @Test
    void rejectsBadParameters() {
        MlQualityService service = new MlQualityService(
                Mockito.mock(PredictionFacade.class),
                Mockito.mock(PaperTradeLearningSampleRepository.class),
                fixedClock()
        );
        assertThrows(IllegalArgumentException.class, () -> service.snapshot(0, 5));
        assertThrows(IllegalArgumentException.class, () -> service.snapshot(7, 1));
    }

    @Test
    void eceMathMatchesWeightedAbsDeviation() {
        List<MlQualityDto.ReliabilityBin> bins = List.of(
                new MlQualityDto.ReliabilityBin(0.0, 0.5, 100, 0.30, 0.25),  // |.05| * 0.5
                new MlQualityDto.ReliabilityBin(0.5, 1.0, 100, 0.80, 0.70)   // |.10| * 0.5
        );
        Double ece = MlQualityService.computeEce(bins);
        assertNotNull(ece);
        assertEquals(0.5 * 0.05 + 0.5 * 0.10, ece, 1e-9);
    }

    @Test
    void maxBinDeviationPicksLargestAbsoluteGap() {
        List<MlQualityDto.ReliabilityBin> bins = List.of(
                new MlQualityDto.ReliabilityBin(0.0, 0.5, 100, 0.30, 0.20),  // 0.10
                new MlQualityDto.ReliabilityBin(0.5, 1.0, 100, 0.80, 0.75)   // 0.05
        );
        assertEquals(0.10, MlQualityService.computeMaxBinDeviation(bins), 1e-9);
    }

    @Test
    void brierScoreMatchesAverageSquaredError() {
        List<double[]> rows = Arrays.asList(new double[]{0.8, 1}, new double[]{0.2, 0}, new double[]{0.6, 0});
        Double brier = MlQualityService.computeBrier(rows);
        assertNotNull(brier);
        // ((0.2)^2 + (0.2)^2 + (0.6)^2) / 3 = (0.04 + 0.04 + 0.36) / 3 = 0.4400 / 3
        assertEquals((0.04 + 0.04 + 0.36) / 3.0, brier, 1e-9);
    }

    @Test
    void equalMassBinsProduceMonotonicLowToHighEdges() {
        List<double[]> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            rows.add(new double[]{i / 100.0, i > 50 ? 1.0 : 0.0});
        }
        List<MlQualityDto.ReliabilityBin> bins = MlQualityService.equalMassBins(rows, 10);
        assertEquals(10, bins.size());
        double prev = -1.0;
        for (MlQualityDto.ReliabilityBin bin : bins) {
            assertTrue(bin.lowerBound() >= prev);
            prev = bin.upperBound();
        }
    }

    @Test
    void driftSeverityClassifierReturnsExpectedBuckets() {
        assertEquals("GREEN", MlQualityService.classifySeverity(0.005, 0.01));
        assertEquals("AMBER", MlQualityService.classifySeverity(0.025, 0.0));
        assertEquals("RED", MlQualityService.classifySeverity(0.05, 0.0));
        assertEquals("RED", MlQualityService.classifySeverity(0.0, 0.07));
        assertEquals("UNKNOWN", MlQualityService.classifySeverity(null, null));
    }

    @Test
    void weightedMeanIgnoresZeroCountBins() {
        List<MlQualityDto.ReliabilityBin> bins = List.of(
                new MlQualityDto.ReliabilityBin(0.0, 0.5, 0, 0.30, 0.20),
                new MlQualityDto.ReliabilityBin(0.5, 1.0, 100, 0.80, 0.75)
        );
        Double mean = MlQualityService.weightedMean(bins, MlQualityDto.ReliabilityBin::meanPredicted);
        assertEquals(0.80, mean, 1e-9);
    }

    @Test
    void dailyVolumeSeriesIsFilledFromCutoffThroughToday() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(facade.latestTrainingReport()).thenReturn(null);
        when(repo.findCalibrationEligibleAfter(any())).thenReturn(buildSamples(0.6, 0.7));

        MlQualityService service = new MlQualityService(facade, repo, fixedClock());
        MlQualityDto snapshot = service.snapshot(7, 5);
        assertNotNull(snapshot.dailyVolume());
        // 7-day window → 8 daily buckets (inclusive of both ends)
        assertEquals(8, snapshot.dailyVolume().size());
    }

    @Test
    void trainingReportFailureFallsBackToEmptySnapshot() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(facade.latestTrainingReport()).thenThrow(new RuntimeException("boom"));
        when(repo.findCalibrationEligibleAfter(any())).thenReturn(List.of());

        MlQualityService service = new MlQualityService(facade, repo, fixedClock());
        MlQualityDto snapshot = service.snapshot(7, 5);
        assertEquals(0, snapshot.training().sampleCount());
        assertNull(snapshot.training().ece());
    }

    // ---- helpers --------------------------------------------------------

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static ModelTrainingReportDto trainingReport(ModelTrainingReportDto.CalibrationBinDto... bins) {
        return new ModelTrainingReportDto(
                "job-1",
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 5, 1),
                12345,
                42,
                "ENSEMBLE",
                "v2.3.1",
                LocalDateTime.of(2026, 5, 17, 4, 0),
                List.of(),
                List.of(bins),
                List.of(),
                List.of()
        );
    }

    private static List<PaperTradeLearningSample> buildSamples(double... probs) {
        List<PaperTradeLearningSample> samples = new ArrayList<>();
        for (int i = 0; i < probs.length; i++) {
            PaperTradeLearningSample sample = new PaperTradeLearningSample();
            sample.setModelProbability(probs[i]);
            sample.setStatus(probs[i] > 0.5 ? "WON" : "LOST");
            sample.setSettledAt(LocalDateTime.of(2026, 5, 17, 12, 0).minusHours(i));
            samples.add(sample);
        }
        return samples;
    }

    private static PaperTradeLearningSample sampleWithStatus(double p, String status) {
        PaperTradeLearningSample s = new PaperTradeLearningSample();
        s.setModelProbability(p);
        s.setStatus(status);
        s.setSettledAt(LocalDateTime.of(2026, 5, 17, 12, 0));
        return s;
    }

    // --- #115 tests: VOID/PUSH must NOT inflate calibration error ---

    @Test
    void voidedSamplesAreExcludedFromCalibration() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(facade.latestTrainingReport()).thenReturn(null);

        // 1 WON + 5 VOIDED. Before #115: voided samples coerced to y=0 → ECE distorted.
        // After #115: voided samples excluded → only 1 sample feeds calibration.
        List<PaperTradeLearningSample> samples = new ArrayList<>();
        samples.add(sampleWithStatus(0.70, "WON"));
        for (int i = 0; i < 5; i++) {
            samples.add(sampleWithStatus(0.70, "VOIDED"));
        }
        when(repo.findCalibrationEligibleAfter(any())).thenReturn(samples);

        MlQualityService service = new MlQualityService(facade, repo, fixedClock());
        MlQualityDto snapshot = service.snapshot(14, 2);

        // Recent block contains only the 1 WON sample, not 6.
        assertEquals(1, snapshot.recent().sampleCount(),
                "VOIDED samples must not enter calibration sample count");
        // With the single sample @ p=0.70, y=1.0 → bin meanPredicted=0.70 observedRate=1.0.
        // ECE = |0.70 - 1.0| = 0.30. If VOIDs had been included as y=0 the ECE would
        // be vastly different (and bogus).
        assertTrue(snapshot.recent().ece() != null);
    }

    @Test
    void pushedSamplesAreExcludedFromCalibration() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(facade.latestTrainingReport()).thenReturn(null);

        List<PaperTradeLearningSample> samples = new ArrayList<>();
        samples.add(sampleWithStatus(0.60, "WON"));
        samples.add(sampleWithStatus(0.55, "LOST"));
        samples.add(sampleWithStatus(0.50, "PUSHED"));
        samples.add(sampleWithStatus(0.50, "PUSH"));
        when(repo.findCalibrationEligibleAfter(any())).thenReturn(samples);

        MlQualityService service = new MlQualityService(facade, repo, fixedClock());
        MlQualityDto snapshot = service.snapshot(14, 2);

        assertEquals(2, snapshot.recent().sampleCount(),
                "PUSHED/PUSH samples must not enter calibration sample count");
    }

    @Test
    void mixedStatusesOnlyWonAndLostCounted() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        PaperTradeLearningSampleRepository repo = Mockito.mock(PaperTradeLearningSampleRepository.class);
        when(facade.latestTrainingReport()).thenReturn(null);

        List<PaperTradeLearningSample> samples = List.of(
                sampleWithStatus(0.70, "WON"),
                sampleWithStatus(0.60, "LOST"),
                sampleWithStatus(0.50, "VOIDED"),
                sampleWithStatus(0.45, "PUSHED"),
                sampleWithStatus(0.40, "OPEN"),    // shouldn't happen but should be filtered as not-resolved-WON anyway
                sampleWithStatus(0.30, "LOST")
        );
        when(repo.findCalibrationEligibleAfter(any())).thenReturn(samples);

        MlQualityService service = new MlQualityService(facade, repo, fixedClock());
        MlQualityDto snapshot = service.snapshot(14, 3);

        // 1 WON + 2 LOST + 1 OPEN-treated-as-LOST = 4 entries in calibration (since OPEN
        // isn't in the NON_RESOLVED set, the original WON/0.0 behaviour applies for back-compat).
        // VOIDED + PUSHED are dropped.
        assertEquals(4, snapshot.recent().sampleCount());
    }
}
