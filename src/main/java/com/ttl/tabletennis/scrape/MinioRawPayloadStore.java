package com.ttl.tabletennis.scrape;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

public class MinioRawPayloadStore implements RawPayloadStore {

    static final int MAX_CORRELATION_ID_LENGTH = 80;
    static final String CONTENT_TYPE = "application/json";
    static final String CONTENT_ENCODING = "gzip";
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final MinioClient client;
    private final String bucket;

    public MinioRawPayloadStore(MinioClient client, String bucket) {
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
    public String put(SourceId source, String correlationId, Instant observedAt, byte[] uncompressedBody) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        if (uncompressedBody == null) {
            throw new IllegalArgumentException("uncompressedBody must not be null");
        }

        String date = DATE_FORMATTER.format(observedAt == null ? Instant.now() : observedAt);
        String safeCorrelationId = sanitizeCorrelationId(correlationId);
        String objectKey = objectKey(source, date, safeCorrelationId);
        byte[] gzipped = gzip(uncompressedBody);

        try (ByteArrayInputStream stream = new ByteArrayInputStream(gzipped)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, gzipped.length, -1)
                    .contentType(CONTENT_TYPE)
                    .headers(java.util.Map.of("Content-Encoding", CONTENT_ENCODING))
                    .build());
        } catch (Exception e) {
            throw new RawPayloadStoreException(
                    "Failed to upload raw payload to bucket " + bucket + " key " + objectKey, e);
        }

        return refFor(bucket, source, date, safeCorrelationId);
    }

    static String objectKey(SourceId source, String date, String safeCorrelationId) {
        return source.id() + "/" + date + "/" + safeCorrelationId + ".json.gz";
    }

    static String refFor(String bucket, SourceId source, String date, String safeCorrelationId) {
        return "s3://" + bucket + "/" + source.id() + "/" + date + "/" + safeCorrelationId;
    }

    static String sanitizeCorrelationId(String correlationId) {
        String trimmed = correlationId.trim();
        String safe = trimmed.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.length() > MAX_CORRELATION_ID_LENGTH) {
            safe = safe.substring(0, MAX_CORRELATION_ID_LENGTH);
        }
        return safe.isBlank() ? "unknown" : safe.toLowerCase(Locale.ROOT);
    }

    static byte[] gzip(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, body.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(body);
        } catch (IOException e) {
            throw new RawPayloadStoreException("Failed to gzip raw payload", e);
        }
        return out.toByteArray();
    }

    String bucket() {
        return bucket;
    }
}
