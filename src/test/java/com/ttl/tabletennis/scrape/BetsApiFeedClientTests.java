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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BetsApiFeedClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void pullOnceParsesLiveEventSweepAndAddsToken() throws Exception {
        String liveJson = """
                {
                  "success": 1,
                  "results": [
                    {
                      "id": "bets-991",
                      "time_status": "1",
                      "time": 1776603000,
                      "league": {"name": "TT Cup"},
                      "home": {"name": "Adam Staniczek"},
                      "away": {"name": "Dariusz Maszczynski"},
                      "ss": "2-1",
                      "points": "9-7",
                      "servingSide": "home"
                    }
                  ]
                }
                """;
        AtomicReference<String> requestedQuery = new AtomicReference<>("");
        String baseUrl = startJsonServer(liveJson, "/v2/events/inplay", requestedQuery);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        BetsApiFeedClient client = new BetsApiFeedClient(
                HttpClient.newHttpClient(),
                ingestionBus,
                new OddsSnapshotFactory(),
                true,
                baseUrl,
                "/v2/events/inplay?sport_id=92",
                "/v1/event/view?event_id=%s",
                "secret-token",
                "",
                "TTLEliteSeries/Test",
                5000,
                0.84,
                0.93
        );

        List<IngestEvent<MirrorObservationPayload>> events = client.pullOnce(
                new FeedClient.PullContext(Instant.parse("2026-04-19T18:45:00Z"), "corr-bets", null)
        );

        assertEquals(1, events.size());
        IngestEvent<MirrorObservationPayload> event = events.get(0);
        MirrorObservationPayload payload = event.payload();
        assertEquals(SourceId.BETSAPI, event.source());
        assertEquals("score.observed", event.topic());
        assertEquals(0.84, event.confidence(), 0.0001);
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
        assertTrue(requestedQuery.get().contains("sport_id=92"));
        assertTrue(requestedQuery.get().contains("token=secret-token"));
        verify(ingestionBus).publishAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void targetedPullKeepsLockedTrackedEventIdAndCompletionConfidence() throws Exception {
        String eventJson = """
                {
                  "results": [
                    {
                      "id": "bets-992",
                      "time_status": "3",
                      "league": {"name": "TT Elite"},
                      "home": {"name": "Marcin Kowalczyk"},
                      "away": {"name": "Adrian Fabis"},
                      "ss": "3-0"
                    }
                  ]
                }
                """;
        AtomicReference<String> requestedQuery = new AtomicReference<>("");
        String baseUrl = startJsonServer(eventJson, "/v1/event/view", requestedQuery);
        BetsApiFeedClient client = new BetsApiFeedClient(
                HttpClient.newHttpClient(),
                mock(IngestionBus.class),
                new OddsSnapshotFactory(),
                true,
                baseUrl,
                "/v2/events/inplay?sport_id=92",
                "/v1/event/view?event_id=%s",
                "secret-token",
                "",
                "TTLEliteSeries/Test",
                5000,
                0.84,
                0.93
        );

        List<IngestEvent<MirrorObservationPayload>> events = client.pullOnce(
                new FeedClient.PullContext(
                        Instant.parse("2026-04-19T18:45:00Z"),
                        "corr-targeted",
                        Map.of(
                                "fixtureId", "bets-992",
                                "trackedEventId", "tracked-bets-locked"
                        )
                )
        );

        assertEquals(1, events.size());
        assertEquals(0.93, events.get(0).confidence(), 0.0001);
        assertEquals("tracked-bets-locked", events.get(0).payload().trackedEventId());
        assertEquals("FINISHED", events.get(0).payload().phase());
        assertTrue(events.get(0).payload().completionSignal());
        assertTrue(requestedQuery.get().contains("event_id=bets-992"));
        assertTrue(requestedQuery.get().contains("token=secret-token"));
    }

    @Test
    void disabledClientReturnsNoEventsAndKeepsIdleHealth() {
        BetsApiFeedClient client = new BetsApiFeedClient(
                HttpClient.newHttpClient(),
                mock(IngestionBus.class),
                new OddsSnapshotFactory(),
                false,
                "https://api.b365api.com",
                "/v2/events/inplay?sport_id=92",
                "/v1/event/view?event_id=%s",
                "",
                "",
                "TTLEliteSeries/Test",
                5000,
                0.84,
                0.93
        );

        assertTrue(client.pullOnce(FeedClient.PullContext.now("corr-disabled")).isEmpty());
        assertEquals(SourceId.BETSAPI, client.currentHealth().source());
        assertEquals(1.0, client.currentHealth().rollingSuccessRate5m(), 0.0001);
    }

    private String startJsonServer(String body,
                                   String path,
                                   AtomicReference<String> requestedQuery) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            requestedQuery.set(exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery());
            byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
