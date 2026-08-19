package com.ttl.tabletennis.cv;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MinioCvAuditEvidenceUploader implements CvAuditEvidenceUploader {

    static final String CONTENT_TYPE = "image/jpeg";
    static final int MAX_MATCH_ID_LENGTH = 80;
    private static final DateTimeFormatter MINUTE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm").withZone(ZoneOffset.UTC);

    private static final Logger log = LoggerFactory.getLogger(MinioCvAuditEvidenceUploader.class);

    private final MinioClient client;
    private final String bucket;

    public MinioCvAuditEvidenceUploader(MinioClient client, String bucket) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        this.client = client;
        this.bucket = bucket.trim();
    }

    @Override
    public List<String> upload(String matchId,
                               Instant contradictionAtUtc,
                               List<CvAuditFrameBuffer.AuditFrame> frames) {
        if (matchId == null || matchId.isBlank() || frames == null || frames.isEmpty()) {
            return List.of();
        }
        String safeMatchId = sanitizeMatchId(matchId);
        String minute = MINUTE_FORMATTER.format(contradictionAtUtc == null ? Instant.now() : contradictionAtUtc);

        List<String> refs = new ArrayList<>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            CvAuditFrameBuffer.AuditFrame frame = frames.get(index);
            if (frame == null || frame.jpegBytes() == null || frame.jpegBytes().length == 0) {
                continue;
            }
            String objectKey = objectKey(safeMatchId, minute, index);
            try (ByteArrayInputStream stream = new ByteArrayInputStream(frame.jpegBytes())) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(stream, frame.jpegBytes().length, -1)
                        .contentType(CONTENT_TYPE)
                        .build());
                refs.add("s3://" + bucket + "/" + objectKey);
            } catch (Exception e) {
                log.warn("[cv-audit] upload failed match={} key={}: {}", safeMatchId, objectKey, e.getMessage());
            }
        }
        return List.copyOf(refs);
    }

    static String objectKey(String matchId, String minute, int index) {
        return matchId + "/" + minute + "/" + String.format(Locale.ROOT, "%02d.jpg", index);
    }

    static String sanitizeMatchId(String matchId) {
        String trimmed = matchId.trim();
        String safe = trimmed.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.length() > MAX_MATCH_ID_LENGTH) {
            safe = safe.substring(0, MAX_MATCH_ID_LENGTH);
        }
        return safe.isBlank() ? "unknown" : safe;
    }

    String bucket() {
        return bucket;
    }
}
