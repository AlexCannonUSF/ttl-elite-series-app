package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.config.FeatureFlagCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class IngestionBusConfigurationTests {

    @TempDir
    Path tempDir;

    @Test
    void redisStreamsOffUsesApplicationEventBus() throws IOException {
        IngestionBus bus = buildBus("off", Optional.of(mock(StringRedisTemplate.class)));

        assertInstanceOf(ApplicationEventIngestionBus.class, bus);
    }

    @Test
    void redisStreamsShadowDualPublishesWithApplicationEventPrimary() throws IOException {
        IngestionBus bus = buildBus("shadow", Optional.of(mock(StringRedisTemplate.class)));

        assertInstanceOf(ShadowRedisIngestionBus.class, bus);
    }

    @Test
    void redisStreamsOnUsesRedisBusWhenRedisTemplateAvailable() throws IOException {
        IngestionBus bus = buildBus("on", Optional.of(mock(StringRedisTemplate.class)));

        assertInstanceOf(RedisStreamsBus.class, bus);
    }

    @Test
    void redisStreamsOnFallsBackWhenRedisTemplateMissing() throws IOException {
        IngestionBus bus = buildBus("on", Optional.empty());

        assertInstanceOf(ApplicationEventIngestionBus.class, bus);
    }

    @Test
    void rawPayloadStoreEnabledWrapsBusWithStoringDecorator() throws IOException {
        IngestionBus bus = buildBus("off", Optional.of(mock(StringRedisTemplate.class)), new EnabledRawPayloadStore());

        assertInstanceOf(RawPayloadStoringIngestionBus.class, bus);
    }

    @Test
    void rawPayloadStoreDisabledDoesNotWrapBus() throws IOException {
        IngestionBus bus = buildBus("off", Optional.of(mock(StringRedisTemplate.class)));

        assertInstanceOf(ApplicationEventIngestionBus.class, bus);
    }

    private IngestionBus buildBus(String redisStreamsState,
                                  Optional<StringRedisTemplate> redisTemplate) throws IOException {
        return buildBus(redisStreamsState, redisTemplate, new NoopRawPayloadStore());
    }

    private IngestionBus buildBus(String redisStreamsState,
                                  Optional<StringRedisTemplate> redisTemplate,
                                  RawPayloadStore rawPayloadStore) throws IOException {
        Path catalogPath = tempDir.resolve("features-" + redisStreamsState + ".yaml");
        Files.writeString(catalogPath, """
                schema_version: 1
                features:
                  "features.redis-streams":
                    owner: "Alex"
                    expires_on: "2026-07-15"
                    state: "%s"
                    description: "Redis Streams bus mode."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "on"
                """.formatted(redisStreamsState));

        FeatureFlagCatalog featureFlagCatalog = new FeatureFlagCatalog(catalogPath.toString());
        return new IngestionBusConfiguration().ingestionBus(
                featureFlagCatalog,
                mock(ApplicationEventPublisher.class),
                new IngestMetricsRecorder(new SimpleMeterRegistry()),
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                rawPayloadStore,
                "ttl",
                10_000L
        );
    }

    private static final class EnabledRawPayloadStore implements RawPayloadStore {
        @Override
        public String put(SourceId source, String correlationId, java.time.Instant observedAt, byte[] body) {
            return "s3://ttl-raw/" + source.id() + "/2026-05-17/" + correlationId;
        }
    }
}
