package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CvAuditConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CvAuditConfiguration.class);

    @Bean
    public CvAuditFrameBuffer cvAuditFrameBuffer(
            @Value("${ttl.cv-audit.maxFramesPerMatch:" + CvAuditFrameBuffer.DEFAULT_MAX_FRAMES_PER_MATCH + "}") int maxFramesPerMatch) {
        return new CvAuditFrameBuffer(maxFramesPerMatch);
    }

    @Bean
    public CvAuditEvidenceUploader cvAuditEvidenceUploader(
            @Value("${ttl.cv-audit.enabled:false}") boolean enabled,
            @Value("${ttl.cv-audit.endpoint:${ttl.ingestion.raw-store.endpoint:http://localhost:9000}}") String endpoint,
            @Value("${ttl.cv-audit.bucket:ttl-cv-audit}") String bucket,
            @Value("${ttl.cv-audit.access-key:${ttl.ingestion.raw-store.access-key:ttl-minio}}") String accessKey,
            @Value("${ttl.cv-audit.secret-key:${ttl.ingestion.raw-store.secret-key:ttl-minio-dev}}") String secretKey,
            @Value("${ttl.cv-audit.region:${ttl.ingestion.raw-store.region:us-east-1}}") String region) {
        if (!enabled) {
            log.info("[cv-audit] disabled; contradiction evidence refs will not be written to MinIO");
            return new NoopCvAuditEvidenceUploader();
        }
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .region(region)
                    .build();
            log.info("[cv-audit] MinIO uploader active; endpoint={} bucket={}", endpoint, bucket);
            return new MinioCvAuditEvidenceUploader(client, bucket);
        } catch (RuntimeException e) {
            log.warn("[cv-audit] failed to init MinIO at {}; falling back to no-op: {}", endpoint, e.getMessage());
            return new NoopCvAuditEvidenceUploader();
        }
    }

    @Bean
    public CvAuditEvidenceStore cvAuditEvidenceStore(CvAuditFrameBuffer buffer,
                                                     CvAuditEvidenceUploader uploader,
                                                     ObjectMapper objectMapper) {
        return new CvAuditEvidenceStore(buffer, uploader, objectMapper);
    }
}
