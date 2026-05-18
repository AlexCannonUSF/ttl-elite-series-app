package com.ttl.tabletennis.service;

import com.ttl.tabletennis.dto.AdaptiveRegimeProfileDto;
import com.ttl.tabletennis.dto.ModelRegistryEntryDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
