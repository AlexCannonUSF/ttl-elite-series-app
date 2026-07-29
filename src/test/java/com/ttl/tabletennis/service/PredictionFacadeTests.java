package com.ttl.tabletennis.service;

import com.ttl.tabletennis.dto.AdaptiveRegimeProfileDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.dto.ModelRegistryEntryDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.prediction.shadow.PredictionShadowService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PredictionFacadeTests {

    @Test
    void delegatesPredictionOperationsWithoutChangingPayloads() {
        PredictionModelService predictionModelService = mock(PredictionModelService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        PredictionFacade predictionFacade = new PredictionFacade(predictionModelService, meterRegistry);

        PredictionModelService.PredictionSnapshot snapshot = new PredictionModelService.PredictionSnapshot(
                "ENSEMBLE",
                "20260416153000-ENSEMBLE-1",
                "PLATT",
                0.63,
                0.37,
                0.57,
                0.69,
                List.of(),
                null,
                0.58,
                0.60,
                0.56,
                0.62,
                0.61,
                0.63
        );
        List<ModelRegistryEntryDto> registry = List.of(
                new ModelRegistryEntryDto(
                        1L,
                        "20260416153000-ENSEMBLE-1",
                        "ENSEMBLE",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 4, 2),
                        LocalDate.of(2026, 4, 10),
                        0.61,
                        0.58,
                        0.21,
                        "PLATT",
                        0.001,
                        5,
                        true,
                        "seed report",
                        LocalDateTime.of(2026, 4, 16, 15, 30)
                )
        );
        ModelTrainingReportDto trainingReport = new ModelTrainingReportDto(
                "job-3.0-phase00",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 4, 1),
                180,
                12,
                "ENSEMBLE",
                "20260416153000-ENSEMBLE-1",
                LocalDateTime.of(2026, 4, 16, 15, 30),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        List<AdaptiveRegimeProfileDto> profiles = List.of(
                new AdaptiveRegimeProfileDto("Live", 42, 0.77, 1.2, 3.4, 0.98, 0.03, true, "LIVE_MID", "FAVORITE")
        );
        PredictionModelService.AdaptiveRegimeTuning regimeTuning =
                new PredictionModelService.AdaptiveRegimeTuning("Live", 0.77, 0.98, 0.03, 0.01, 0.02);

        when(predictionModelService.predict(11L, 22L, LocalDate.of(2026, 4, 16), "ENSEMBLE")).thenReturn(snapshot);
        when(predictionModelService.recentRegistry("ENSEMBLE", 10)).thenReturn(registry);
        when(predictionModelService.latestTrainingReport()).thenReturn(trainingReport);
        when(predictionModelService.currentAdaptiveRegimeProfiles()).thenReturn(profiles);
        when(predictionModelService.currentAdaptiveRegimeTuning(true, "LIVE_MID", 0.61)).thenReturn(regimeTuning);
        when(predictionModelService.trainModels(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1))).thenReturn(trainingReport);

        assertSame(snapshot, predictionFacade.predict(11L, 22L, LocalDate.of(2026, 4, 16), "ENSEMBLE"));
        assertSame(registry, predictionFacade.recentRegistry("ENSEMBLE", 10));
        assertSame(trainingReport, predictionFacade.latestTrainingReport());
        assertSame(profiles, predictionFacade.currentAdaptiveRegimeProfiles());
        assertSame(regimeTuning, predictionFacade.currentAdaptiveRegimeTuning(true, "LIVE_MID", 0.61));
        assertSame(trainingReport, predictionFacade.trainModels(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)));

        verify(predictionModelService).predict(11L, 22L, LocalDate.of(2026, 4, 16), "ENSEMBLE");
        verify(predictionModelService).recentRegistry("ENSEMBLE", 10);
        verify(predictionModelService).latestTrainingReport();
        verify(predictionModelService).currentAdaptiveRegimeProfiles();
        verify(predictionModelService).currentAdaptiveRegimeTuning(true, "LIVE_MID", 0.61);
        verify(predictionModelService).trainModels(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1));

        assertEquals(1.0, meterRegistry.get("ttl.facade.calls").tag("facade", "prediction").tag("operation", "predict").counter().count());
        assertEquals(1.0, meterRegistry.get("ttl.facade.calls").tag("facade", "prediction").tag("operation", "recentRegistry").counter().count());
        assertEquals(1.0, meterRegistry.get("ttl.facade.calls").tag("facade", "prediction").tag("operation", "latestTrainingReport").counter().count());
        assertEquals(1.0, meterRegistry.get("ttl.facade.calls").tag("facade", "prediction").tag("operation", "currentAdaptiveRegimeProfiles").counter().count());
        assertEquals(1.0, meterRegistry.get("ttl.facade.calls").tag("facade", "prediction").tag("operation", "currentAdaptiveRegimeTuning").counter().count());
        assertEquals(1.0, meterRegistry.get("ttl.facade.calls").tag("facade", "prediction").tag("operation", "trainModels").counter().count());
    }

    @Test
    void forwardsRaterEnsembleDeltaToPredictionShadow() {
        PredictionModelService predictionModelService = mock(PredictionModelService.class);
        PredictionShadowService shadowService = mock(PredictionShadowService.class);
        PredictionFacade predictionFacade = new PredictionFacade(
                predictionModelService,
                new SimpleMeterRegistry(),
                Optional.of(shadowService)
        );
        MatchupFeatureVectorDto featureVector = featureVector(11L, 22L);
        PredictionModelService.PredictionSnapshot snapshot = new PredictionModelService.PredictionSnapshot(
                "ENSEMBLE",
                "20260518150000-ENSEMBLE-1",
                "PLATT",
                0.64,
                0.36,
                0.57,
                0.70,
                List.of(),
                featureVector,
                0.58,
                0.60,
                0.61,
                0.62,
                0.63,
                0.64
        );

        when(predictionModelService.predict(11L, 22L, LocalDate.of(2026, 5, 18), "ENSEMBLE")).thenReturn(snapshot);

        predictionFacade.predict(11L, 22L, LocalDate.of(2026, 5, 18), "ENSEMBLE");

        ArgumentCaptor<PredictionShadowService.ShadowContext> captor =
                ArgumentCaptor.forClass(PredictionShadowService.ShadowContext.class);
        verify(shadowService).shadow(captor.capture());
        PredictionShadowService.ShadowContext context = captor.getValue();
        assertEquals(0.105, (double) context.extraFeatures().get("rater.ensemble.delta"), 0.000001);
        assertEquals(1600.0, (double) context.extraFeatures().get("rater.glicko.top.mu"), 0.000001);
        assertEquals(26.4, (double) context.extraFeatures().get("rater.ts2.top.mu"), 0.000001);
        assertTrue(context.extraFeatures().containsKey("rater.wenglin.delta"));
    }

    private MatchupFeatureVectorDto featureVector(Long player1Id, Long player2Id) {
        MatchupFeatureVectorDto.PlayerFeatureDto p1 = new MatchupFeatureVectorDto.PlayerFeatureDto(
                0.62,
                0.58,
                1540.0,
                1585.0,
                1600.0,
                70.0,
                0.05,
                26.4,
                2.2,
                0.60,
                0.35,
                9.0,
                8.0,
                7.0,
                0.64,
                0.61,
                0.58,
                0.82
        );
        MatchupFeatureVectorDto.PlayerFeatureDto p2 = new MatchupFeatureVectorDto.PlayerFeatureDto(
                0.47,
                0.44,
                1490.0,
                1510.0,
                1508.0,
                82.0,
                0.06,
                24.8,
                2.4,
                -0.10,
                0.40,
                8.0,
                7.0,
                6.0,
                0.60,
                0.57,
                0.54,
                0.78
        );
        return new MatchupFeatureVectorDto(
                player1Id,
                player2Id,
                LocalDate.of(2026, 5, 18),
                0.60,
                0.40,
                7.0,
                0.55,
                p1,
                p2,
                0.59,
                0.60,
                0.70,
                0.45,
                0.605,
                0.105,
                new MatchupFeatureVectorDto.ReliabilitySummaryDto(0.66, 0.72, 0.82, 0.78),
                new MatchupFeatureVectorDto.SignificanceSummaryDto(
                        0.60,
                        0.55,
                        0.62,
                        0.59,
                        0.56,
                        0.76,
                        1,
                        5,
                        0,
                        "Baseline Stability",
                        0.76,
                        "Head-to-Head",
                        0.55
                ),
                new MatchupFeatureVectorDto.RatingIntervalDto(1460.0, 1740.0),
                new MatchupFeatureVectorDto.RatingIntervalDto(1346.0, 1674.0)
        );
    }
}
