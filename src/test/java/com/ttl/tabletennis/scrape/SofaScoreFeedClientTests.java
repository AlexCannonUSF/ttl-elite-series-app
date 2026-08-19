package com.ttl.tabletennis.scrape;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SofaScoreFeedClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void pullOnceParsesLiveEventSweepIntoMirrorObservationPayloads() throws Exception {
        String liveJson = """
                {
                  "events": [
                    {
                      "id": 70578852,
                      "startTimestamp": 1776603000,
                      "status": {"type": "inprogress", "description": "2nd set"},
                      "homeTeam": {"name": "Adam Staniczek"},
                      "awayTeam": {"name": "Dariusz Maszczynski"},
                      "tournament": {"name": "TT Cup"},
                      "homeScore": {"current": 2, "point": 9},
                      "awayScore": {"current": 1, "point": 7},
                      "servingSide": "home",
                      "isLive": true
                    }
                  ]
                }
                """;

        String endpointBase = startJsonServer(liveJson, "/api/v1/sport/table-tennis/events/live", null);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        SofaScoreFeedClient client = new SofaScoreFeedClient(
                HttpClient.newHttpClient(),
                ingestionBus,
                new OddsSnapshotFactory(),
                true,
                endpointBase + "/api/v1",
                "/sport/table-tennis/events/live",
                "/event/%s",
                "/event/%s/incidents",
                endpointBase + "/table-tennis",
                "Mozilla/5.0",
                5000,
                0.78,
                0.90
        );

        List<IngestEvent<MirrorObservationPayload>> events = client.pullOnce(
                new FeedClient.PullContext(Instant.parse("2026-04-19T18:45:00Z"), "corr-live", null)
        );

        assertEquals(1, events.size());
        MirrorObservationPayload payload = events.get(0).payload();
        assertEquals(SourceId.SOFASCORE, events.get(0).source());
        assertEquals("score.observed", events.get(0).topic());
        assertEquals("Adam Staniczek", payload.player1Name());
        assertEquals("Dariusz Maszczynski", payload.player2Name());
        assertEquals("TT Cup", payload.competitionName());
        assertEquals("LIVE_MID", payload.phase());
        assertEquals(2, payload.gamesP1());
        assertEquals(1, payload.gamesP2());
        assertEquals(9, payload.pointsP1());
        assertEquals(7, payload.pointsP2());
        assertEquals("P1", payload.server());
        assertFalse(payload.completionSignal());
        assertTrue(payload.trackedEventId().length() == 64);
        verify(ingestionBus).publishAll(org.mockito.ArgumentMatchers.anyList());
        assertEquals(1.0, client.currentHealth().rollingSuccessRate5m(), 0.0001);
    }

    @Test
    void pullOnceUsesTargetedTrackedEventIdAndIncludesIncidentsWhenFixtureContextProvided() throws Exception {
        String eventJson = """
                {
                  "event": {
                    "id": 998877,
                    "status": {"type": "finished"},
                    "homeTeam": {"name": "Marcin Kowalczyk"},
                    "awayTeam": {"name": "Adrian Fabis"},
                    "tournament": {"name": "TT Elite"},
                    "homeScore": {"current": 3},
                    "awayScore": {"current": 1}
                  }
                }
                """;
        String incidentsJson = """
                {
                  "incidents": [
                    {"type": "point", "homeScore": 10, "awayScore": 7}
                  ]
                }
                """;

        AtomicReference<String> requestedPath = new AtomicReference<>("");
        String endpointBase = startJsonServer(eventJson, "/api/v1/event/998877", requestedPath, incidentsJson, "/api/v1/event/998877/incidents");
        SofaScoreFeedClient client = new SofaScoreFeedClient(
                HttpClient.newHttpClient(),
                mock(IngestionBus.class),
                new OddsSnapshotFactory(),
                true,
                endpointBase + "/api/v1",
                "/sport/table-tennis/events/live",
                "/event/%s",
                "/event/%s/incidents",
                endpointBase + "/table-tennis",
                "Mozilla/5.0",
                5000,
                0.78,
                0.90
        );

        List<IngestEvent<MirrorObservationPayload>> events = client.pullOnce(
                new FeedClient.PullContext(
                        Instant.parse("2026-04-19T18:45:00Z"),
                        "corr-targeted",
                        java.util.Map.of(
                                "fixtureId", "998877",
                                "trackedEventId", "tracked-locked-123"
                        )
                )
        );

        assertEquals(1, events.size());
        MirrorObservationPayload payload = events.get(0).payload();
        assertEquals("tracked-locked-123", payload.trackedEventId());
        assertTrue(payload.completionSignal());
        assertEquals("FINISHED", payload.phase());
        assertTrue(payload.payloadJson().contains("\"incidents\""));
        assertTrue(requestedPath.get().contains("/api/v1/event/998877/incidents"));
    }

    @Test
    void disabledClientReturnsNoEventsAndKeepsIdleHealth() {
        SofaScoreFeedClient client = new SofaScoreFeedClient(
                HttpClient.newHttpClient(),
                mock(IngestionBus.class),
                new OddsSnapshotFactory(),
                false,
                "https://www.sofascore.com/api/v1",
                "/sport/table-tennis/events/live",
                "/event/%s",
                "/event/%s/incidents",
                "https://www.sofascore.com/table-tennis",
                "Mozilla/5.0",
                5000,
                0.78,
                0.90
        );

        assertTrue(client.pullOnce(FeedClient.PullContext.now("corr-disabled")).isEmpty());
        assertEquals(SourceId.SOFASCORE, client.currentHealth().source());
        assertEquals(1.0, client.currentHealth().rollingSuccessRate5m(), 0.0001);
    }

    private String startJsonServer(String primaryBody,
                                   String primaryPath,
                                   AtomicReference<String> requestedPath) throws IOException {
        return startJsonServer(primaryBody, primaryPath, requestedPath, null, null);
    }

    private String startJsonServer(String primaryBody,
                                   String primaryPath,
                                   AtomicReference<String> requestedPath,
                                   String secondaryBody,
                                   String secondaryPath) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(primaryPath, exchange -> respondJson(exchange, primaryBody, requestedPath));
        if (secondaryBody != null && secondaryPath != null) {
            server.createContext(secondaryPath, exchange -> respondJson(exchange, secondaryBody, requestedPath));
        }
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respondJson(com.sun.net.httpserver.HttpExchange exchange,
                             String body,
                             AtomicReference<String> requestedPath) throws IOException {
        if (requestedPath != null) {
            requestedPath.set(exchange.getRequestURI().getPath());
        }
        byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }
}
