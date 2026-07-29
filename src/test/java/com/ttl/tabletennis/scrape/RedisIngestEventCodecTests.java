package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisIngestEventCodecTests {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RedisIngestEventCodec codec = new RedisIngestEventCodec(objectMapper);

    @Test
    void decodeReconstructsAllowListedTypedPayload() throws Exception {
        MirrorObservationPayload payload = new MirrorObservationPayload(
                "tracked-1",
                "mirror-1",
                "Alpha",
                "Beta",
                "TT Elite",
                "LIVE",
                2,
                1,
                8,
                6,
                "Alpha",
                false,
                "{\"score\":\"8-6\"}"
        );
        Map<String, String> fields = validFields();
        fields.put("payload_type", MirrorObservationPayload.class.getName());
        fields.put("payload_json", objectMapper.writeValueAsString(payload));

        IngestEvent<?> event = codec.decode(fields);

        assertEquals(SourceId.SOFASCORE, event.source());
        assertEquals("score.observed", event.topic());
        assertEquals(Instant.parse("2026-07-29T12:00:00Z"), event.observedAt());
        assertEquals(0.93, event.confidence());
        assertEquals("corr-1", event.correlationId());
        assertEquals("raw://mirror-1", event.rawPayloadRef());
        MirrorObservationPayload decoded = assertInstanceOf(
                MirrorObservationPayload.class,
                event.payload()
        );
        assertEquals("tracked-1", decoded.trackedEventId());
        assertEquals(8, decoded.pointsP1());
    }

    @Test
    void decodeRejectsPayloadTypesOutsideAllowList() {
        Map<String, String> fields = validFields();
        fields.put("payload_type", "java.lang.Runtime");
        fields.put("payload_json", "{}");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> codec.decode(fields)
        );

        assertEquals("Unsupported payload_type: java.lang.Runtime", failure.getMessage());
    }

    private static Map<String, String> validFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("source_id", "SOFASCORE");
        fields.put("topic", "score.observed");
        fields.put("observed_at", "2026-07-29T12:00:00Z");
        fields.put("confidence", "0.93");
        fields.put("correlation_id", "corr-1");
        fields.put("raw_payload_ref", "raw://mirror-1");
        return fields;
    }
}
