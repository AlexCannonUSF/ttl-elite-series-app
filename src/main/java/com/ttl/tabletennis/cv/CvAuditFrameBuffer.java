package com.ttl.tabletennis.cv;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling in-memory buffer of the last N JPEG frames per match. The Stream-CV
 * worker pipeline pushes frames here; on a contradiction the audit pipeline
 * reads the snapshot and uploads it to {@code ttl-cv-audit}.
 */
public class CvAuditFrameBuffer {

    public static final int DEFAULT_MAX_FRAMES_PER_MATCH = 10;

    private final int maxFramesPerMatch;
    private final Map<String, Deque<AuditFrame>> framesByMatch = new ConcurrentHashMap<>();

    public CvAuditFrameBuffer() {
        this(DEFAULT_MAX_FRAMES_PER_MATCH);
    }

    public CvAuditFrameBuffer(int maxFramesPerMatch) {
        if (maxFramesPerMatch <= 0) {
            throw new IllegalArgumentException("maxFramesPerMatch must be positive");
        }
        this.maxFramesPerMatch = maxFramesPerMatch;
    }

    public void push(String matchId, Instant capturedAtUtc, byte[] jpegBytes) {
        if (matchId == null || matchId.isBlank() || jpegBytes == null || jpegBytes.length == 0) {
            return;
        }
        AuditFrame frame = new AuditFrame(
                capturedAtUtc == null ? Instant.now() : capturedAtUtc,
                jpegBytes.clone()
        );
        Deque<AuditFrame> deque = framesByMatch.computeIfAbsent(matchId.trim(), key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(frame);
            while (deque.size() > maxFramesPerMatch) {
                deque.removeFirst();
            }
        }
    }

    public List<AuditFrame> snapshot(String matchId) {
        if (matchId == null || matchId.isBlank()) {
            return List.of();
        }
        Deque<AuditFrame> deque = framesByMatch.get(matchId.trim());
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return List.copyOf(new ArrayList<>(deque));
        }
    }

    public void clear(String matchId) {
        if (matchId == null || matchId.isBlank()) {
            return;
        }
        framesByMatch.remove(matchId.trim());
    }

    public int maxFramesPerMatch() {
        return maxFramesPerMatch;
    }

    public int trackedMatches() {
        return framesByMatch.size();
    }

    public record AuditFrame(Instant capturedAtUtc, byte[] jpegBytes) { }
}
