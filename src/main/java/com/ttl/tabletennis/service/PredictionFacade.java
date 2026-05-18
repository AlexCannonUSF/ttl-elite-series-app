package com.ttl.tabletennis.service;

import com.ttl.tabletennis.dto.AdaptiveRegimeProfileDto;
import com.ttl.tabletennis.dto.ModelRegistryEntryDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

@Service
public class PredictionFacade {

    private final PredictionModelService predictionModelService;
    private final MeterRegistry meterRegistry;

    public PredictionFacade(PredictionModelService predictionModelService, MeterRegistry meterRegistry) {
        this.predictionModelService = predictionModelService;
        this.meterRegistry = meterRegistry;
    }

    public PredictionModelService.PredictionSnapshot predict(Long player1Id,
                                                            Long player2Id,
                                                            LocalDate asOfDate,
                                                            String requestedFamily) {
        return record("predict", () -> predictionModelService.predict(player1Id, player2Id, asOfDate, requestedFamily));
    }

    public List<ModelRegistryEntryDto> recentRegistry(String family, int limit) {
        return record("recentRegistry", () -> predictionModelService.recentRegistry(family, limit));
    }

    public ModelTrainingReportDto latestTrainingReport() {
        return record("latestTrainingReport", predictionModelService::latestTrainingReport);
    }

    public List<AdaptiveRegimeProfileDto> currentAdaptiveRegimeProfiles() {
        return record("currentAdaptiveRegimeProfiles", predictionModelService::currentAdaptiveRegimeProfiles);
    }

    public PredictionModelService.AdaptiveRegimeTuning currentAdaptiveRegimeTuning(boolean live,
                                                                                    String phase,
                                                                                    double impliedProbability) {
        return record(
                "currentAdaptiveRegimeTuning",
                () -> predictionModelService.currentAdaptiveRegimeTuning(live, phase, impliedProbability)
        );
    }

    public ModelTrainingReportDto trainModels(LocalDate fromDate, LocalDate toDate) {
        return record("trainModels", () -> predictionModelService.trainModels(fromDate, toDate));
    }

    private <T> T record(String operation, Supplier<T> supplier) {
        if (meterRegistry == null) {
            return supplier.get();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return supplier.get();
        } finally {
            meterRegistry.counter("ttl.facade.calls", "facade", "prediction", "operation", operation).increment();
            sample.stop(meterRegistry.timer("ttl.facade.duration", "facade", "prediction", "operation", operation));
        }
    }
}
