package com.ttl.tabletennis.scrape;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RawPayloadStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RawPayloadStoreConfiguration.class);

    @Bean
    public RawPayloadStore rawPayloadStore(
            @Value("${ttl.ingestion.raw-store.enabled:false}") boolean enabled,
            @Value("${ttl.ingestion.raw-store.endpoint:http://localhost:9000}") String endpoint,
            @Value("${ttl.ingestion.raw-store.bucket:ttl-raw}") String bucket,
            @Value("${ttl.ingestion.raw-store.access-key:ttl-minio}") String accessKey,
            @Value("${ttl.ingestion.raw-store.secret-key:ttl-minio-dev}") String secretKey,
            @Value("${ttl.ingestion.raw-store.region:us-east-1}") String region) {
        if (!enabled) {
            log.info("[raw-store] disabled; events will be forwarded without rawPayloadRef writes");
            return new NoopRawPayloadStore();
        }
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .region(region)
                    .build();
            log.info("[raw-store] MinIO writer active; endpoint={} bucket={}", endpoint, bucket);
            return new MinioRawPayloadStore(client, bucket);
        } catch (RuntimeException e) {
            log.warn("[raw-store] failed to initialize MinIO client at {}; falling back to no-op writer: {}",
                    endpoint, e.getMessage());
            return new NoopRawPayloadStore();
        }
    }
}
