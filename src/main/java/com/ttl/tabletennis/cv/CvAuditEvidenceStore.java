package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class CvAuditEvidenceStore {

    private static final Logger log = LoggerFactory.getLogger(CvAuditEvidenceStore.class);

    private final CvAuditFrameBuffer buffer;
    private final CvAuditEvidenceUploader uploader;
    private final ObjectMapper objectMapper;

    public CvAuditEvidenceStore(CvAuditFrameBuffer buffer,
                                CvAuditEvidenceUploader uploader,
                                ObjectMapper objectMapper) {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (uploader == null) {
            throw new IllegalArgumentException("uploader must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.buffer = buffer;
        this.uploader = uploader;
        this.objectMapper = objectMapper;
    }

    /**
     * Snapshot the per-match buffer and (if the uploader is enabled) write
     * those JPEGs to MinIO. Returns the canonical ref list; the audit pipeline
     * persists the serialized JSON of those refs into
     * {@code settlement_audit.evidence_refs}.
     */
    public List<String> uploadForContradiction(String matchId, Instant contradictionAtUtc) {
        if (matchId == null || matchId.isBlank()) {
            return List.of();
        }
        if (!uploader.isEnabled()) {
            return List.of();
        }
        List<CvAuditFrameBuffer.AuditFrame> frames = buffer.snapshot(matchId);
        if (frames.isEmpty()) {
            return List.of();
        }
        try {
            return uploader.upload(matchId, contradictionAtUtc, frames);
        } catch (RuntimeException e) {
            log.warn("[cv-audit] upload threw for match={}: {}", matchId, e.getMessage());
            return List.of();
        }
    }

    public Optional<String> serializeRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.writeValueAsString(refs));
        } catch (JsonProcessingException e) {
            log.warn("[cv-audit] failed to serialize refs: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public CvAuditFrameBuffer buffer() {
        return buffer;
    }

    public boolean isEnabled() {
        return uploader.isEnabled();
    }
}
