package com.ttl.tabletennis.service;

import com.ttl.tabletennis.dto.AdaptiveRegimeProfileDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.dto.ModelRegistryEntryDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.prediction.shadow.PredictionShadowService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Service
public class PredictionFacade {

    /**
     * Per-matchup prediction cache. {@code predict()} is the single hottest
     * call path on the live-board hot loop, and its cost is dominated by
     * {@link FeatureService#buildMatchupFeatureVector} which fans out into
     * cold-cache H2 page reads against the {@code rating_snapshot} tables.
     * Player ratings only update once a day at most, so caching the full
     * {@link PredictionModelService.PredictionSnapshot} keyed by
     * {@code (p1Id, p2Id, asOfDate, family)} gives us a near-perfect hit
     * rate for the duration of a trading day and makes subsequent live-board
     * computes essentially free after the first hit per matchup.
     *
     * <p>This cache is invalidated automatically by TTL (1 hour). Tests can
     * call {@link #clearPredictCacheForTest()} for deterministic behaviour.
     */
    private static final long PREDICT_CACHE_TTL_MS = 60L * 60L * 1_000L; // 1 hour
    private static final int PREDICT_CACHE_MAX_ENTRIES = 4_096;
    private final ConcurrentMap<PredictCacheKey, CachedPrediction> predictCache = new ConcurrentHashMap<>();

    private record PredictCacheKey(Long player1Id, Long player2Id, LocalDate asOfDate, String family) { }
    private record CachedPrediction(PredictionModelService.PredictionSnapshot snapshot, long capturedAtMillis) { }

    private final PredictionModelService predictionModelService;
    private final MeterRegistry meterRegistry;
    private final Optional<PredictionShadowService> shadowService;

    @Autowired
    public PredictionFacade(PredictionModelService predictionModelService,
                            MeterRegistry meterRegistry,
                            Optional<PredictionShadowService> shadowService) {
        this.predictionModelService = predictionModelService;
        this.meterRegistry = meterRegistry;
        this.shadowService = shadowService == null ? Optional.empty() : shadowService;
    }

    public PredictionFacade(PredictionModelService predictionModelService, MeterRegistry meterRegistry) {
        this(predictionModelService, meterRegistry, Optional.empty());
    }

    public PredictionModelService.PredictionSnapshot predict(Long player1Id,
                                                            Long player2Id,
                                                            LocalDate asOfDate,
                                                            String requestedFamily) {
        LocalDate effectiveDate = asOfDate == null ? LocalDate.now() : asOfDate;
        String effectiveFamily = requestedFamily == null ? "" : requestedFamily;
        PredictCacheKey key = new PredictCacheKey(player1Id, player2Id, effectiveDate, effectiveFamily);
        long now = System.currentTimeMillis();
        CachedPrediction cached = predictCache.get(key);
        if (cached != null && (now - cached.capturedAtMillis()) < PREDICT_CACHE_TTL_MS) {
            if (meterRegistry != null) {
                meterRegistry.counter("ttl.facade.calls",
                        "facade", "prediction",
                        "operation", "predict",
                        "cache", "hit").increment();
            }
            return cached.snapshot();
        }
        PredictionModelService.PredictionSnapshot snapshot = record("predict",
                () -> predictionModelService.predict(player1Id, player2Id, asOfDate, requestedFamily));
        if (snapshot != null) {
            // Tiny LRU-ish guard: drop a few stale entries if we exceed cap.
            if (predictCache.size() >= PREDICT_CACHE_MAX_ENTRIES) {
                evictExpired(now);
            }
            predictCache.put(key, new CachedPrediction(snapshot, now));
            if (meterRegistry != null) {
                meterRegistry.counter("ttl.facade.calls",
                        "facade", "prediction",
                        "operation", "predict",
                        "cache", "miss").increment();
            }
        }
        fireShadow(player1Id, player2Id, asOfDate, snapshot);
        return snapshot;
    }

    /** Drop expired entries; keep newest if everything is still fresh. */
    private void evictExpired(long now) {
        predictCache.entrySet().removeIf(e ->
                (now - e.getValue().capturedAtMillis()) >= PREDICT_CACHE_TTL_MS);
    }

    /** Test-only hook to wipe the prediction cache between cases. */
    public void clearPredictCacheForTest() {
        predictCache.clear();
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

    private void fireShadow(Long player1Id,
                            Long player2Id,
                            LocalDate asOfDate,
                            PredictionModelService.PredictionSnapshot snapshot) {
        if (shadowService.isEmpty() || snapshot == null || player1Id == null || player2Id == null) {
            return;
        }
        PredictionShadowService.ShadowContext context = new PredictionShadowService.ShadowContext(
                UUID.randomUUID().toString(),
                player1Id,
                player2Id,
                asOfDate,
                null,
                snapshot.modelFamily(),
                snapshot.modelVersion(),
                snapshot.player1Probability(),
                0,
                false,
                false,
                v3RaterFeaturePayload(snapshot.featureVector())
        );
        shadowService.get().shadow(context);
    }

    private Map<String, Object> v3RaterFeaturePayload(MatchupFeatureVectorDto featureVector) {
        if (featureVector == null || featureVector.player1() == null || featureVector.player2() == null) {
            return Map.of();
        }

        MatchupFeatureVectorDto.PlayerFeatureDto top = featureVector.player1();
        MatchupFeatureVectorDto.PlayerFeatureDto bot = featureVector.player2();
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("rater.glicko.top.mu", top.glickoRating());
        features.put("rater.glicko.bot.mu", bot.glickoRating());
        features.put("rater.glicko.top.phi", top.glickoRatingDeviation());
        features.put("rater.glicko.bot.phi", bot.glickoRatingDeviation());
        features.put("rater.glicko.delta_mu", top.glickoRating() - bot.glickoRating());
        features.put("rater.glicko.delta_phi_sum", top.glickoRatingDeviation() + bot.glickoRatingDeviation());
        features.put("rater.ts2.top.mu", top.trueSkill2Mu());
        features.put("rater.ts2.bot.mu", bot.trueSkill2Mu());
        features.put("rater.ts2.top.sigma", top.trueSkill2Sigma());
        features.put("rater.ts2.bot.sigma", bot.trueSkill2Sigma());
        features.put("rater.ts2.skill_gap", top.trueSkill2Mu() - bot.trueSkill2Mu());
        features.put("rater.wenglin.delta", featureVector.wengLinProbabilityPlayer1() - 0.5);
        features.put("rater.ensemble.delta", featureVector.raterEnsembleDelta());
        return features;
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
