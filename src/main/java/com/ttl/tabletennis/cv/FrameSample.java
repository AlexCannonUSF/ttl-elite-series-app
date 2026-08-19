package com.ttl.tabletennis.cv;

import java.time.Instant;
import java.util.Arrays;

public record FrameSample(String matchId,
                          String frameId,
                          long sequence,
                          Instant capturedAtUtc,
                          byte[] jpegBytes,
                          int sourceByteCount) {

    public FrameSample {
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
        jpegBytes = jpegBytes == null ? new byte[0] : Arrays.copyOf(jpegBytes, jpegBytes.length);
        sourceByteCount = Math.max(sourceByteCount, jpegBytes.length);
    }

    @Override
    public byte[] jpegBytes() {
        return Arrays.copyOf(jpegBytes, jpegBytes.length);
    }
}
