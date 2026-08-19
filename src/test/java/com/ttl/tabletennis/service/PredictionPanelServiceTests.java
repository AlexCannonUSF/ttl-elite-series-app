package com.ttl.tabletennis.service;

import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.dto.PredictionPanelDto;
import com.ttl.tabletennis.prediction.conformal.ConformalPredictor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PredictionPanelServiceTests {

    private final ConformalPredictor predictor = new ConformalPredictor();

    @Test
    void buildPopulatesProbabilityConformalAndContributions() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        when(facade.predict(any(), any(), any(), any())).thenReturn(snapshot(0.62, 0.48, 0.74));
        when(facade.latestTrainingReport()).thenReturn(trainingReport(
                new ModelTrainingReportDto.CalibrationBinDto(0.0, 0.5, 50, 0.30, 0.27),
                new ModelTrainingReportDto.CalibrationBinDto(0.5, 1.0, 50, 0.75, 0.78)
        ));

        PredictionPanelService service = new PredictionPanelService(facade, predictor);
        PredictionPanelDto panel = service.build(10L, 20L, LocalDate.of(2026, 5, 18), null);

        assertEquals(0.62, panel.pTop().value(), 1e-9);
        assertEquals(0.38, panel.pBot().value(), 1e-9);
        assertEquals(0.48, panel.pTop().intervalLow(), 1e-9);
        assertEquals(0.74, panel.pTop().intervalHigh(), 1e-9);
        // pBot interval is the mirror of pTop's bounds.
        assertEquals(1.0 - 0.74, panel.pBot().intervalLow(), 1e-9);
        assertEquals(1.0 - 0.48, panel.pBot().intervalHigh(), 1e-9);

        assertNotNull(panel.conformal());
        assertEquals(0.9, panel.conformal().coverage(), 1e-9);
        assertEquals(0.1, panel.conformal().alpha(), 1e-9);
        assertTrue(List.of("CONFIDENT_TOP", "CONFIDENT_BOT", "AMBIGUOUS", "ANOMALOUS")
                .contains(panel.conformal().label()));
        assertEquals("heuristic-uncalibrated", panel.conformal().method());

        assertEquals(2, panel.reliabilityCurve().size());
        assertEquals(50, panel.reliabilityCurve().get(0).count());
    }

    @Test
    void buildTrimsContributionsToTopKByAbsoluteContribution() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        when(facade.predict(any(), any(), any(), any())).thenReturn(snapshot(0.55, 0.4, 0.7, List.of(
                new MatchupAnalysisDto.FeatureContributionDto("small", 0.01),
                new MatchupAnalysisDto.FeatureContributionDto("big", -0.4),
                new MatchupAnalysisDto.FeatureContributionDto("mid", 0.12),
                new MatchupAnalysisDto.FeatureContributionDto("tiny", -0.003),
                new MatchupAnalysisDto.FeatureContributionDto("huge", 0.55)
        )));
        when(facade.latestTrainingReport()).thenReturn(null);

        PredictionPanelService service = new PredictionPanelService(facade, predictor);
        PredictionPanelDto panel = service.build(10L, 20L, null, null, 3);

        assertEquals(3, panel.topContributions().size());
        assertEquals("huge", panel.topContributions().get(0).feature());
        assertEquals("big", panel.topContributions().get(1).feature());
        assertEquals("mid", panel.topContributions().get(2).feature());
    }

    @Test
    void buildSwallowsTrainingReportFailureAndReturnsEmptyReliability() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        when(facade.predict(any(), any(), any(), any())).thenReturn(snapshot(0.5, 0.4, 0.6));
        when(facade.latestTrainingReport()).thenThrow(new RuntimeException("kaboom"));

        PredictionPanelService service = new PredictionPanelService(facade, predictor);
        PredictionPanelDto panel = service.build(10L, 20L, null, null);

        assertTrue(panel.reliabilityCurve().isEmpty());
    }

    @Test
    void buildRejectsSamePlayerAndNonPositiveK() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        PredictionPanelService service = new PredictionPanelService(facade, predictor);
        assertThrows(IllegalArgumentException.class,
                () -> service.build(10L, 10L, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.build(10L, 20L, null, null, 0));
    }

    @Test
    void buildClampsOutOfRangeSnapshotValues() {
        PredictionFacade facade = Mockito.mock(PredictionFacade.class);
        when(facade.predict(any(), any(), any(), any())).thenReturn(snapshot(1.5, -0.1, 1.4));
        when(facade.latestTrainingReport()).thenReturn(null);

        PredictionPanelService service = new PredictionPanelService(facade, predictor);
        PredictionPanelDto panel = service.build(10L, 20L, null, null);
        assertEquals(1.0, panel.pTop().value(), 1e-9);
        assertEquals(0.0, panel.pTop().intervalLow(), 1e-9);
        assertEquals(1.0, panel.pTop().intervalHigh(), 1e-9);
    }

    @Test
    void matchKeyIsSymmetricInPlayerOrder() {
        assertEquals(
                PredictionPanelService.matchKey(20L, 10L, LocalDate.of(2026, 5, 18)),
                PredictionPanelService.matchKey(10L, 20L, LocalDate.of(2026, 5, 18))
        );
        assertTrue(PredictionPanelService.matchKey(10L, 20L, null).endsWith("@latest"));
    }

    @Test
    void trimContributionsHandlesNullsAndNansSafely() {
        List<MatchupAnalysisDto.FeatureContributionDto> source = java.util.Arrays.asList(
                null,
                new MatchupAnalysisDto.FeatureContributionDto(null, 0.5),
                new MatchupAnalysisDto.FeatureContributionDto("nan", Double.NaN),
                new MatchupAnalysisDto.FeatureContributionDto("ok", -0.2)
        );
        List<MatchupAnalysisDto.FeatureContributionDto> trimmed =
                PredictionPanelService.trimContributions(source, 5);
        assertEquals(1, trimmed.size());
        assertEquals("ok", trimmed.get(0).feature());
        assertFalse(Double.isNaN(trimmed.get(0).contribution()));
    }

    // ---- helpers ----

    private static PredictionModelService.PredictionSnapshot snapshot(double pTop, double lo, double hi) {
        return snapshot(pTop, lo, hi, List.of(
                new MatchupAnalysisDto.FeatureContributionDto("rater.ensemble.delta", 0.3),
                new MatchupAnalysisDto.FeatureContributionDto("form.top.10.dominance", 0.18),
                new MatchupAnalysisDto.FeatureContributionDto("h2h.win_rate_top", -0.05)
        ));
    }

    private static PredictionModelService.PredictionSnapshot snapshot(double pTop, double lo, double hi,
                                                                       List<MatchupAnalysisDto.FeatureContributionDto> contributions) {
        return new PredictionModelService.PredictionSnapshot(
                "ENSEMBLE", "v2.3.1", "isotonic",
                pTop, 1.0 - pTop, lo, hi,
                contributions,
                null,
                pTop, pTop, pTop, pTop, pTop, pTop
        );
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
}
