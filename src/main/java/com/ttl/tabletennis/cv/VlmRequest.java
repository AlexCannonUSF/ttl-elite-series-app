package com.ttl.tabletennis.cv;

import java.time.Duration;

public record VlmRequest(byte[] imageBytes,
                         String imageContentType,
                         String matchId,
                         String frameId,
                         Duration timeout) {

    public VlmRequest {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes must not be null or empty");
        }
        imageContentType = imageContentType == null || imageContentType.isBlank()
                ? "image/jpeg"
                : imageContentType.trim();
        matchId = matchId == null ? "" : matchId.trim();
        frameId = frameId == null ? "" : frameId.trim();
        timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
    }
}
