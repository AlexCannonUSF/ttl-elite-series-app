package com.ttl.tabletennis.scrape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShadowRedisIngestionBus implements IngestionBus {

    private static final Logger log = LoggerFactory.getLogger(ShadowRedisIngestionBus.class);

    private final IngestionBus primaryBus;
    private final IngestionBus shadowBus;

    public ShadowRedisIngestionBus(IngestionBus primaryBus, IngestionBus shadowBus) {
        if (primaryBus == null) {
            throw new IllegalArgumentException("primaryBus must not be null");
        }
        if (shadowBus == null) {
            throw new IllegalArgumentException("shadowBus must not be null");
        }
        this.primaryBus = primaryBus;
        this.shadowBus = shadowBus;
    }

    @Override
    public void publish(IngestEvent<?> event) {
        if (event == null) {
            return;
        }
        primaryBus.publish(event);
        try {
            shadowBus.publish(event);
        } catch (RuntimeException e) {
            log.warn("[ingestion-bus] Redis shadow publish failed for topic={} source={} correlationId={}: {}",
                    event.topic(),
                    event.source().id(),
                    event.correlationId(),
                    e.getMessage());
        }
    }
}
