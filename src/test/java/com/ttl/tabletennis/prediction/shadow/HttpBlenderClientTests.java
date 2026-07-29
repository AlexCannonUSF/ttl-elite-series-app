package com.ttl.tabletennis.prediction.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpBlenderClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void scoreReturnsOkOnHappyPath() throws Exception {
        RecordingHttp http = new RecordingHttp(new BlenderHttpExchange.Response(
                200,
                """
                {
                  "matchId": "m1",
                  "modelVersion": "v3.0.0",
                  "calibratorVersion": "platt+iso-v3.0.0",
                  "conformalVersion": "mondrian-split-v3.0.0",
                  "featureSchemaHash": "abc",
                  "pTop": {"value": 0.62, "rawValue": 0.58},
                  "pBot": {"value": 0.38, "rawValue": 0.42},
                  "uncertainty": {"coverage": 0.9, "alpha": 0.1, "label": "CONFIDENT_TOP",
                                  "intervalLow": 0.1, "intervalHigh": 0.9, "groupKey": "5|false|true",
                                  "quantile": 0.9, "method": "mondrian-split-conformal", "version": "v3.0.0"},
                  "computedAtUtc": "2026-05-18T03:04:05.000Z",
                  "latencyMs": 12.5
                }
                """));
        HttpBlenderClient client = new HttpBlenderClient("http://example/v1/blend", http, objectMapper);
        BlenderClient.Result result = client.score(request(), Duration.ofMillis(500));

        assertEquals(BlenderClient.Status.OK, result.status());
        BlenderResponse response = result.response().orElseThrow();
        assertEquals(0.62, response.pTop());
        assertEquals(0.38, response.pBot());
        assertEquals("CONFIDENT_TOP", response.uncertaintyLabel());
        assertEquals(1, http.calls.size());

        JsonNode body = objectMapper.readTree(http.calls.get(0).body());
        assertEquals("m1", body.path("matchId").asText());
        assertEquals("abc", body.path("featureSchemaHash").asText());
    }

    @Test
    void scoreParsesSanityBlockWhenPresent() throws Exception {
        RecordingHttp http = new RecordingHttp(new BlenderHttpExchange.Response(
                200,
                """
                {
                  "matchId": "m1",
                  "modelVersion": "v3.0.0",
                  "calibratorVersion": "platt+iso-v3.0.0",
                  "conformalVersion": "mondrian-split-v3.0.0",
                  "featureSchemaHash": "abc",
                  "pTop": {"value": 0.62, "rawValue": 0.58},
                  "pBot": {"value": 0.38, "rawValue": 0.42},
                  "uncertainty": {"coverage": 0.9, "alpha": 0.1, "label": "CONFIDENT_TOP",
                                  "intervalLow": 0.1, "intervalHigh": 0.9, "groupKey": "5|false|true",
                                  "quantile": 0.9, "method": "mondrian-split-conformal", "version": "v3.0.0"},
                  "computedAtUtc": "2026-05-18T03:04:05.000Z",
                  "latencyMs": 12.5,
                  "sanity": {
                    "variant": "B",
                    "modelVersion": "v3.0.0-variant-b",
                    "calibratorVersion": "platt+iso-v3.0.0",
                    "conformalVersion": "mondrian-split-v3.0.0",
                    "featureSchemaHash": "def",
                    "pTop": {"value": 0.57, "rawValue": 0.55},
                    "pBot": {"value": 0.43, "rawValue": 0.45},
                    "uncertainty": {"coverage": 0.9, "alpha": 0.1, "label": "CONFIDENT_TOP",
                                    "intervalLow": 0.1, "intervalHigh": 0.9, "groupKey": "5|false|true",
                                    "quantile": 0.9, "method": "mondrian-split-conformal", "version": "v3.0.0"},
                    "absoluteDiffPTop": 0.05,
                    "latencyMs": 9.5
                  }
                }
                """));
        HttpBlenderClient client = new HttpBlenderClient("http://example/v1/blend", http, objectMapper);
        BlenderClient.Result result = client.score(request(), Duration.ofMillis(500));

        assertEquals(BlenderClient.Status.OK, result.status());
        BlenderResponse response = result.response().orElseThrow();
        assertTrue(response.sanity().isPresent());
        BlenderResponse.Sanity sanity = response.sanity().get();
        assertEquals("B", sanity.variant());
        assertEquals("v3.0.0-variant-b", sanity.modelVersion());
        assertEquals("def", sanity.featureSchemaHash());
        assertEquals(0.57, sanity.pTop());
        assertEquals(0.05, sanity.absoluteDiffPTop());
    }

    @Test
    void scoreOmitsSanityBlockWhenAbsent() {
        RecordingHttp http = new RecordingHttp(new BlenderHttpExchange.Response(
                200,
                """
                {
                  "matchId": "m1",
                  "modelVersion": "v3.0.0",
                  "calibratorVersion": "platt+iso-v3.0.0",
                  "conformalVersion": "mondrian-split-v3.0.0",
                  "featureSchemaHash": "abc",
                  "pTop": {"value": 0.62, "rawValue": 0.58},
                  "pBot": {"value": 0.38, "rawValue": 0.42},
                  "uncertainty": {"coverage": 0.9, "alpha": 0.1, "label": "CONFIDENT_TOP",
                                  "intervalLow": 0.1, "intervalHigh": 0.9, "groupKey": "5|false|true",
                                  "quantile": 0.9, "method": "mondrian-split-conformal", "version": "v3.0.0"},
                  "computedAtUtc": "2026-05-18T03:04:05.000Z",
                  "latencyMs": 12.5
                }
                """));
        HttpBlenderClient client = new HttpBlenderClient("http://example/v1/blend", http, objectMapper);
        BlenderClient.Result result = client.score(request(), Duration.ofMillis(500));
        assertEquals(BlenderClient.Status.OK, result.status());
        assertTrue(result.response().orElseThrow().sanity().isEmpty());
    }

    @Test
    void status409ReturnsSchemaHashMismatch() {
        RecordingHttp http = new RecordingHttp(new BlenderHttpExchange.Response(409, "{\"detail\":\"hash mismatch\"}"));
        HttpBlenderClient client = new HttpBlenderClient("http://example/v1/blend", http, objectMapper);
        BlenderClient.Result result = client.score(request(), Duration.ofMillis(500));
        assertEquals(BlenderClient.Status.SCHEMA_HASH_MISMATCH, result.status());
        assertTrue(result.reason().contains("409"));
    }

    @Test
    void status503ReturnsServiceUnavailable() {
        RecordingHttp http = new RecordingHttp(new BlenderHttpExchange.Response(503, "{\"detail\":\"not ready\"}"));
        HttpBlenderClient client = new HttpBlenderClient("http://example/v1/blend", http, objectMapper);
        BlenderClient.Result result = client.score(request(), Duration.ofMillis(500));
        assertEquals(BlenderClient.Status.SERVICE_UNAVAILABLE, result.status());
    }

    @Test
    void otherNon2xxReturnsError() {
        RecordingHttp http = new RecordingHttp(new BlenderHttpExchange.Response(500, "boom"));
        HttpBlenderClient client = new HttpBlenderClient("http://example/v1/blend", http, objectMapper);
        BlenderClient.Result result = client.score(request(), Duration.ofMillis(500));
        assertEquals(BlenderClient.Status.ERROR, result.status());
        assertTrue(result.reason().contains("500"));
    }

    @Test
    void ioExceptionMapsToServiceUnavailable() {
        BlenderHttpExchange exploding = (url, headers, body, timeout) -> { throw new IOException("dns"); };
        HttpBlenderClient client = new HttpBlenderClient("http://example/v1/blend", exploding, objectMapper);
        BlenderClient.Result result = client.score(request(), Duration.ofMillis(500));
        assertEquals(BlenderClient.Status.SERVICE_UNAVAILABLE, result.status());
        assertTrue(result.reason().contains("dns"));
    }

    @Test
    void unparseableResponseReturnsError() {
        RecordingHttp http = new RecordingHttp(new BlenderHttpExchange.Response(200, "not json"));
        HttpBlenderClient client = new HttpBlenderClient("http://example/v1/blend", http, objectMapper);
        BlenderClient.Result result = client.score(request(), Duration.ofMillis(500));
        assertEquals(BlenderClient.Status.ERROR, result.status());
        assertTrue(result.reason().contains("parse"));
    }

    @Test
    void constructorRejectsBlankOrNullDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpBlenderClient("", new RecordingHttp(), objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpBlenderClient("http://x", null, objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpBlenderClient("http://x", new RecordingHttp(), null));
    }

    private static BlenderRequest request() {
        return new BlenderRequest(
                "m1",
                "abc",
                false,
                false,
                Map.of("match.best_of", 5)
        );
    }

    private static final class RecordingHttp implements BlenderHttpExchange {
        record Call(String url, Map<String, String> headers, String body) { }

        final List<Call> calls = new ArrayList<>();
        private final Response next;

        RecordingHttp() {
            this(new Response(200, "{}"));
        }

        RecordingHttp(Response next) {
            this.next = next;
        }

        @Override
        public Response post(String url, Map<String, String> headers, String body, Duration timeout) {
            calls.add(new Call(url, headers, body));
            return next;
        }
    }
}
