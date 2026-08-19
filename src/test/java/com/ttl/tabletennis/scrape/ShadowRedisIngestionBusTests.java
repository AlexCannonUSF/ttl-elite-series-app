package com.ttl.tabletennis.scrape;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ShadowRedisIngestionBusTests {

    @Test
    void publishKeepsPrimaryPathEvenWhenRedisShadowFails() {
        IngestionBus primaryBus = mock(IngestionBus.class);
        IngestionBus redisBus = mock(IngestionBus.class);
        ShadowRedisIngestionBus bus = new ShadowRedisIngestionBus(primaryBus, redisBus);
        IngestEvent<String> event = new IngestEvent<>(
                SourceId.INTERNAL_DB,
                "feed.health",
                Instant.parse("2026-05-18T01:00:00Z"),
                1.0,
                "corr-shadow",
                "",
                "payload"
        );
        doThrow(new IllegalStateException("redis down")).when(redisBus).publish(event);

        bus.publish(event);

        verify(primaryBus).publish(event);
        verify(redisBus).publish(event);
    }
}
