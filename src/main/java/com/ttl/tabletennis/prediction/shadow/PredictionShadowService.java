package com.ttl.tabletennis.prediction.shadow;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PredictionDiffLog;
import com.ttl.tabletennis.repository.PredictionDiffLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Orchestrates the Phase 04 item 11 shadow path: decides whether a predict
 * call gets a v3 shadow, calls {@link BlenderClient}, and persists the
 * comparison row into {@code prediction_diff_log}. All shadow work runs on
 * an injected executor so the primary v2 path stays fast.
 */
public class PredictionShadowService {

    public static final String FLAG = FeatureFlagCatalog.PREDICT_V3_FLAG;

    private static final Logger log = LoggerFactory.getLogger(PredictionShadowService.class);

    private final FeatureFlagCatalog featureFlagCatalog;
    private final PredictionShadowSampler sampler;
    private final BlenderClient blenderClient;
    private final PredictionDiffLogRepository repository;
    private final Executor executor;
    private final Clock clock;
    private final Duration requestTimeout;
    private final String featureSchemaHash;

    public PredictionShadowService(FeatureFlagCatalog featureFlagCatalog,
                                   PredictionShadowSampler sampler,
                                   BlenderClient blenderClient,
                                   PredictionDiffLogRepository repository,
                                   Executor executor,
                                   Clock clock,
                                   Duration requestTimeout,
                                   String featureSchemaHash) {
        if (sampler == null) {
            throw new IllegalArgumentException("sampler must not be null");
        }
        if (blenderClient == null) {
            throw new IllegalArgumentException("blenderClient must not be null");
        }
        this.featureFlagCatalog = featureFlagCatalog;
        this.sampler = sampler;
        this.blenderClient = blenderClient;
        this.repository = repository;
        this.executor = executor == null ? Runnable::run : executor;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? Duration.ofMillis(750)
                : requestTimeout;
        this.featureSchemaHash = featureSchemaHash == null ? "" : featureSchemaHash.trim();
    }

    public boolean enabled() {
        if (!blenderClient.isEnabled()) {
            return false;
        }
        if (featureFlagCatalog == null) {
            return true;
        }
        String state = featureFlagCatalog.stateOf(FLAG);
        return "shadow".equals(state) || "on".equals(state);
    }

    /**
     * Shadow the given v2 prediction. Returns immediately; the actual
     * blender call + persistence happen on the executor.
     */
    public void shadow(ShadowContext context) {
        if (context == null) {
            return;
        }
        if (!enabled()) {
            return;
        }
        if (!sampler.shouldShadow(context.player1Id(), context.player2Id(), context.asOfDate())) {
            return;
        }
        try {
            executor.execute(() -> safeRun(context));
        } catch (RejectedExecutionException e) {
            log.warn("[predict-v3-shadow] executor rejected shadow task for {}: {}", context.predictionId(), e.getMessage());
        }
    }

    private void safeRun(ShadowContext context) {
        try {
            runShadow(context);
        } catch (RuntimeException e) {
            log.warn("[predict-v3-shadow] unexpected failure for {}: {}", context.predictionId(), e.getMessage());
        }
    }

    private void runShadow(ShadowContext context) {
        if (featureSchemaHash.isBlank()) {
            persist(context, null, BlenderClient.Status.DISABLED.name(), "feature-schema-hash-not-configured", 0L);
            return;
        }

        Map<String, Object> features = featurePayload(context);
        BlenderRequest request = new BlenderRequest(
                context.matchId(),
                featureSchemaHash,
                context.isInPlay(),
                context.isMajorEvent(),
                features
        );
        BlenderClient.Result result = blenderClient.score(request, requestTimeout);
        persist(context, result, result.status().name(), result.reason(), result.latencyMs());
    }

    private void persist(ShadowContext context,
                         BlenderClient.Result result,
                         String shadowStatus,
                         String errorReason,
                         long latencyMs) {
        if (repository == null) {
            return;
        }
        try {
            PredictionDiffLog row = new PredictionDiffLog();
            row.setPredictionId(context.predictionId());
            row.setPlayer1Id(context.player1Id());
            row.setPlayer2Id(context.player2Id());
            row.setAsOfDate(context.asOfDate());
            row.setV2ModelFamily(context.v2ModelFamily());
            row.setV2ModelVersion(context.v2ModelVersion());
            row.setV2P1Probability(toProbability(context.v2P1Probability()));
            row.setShadowStatus(shadowStatus);
            row.setErrorReason(truncate(errorReason, 255));
            row.setLatencyMs(latencyMs <= 0L ? null : latencyMs);
            row.setComputedAtUtc(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

            if (result != null && result.isOk() && result.response().isPresent()) {
                BlenderResponse response = result.response().get();
                row.setV3ModelVersion(response.modelVersion());
                row.setV3CalibratorVersion(response.calibratorVersion());
                row.setV3ConformalVersion(response.conformalVersion());
                row.setV3UncertaintyLabel(response.uncertaintyLabel());
                row.setV3P1Probability(toProbability(response.pTop()));
                row.setAbsDiff(toProbability(Math.abs(context.v2P1Probability() - response.pTop())));
                response.sanity().ifPresent(sanity -> {
                    row.setV3VariantBModelVersion(sanity.modelVersion());
                    row.setV3VariantBP1Probability(toProbability(sanity.pTop()));
                    row.setVariantAbAbsDiff(toProbability(sanity.absoluteDiffPTop()));
                });
            }
            repository.save(row);
        } catch (RuntimeException e) {
            log.warn("[predict-v3-shadow] failed to persist diff for {}: {}", context.predictionId(), e.getMessage());
        }
    }

    private Map<String, Object> featurePayload(ShadowContext context) {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("match.best_of", context.bestOf());
        features.put("match.is_major_event", context.isMajorEvent());
        if (context.extraFeatures() != null) {
            features.putAll(context.extraFeatures());
        }
        return features;
    }

    private static BigDecimal toProbability(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return BigDecimal.valueOf(clamped).setScale(6, RoundingMode.HALF_UP);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public record ShadowContext(String predictionId,
                                long player1Id,
                                long player2Id,
                                LocalDate asOfDate,
                                String matchId,
                                String v2ModelFamily,
                                String v2ModelVersion,
                                double v2P1Probability,
                                int bestOf,
                                boolean isInPlay,
                                boolean isMajorEvent,
                                Map<String, Object> extraFeatures) {

        public ShadowContext {
            predictionId = predictionId == null || predictionId.isBlank()
                    ? UUID.randomUUID().toString()
                    : predictionId.trim();
            matchId = matchId == null || matchId.isBlank()
                    ? "match-" + player1Id + "-" + player2Id + "-" + (asOfDate == null ? "unknown" : asOfDate)
                    : matchId.trim();
            v2ModelFamily = v2ModelFamily == null ? "" : v2ModelFamily.trim();
            v2ModelVersion = v2ModelVersion == null ? "" : v2ModelVersion.trim();
            if (asOfDate == null) {
                asOfDate = LocalDate.now(ZoneOffset.UTC);
            }
            if (bestOf <= 0) {
                bestOf = 5;
            }
            extraFeatures = extraFeatures == null ? Map.of() : Map.copyOf(extraFeatures);
        }
    }
}
