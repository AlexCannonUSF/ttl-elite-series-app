package com.ttl.tabletennis.cv;

import java.time.Instant;
import java.util.List;

public interface CvAuditEvidenceUploader {

    /**
     * Upload audit frames for a contradiction. Returns the canonical
     * {@code s3://} refs in the order uploaded (one per frame).
     * Implementations must be fail-open: an empty list signals "not uploaded".
     */
    List<String> upload(String matchId, Instant contradictionAtUtc, List<CvAuditFrameBuffer.AuditFrame> frames);

    default boolean isEnabled() {
        return true;
    }
}
