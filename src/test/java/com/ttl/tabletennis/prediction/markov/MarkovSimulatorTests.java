package com.ttl.tabletennis.prediction.markov;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkovSimulatorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void simulateReturnsHttpResultAndSendsContract() throws Exception {
        RecordingHttp http = new RecordingHttp(okResponse());
        MarkovSimulator simulator = simulator(true, http, 2);

        MarkovSimulationResult result = simulator.simulate(
                new MarkovSimulationRequest("m1", 0.55, 0.51, 5)
        );

        assertEquals(MarkovSimulationResult.Status.OK, result.status());
        assertEquals("m1", result.matchId());
        assertEquals(0.64, result.pMatchTop(), 1.0e-9);
        assertEquals(0.12, result.p30(), 1.0e-9);
        assertEquals(0.24, result.p31(), 1.0e-9);
        assertEquals(0.28, result.p32(), 1.0e-9);
        assertEquals("closed-form-best-of-5", result.method());
        assertEquals(1, http.calls.size());

        JsonNode request = objectMapper.readTree(http.calls.get(0).body());
        assertEquals("m1", request.path("matchId").asText());
        assertEquals(0.55, request.path("pPointTopOnServe").asDouble(), 1.0e-9);
        assertEquals(0.51, request.path("pPointTopOnReceive").asDouble(), 1.0e-9);
        assertEquals(5, request.path("bestOf").asInt());
    }

    @Test
    void disabledSimulatorUsesLocalFallbackWithoutHttpCall() {
        RecordingHttp http = new RecordingHttp(okResponse());
        MarkovSimulator simulator = simulator(false, http, 2);

        MarkovSimulationResult result = simulator.simulate(
                MarkovSimulationRequest.preMatch("m2", 0.55, 5)
        );

        assertEquals(MarkovSimulationResult.Status.FALLBACK, result.status());
        assertEquals("disabled-by-property", result.reason());
        assertTrue(result.pMatchTop() > 0.5);
        assertTrue(result.expTotalPoints() > 0.0);
        assertTrue(result.medianMatchMinutes() > 0.0);
        assertEquals(result.pMatchTop(), result.p30() + result.p31() + result.p32(), 1.0e-9);
        assertTrue(http.calls.isEmpty());
    }

    @Test
    void retriesTransientServiceFailureBeforeReturningOk() {
        RecordingHttp http = new RecordingHttp(
                new MarkovHttpExchange.Response(503, "{\"detail\":\"warming\"}"),
                okResponse()
        );
        MarkovSimulator simulator = simulator(true, http, 2);

        MarkovSimulationResult result = simulator.simulate(
                MarkovSimulationRequest.preMatch("m3", 0.52, 5)
        );

        assertEquals(MarkovSimulationResult.Status.OK, result.status());
        assertEquals(2, http.calls.size());
    }

    @Test
    void fallsBackAfterHttpFailure() {
        RecordingHttp http = new RecordingHttp(new IOException("dns"));
        MarkovSimulator simulator = simulator(true, http, 1);

        MarkovSimulationResult result = simulator.simulate(
                MarkovSimulationRequest.preMatch("m4", 0.52, 3)
        );

        assertEquals(MarkovSimulationResult.Status.FALLBACK, result.status());
        assertTrue(result.reason().contains("dns"));
        assertFalse(result.pMatchTop() == 0.5);
        assertEquals(1, http.calls.size());
    }

    @Test
    void requestRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarkovSimulationRequest("", 0.5, null, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkovSimulationRequest("m", 1.2, null, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkovSimulationRequest("m", 0.5, -0.1, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkovSimulationRequest("m", 0.5, null, 11));
    }

    private MarkovSimulator simulator(boolean enabled, RecordingHttp http, int maxAttempts) {
        return new MarkovSimulator(
                enabled,
                "http://example/v1/markov",
                Duration.ofMillis(250),
                maxAttempts,
                http,
                objectMapper
        );
    }

    private static MarkovHttpExchange.Response okResponse() {
        return new MarkovHttpExchange.Response(
                200,
                """
                {
                  "matchId": "m1",
                  "pMatchTop": 0.64,
                  "p_3_0": 0.12,
                  "p_3_1": 0.24,
                  "p_3_2": 0.28,
                  "expTotalPoints": 77.5,
                  "medianMatchMinutes": 10.9,
                  "method": "closed-form-best-of-5",
                  "version": "v3.0.0-phase05-markov-1",
                  "note": "ok"
                }
                """
        );
    }

    private static final class RecordingHttp implements MarkovHttpExchange {
        record Call(String url, Map<String, String> headers, String body) { }

        final List<Call> calls = new ArrayList<>();
        private final Queue<Object> outcomes = new ArrayDeque<>();

        RecordingHttp(Object... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public Response post(String url,
                             Map<String, String> headers,
                             String body,
                             Duration timeout) throws IOException {
            calls.add(new Call(url, headers, body));
            Object next = outcomes.isEmpty()
                    ? new MarkovHttpExchange.Response(500, "missing-test-response")
                    : outcomes.remove();
            if (next instanceof IOException ioException) {
                throw ioException;
            }
            return (Response) next;
        }
    }
}
