package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.config.FeatureFlagCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

@Configuration
public class IngestionBusConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IngestionBusConfiguration.class);

    @Bean
    public IngestionBus ingestionBus(FeatureFlagCatalog featureFlagCatalog,
                                     ApplicationEventPublisher applicationEventPublisher,
                                     IngestMetricsRecorder ingestMetricsRecorder,
                                     Optional<StringRedisTemplate> redisTemplate,
                                     ObjectMapper objectMapper,
                                     RawPayloadStore rawPayloadStore,
                                     @Value("${ttl.ingestion.redis.streamPrefix:ttl}") String redisStreamPrefix,
                                     @Value("${ttl.ingestion.redis.maxStreamLength:10000}") long redisMaxStreamLength) {
        IngestionBus delivery = selectDeliveryBus(
                featureFlagCatalog,
                applicationEventPublisher,
                ingestMetricsRecorder,
                redisTemplate,
                objectMapper,
                redisStreamPrefix,
                redisMaxStreamLength
        );
        if (rawPayloadStore != null && rawPayloadStore.isEnabled()) {
            log.info("[ingestion-bus] wrapping bus with raw-payload-store writer");
            return new RawPayloadStoringIngestionBus(delivery, rawPayloadStore, objectMapper);
        }
        return delivery;
    }

    IngestionBus selectDeliveryBus(FeatureFlagCatalog featureFlagCatalog,
                                   ApplicationEventPublisher applicationEventPublisher,
                                   IngestMetricsRecorder ingestMetricsRecorder,
                                   Optional<StringRedisTemplate> redisTemplate,
                                   ObjectMapper objectMapper,
                                   String redisStreamPrefix,
                                   long redisMaxStreamLength) {
        ApplicationEventIngestionBus applicationBus = new ApplicationEventIngestionBus(
                applicationEventPublisher,
                ingestMetricsRecorder
        );
        String redisStreamsState = featureFlagCatalog.stateOf(FeatureFlagCatalog.REDIS_STREAMS_FLAG);

        if (!"shadow".equals(redisStreamsState) && !"on".equals(redisStreamsState)) {
            log.info("[ingestion-bus] using application-event bus; {}={}",
                    FeatureFlagCatalog.REDIS_STREAMS_FLAG,
                    redisStreamsState);
            return applicationBus;
        }

        if (redisTemplate.isEmpty()) {
            log.warn("[ingestion-bus] {}={} but no Redis template is available; falling back to application-event bus",
                    FeatureFlagCatalog.REDIS_STREAMS_FLAG,
                    redisStreamsState);
            return applicationBus;
        }

        RedisStreamsBus redisBus = new RedisStreamsBus(
                redisTemplate.get(),
                objectMapper,
                ingestMetricsRecorder,
                redisStreamPrefix,
                "on".equals(redisStreamsState),
                redisMaxStreamLength
        );

        if ("shadow".equals(redisStreamsState)) {
            log.info("[ingestion-bus] using shadow Redis Streams bus with application-event primary path");
            return new ShadowRedisIngestionBus(applicationBus, redisBus);
        }

        log.info("[ingestion-bus] using Redis Streams bus");
        return redisBus;
    }
}
