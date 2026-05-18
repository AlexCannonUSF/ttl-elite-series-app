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

class AiScoreFeedClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void pullOnceParsesAiScoreLiveJsonIntoMirrorPayload() throws Exception {
        String liveJson = """
                {
                  "data": [
                    {
                      "match_id": "as-77",
                      "status": "inprogress",
                      "competition": {"name": "WTT Feeder"},
                      "home_name": "Left Player",
                      "away_name": "Right Player",
                      "score": "1-0",
                      "point_score": "10-8",
                      "service": "away",
                      "live": true
                    }
                  ]
                }
                """;
        AtomicReference<String> apiKeyHeader = new AtomicReference<>("");
        String baseUrl = startJsonServer(liveJson, "/v3/matchs/live", apiKeyHeader);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        AiScoreFeedClient client = new AiScoreFeedClient(
                HttpClient.newHttpClient(),
                ingestionBus,
                new OddsSnapshotFactory(),
                true,
                baseUrl,
                "/v3/matchs/live?type=4",
                "/v3/match/detail?match_id=%s",
                "X-AiScore-Key",
                "local-key",
                baseUrl + "/table-tennis",
                "TTLEliteSeries/Test",
                5000,
                0.72,
                0.86
        );

        List<IngestEvent<MirrorObservationPayload>> events = client.pullOnce(
                new FeedClient.PullContext(Instant.parse("2026-04-19T18:45:00Z"), "corr-ai", null)
        );

        assertEquals(1, events.size());
        IngestEvent<MirrorObservationPayload> event = events.get(0);
        MirrorObservationPayload payload = event.payload();
        assertEquals(SourceId.AISCORE, event.source());
        assertEquals("score.observed", event.topic());
        assertEquals(0.72, event.confidence(), 0.0001);
        assertEquals("Left Player", payload.player1Name());
        assertEquals("Right Player", payload.player2Name());
        assertEquals("WTT Feeder", payload.competitionName());
        assertEquals("LIVE_EARLY", payload.phase());
        assertEquals(1, payload.gamesP1());
        assertEquals(0, payload.gamesP2());
        assertEquals(10, payload.pointsP1());
        assertEquals(8, payload.pointsP2());
        assertEquals("P2", payload.server());
        assertFalse(payload.completionSignal());
        assertEquals("local-key", apiKeyHeader.get());
        verify(ingestionBus).publishAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void disabledClientReturnsNoEventsAndKeepsIdleHealth() {
        AiScoreFeedClient client = new AiScoreFeedClient(
                HttpClient.newHttpClient(),
                mock(IngestionBus.class),
                new OddsSnapshotFactory(),
                false,
                "https://www.aiscore.com/api",
                "/v3/matchs/live?type=4",
                "/v3/match/detail?match_id=%s",
                "",
                "",
                "",
                "TTLEliteSeries/Test",
                5000,
                0.72,
                0.86
        );

        assertTrue(client.pullOnce(FeedClient.PullContext.now("corr-disabled")).isEmpty());
        assertEquals(SourceId.AISCORE, client.currentHealth().source());
        assertEquals(1.0, client.currentHealth().rollingSuccessRate5m(), 0.0001);
    }

    private String startJsonServer(String body,
                                   String path,
                                   AtomicReference<String> apiKeyHeader) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-AiScore-Key"));
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
