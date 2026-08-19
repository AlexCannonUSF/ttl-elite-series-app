package com.ttl.tabletennis.cv;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StreamCvVlmFallbackHook {

    private final boolean enabled;
    private final Duration defaultForceTtl;
    private final Map<String, StreamCvVlmForceRequest> forcedMatches = new ConcurrentHashMap<>();

    @Autowired
    public StreamCvVlmFallbackHook(@Value("${ttl.streamCv.vlm.forceHookEnabled:true}") boolean enabled,
                                   @Value("${ttl.streamCv.vlm.forceTtlSeconds:300}") long defaultForceTtlSeconds) {
        this(enabled, Duration.ofSeconds(Math.max(1L, defaultForceTtlSeconds)));
    }

    StreamCvVlmFallbackHook(boolean enabled, Duration defaultForceTtl) {
        this.enabled = enabled;
        this.defaultForceTtl = defaultForceTtl == null ? Duration.ofMinutes(5) : defaultForceTtl;
    }

    public Optional<StreamCvVlmForceRequest> forceNextFrame(String matchId,
                                                           String requestedBy,
                                                           String reason,
                                                           Instant now) {
        return forceNextFrame(matchId, requestedBy, reason, defaultForceTtl, now);
    }

    public Optional<StreamCvVlmForceRequest> forceNextFrame(String matchId,
                                                           String requestedBy,
                                                           String reason,
                                                           Duration ttl,
                                                           Instant now) {
        if (!enabled || matchId == null || matchId.isBlank()) {
            return Optional.empty();
        }
        Instant requestedAt = now == null ? Instant.now() : now;
        Duration effectiveTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? defaultForceTtl : ttl;
        StreamCvVlmForceRequest request = new StreamCvVlmForceRequest(
                matchId,
                requestedBy,
                reason,
                requestedAt,
                requestedAt.plus(effectiveTtl)
        );
        forcedMatches.put(request.matchId(), request);
        return Optional.of(request);
    }

    public Optional<StreamCvVlmFallbackDecision> consumeForcedDecision(String matchId, String frameId, Instant now) {
        if (!enabled || matchId == null || matchId.isBlank()) {
            return Optional.empty();
        }
        Instant decidedAt = now == null ? Instant.now() : now;
        StreamCvVlmForceRequest request = forcedMatches.remove(matchId.trim());
        if (request == null || request.expiredAt(decidedAt)) {
            return Optional.empty();
        }
        return Optional.of(new StreamCvVlmFallbackDecision(
                request.matchId(),
                frameId,
                StreamCvVlmFallbackTrigger.OPERATOR_FORCE,
                request.reason(),
                request.requestedBy(),
                request.requestedAtUtc(),
                decidedAt,
                request.expiresAtUtc()
        ));
    }

    public Optional<StreamCvVlmFallbackDecision> classicCvExhausted(String matchId,
                                                                    String frameId,
                                                                    int consecutiveFailures,
                                                                    Instant now) {
        if (!enabled || consecutiveFailures < 5 || matchId == null || matchId.isBlank()) {
            return Optional.empty();
        }
        Instant decidedAt = now == null ? Instant.now() : now;
        return Optional.of(new StreamCvVlmFallbackDecision(
                matchId,
                frameId,
                StreamCvVlmFallbackTrigger.CLASSIC_CV_EXHAUSTED,
                "Tier B classic CV failed " + consecutiveFailures + " consecutive frames",
                "system",
                decidedAt,
                decidedAt,
                decidedAt
        ));
    }

    public Optional<StreamCvVlmFallbackDecision> ocrDisagreement(String matchId,
                                                                 String frameId,
                                                                 String reason,
                                                                 Instant now) {
        if (!enabled || matchId == null || matchId.isBlank()) {
            return Optional.empty();
        }
        Instant decidedAt = now == null ? Instant.now() : now;
        return Optional.of(new StreamCvVlmFallbackDecision(
                matchId,
                frameId,
                StreamCvVlmFallbackTrigger.OCR_DISAGREEMENT,
                reason,
                "system",
                decidedAt,
                decidedAt,
                decidedAt
        ));
    }

    public int activeForceCount(Instant now) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        forcedMatches.entrySet().removeIf(entry -> entry.getValue().expiredAt(effectiveNow));
        return forcedMatches.size();
    }

    public StreamCvComponentStatus status() {
        return new StreamCvComponentStatus(
                "StreamCvVlmFallbackHook",
                enabled ? "ready" : "off",
                enabled,
                "Phase 03 VLM fallback decision hook; active operator force requests: " + activeForceCount(Instant.now()) + "."
        );
    }
}
