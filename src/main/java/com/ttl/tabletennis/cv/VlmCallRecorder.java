package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.domain.StreamVlmCall;
import com.ttl.tabletennis.repository.StreamVlmCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

public class VlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(VlmCallRecorder.class);

    private final Optional<StreamVlmCallRepository> repository;

    public VlmCallRecorder(Optional<StreamVlmCallRepository> repository) {
        this.repository = repository == null ? Optional.empty() : repository;
    }

    public void record(String workerId,
                       String matchId,
                       String engineId,
                       VlmRequest request,
                       VlmScoreReadingResult result,
                       Instant now) {
        if (repository.isEmpty()) {
            return;
        }
        StreamVlmCall row = new StreamVlmCall();
        row.setCallId(UUID.randomUUID().toString());
        row.setMatchId(safeText(matchId, request == null ? null : request.matchId(), "unknown"));
        row.setWorkerId(safeText(workerId, "unknown"));
        row.setFrameId(request == null ? "" : request.frameId());
        row.setModel(safeText(engineId, "unknown"));
        row.setDecision(result.status().name());
        row.setTokensIn(result.tokensIn());
        row.setTokensOut(result.tokensOut());
        row.setLatencyMs(result.latency() == null ? 0L : result.latency().toMillis());
        row.setCostUsdEst(BigDecimal.valueOf(Math.max(0.0, result.costEstimateUsd()))
                .setScale(6, RoundingMode.HALF_UP));
        row.setResponseValid(result.status() == VlmScoreReadingResult.Status.OK);
        row.setErrorReason(result.error());
        row.setCalledAtUtc(LocalDateTime.ofInstant(now == null ? Instant.now() : now, ZoneOffset.UTC));

        try {
            repository.get().save(row);
        } catch (RuntimeException e) {
            log.warn("[vlm-recorder] failed to persist stream_vlm_call for worker={} model={}: {}",
                    workerId, engineId, e.getMessage());
        }
    }

    private static String safeText(String first, String... fallbacks) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (fallbacks != null) {
            for (String fallback : fallbacks) {
                if (fallback != null && !fallback.isBlank()) {
                    return fallback.trim();
                }
            }
        }
        return "";
    }
}
