package com.ttl.tabletennis.cv;

import java.time.Instant;

public record StreamScoreFrame(String matchId,
                               String frameId,
                               long sequence,
                               Instant capturedAtUtc,
                               ScoreTuple score,
                               double confidence,
                               String templateId,
                               String reader) {

    public StreamScoreFrame {
        if (matchId == null || matchId.trim().isEmpty()) {
            throw new IllegalArgumentException("matchId must not be blank");
        }
        matchId = matchId.trim();
        if (frameId == null || frameId.trim().isEmpty()) {
            throw new IllegalArgumentException("frameId must not be blank");
        }
        frameId = frameId.trim();
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        capturedAtUtc = capturedAtUtc == null ? Instant.now() : capturedAtUtc;
        if (score == null) {
            throw new IllegalArgumentException("score must not be null");
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        templateId = templateId == null ? "" : templateId.trim();
        reader = reader == null || reader.isBlank() ? "unknown" : reader.trim();
    }
}
