package com.ttl.tabletennis.scrape;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ItftWttFeedClientTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void pullOnceParsesHtmlRankingTable() throws Exception {
        String html = """
                <html>
                  <body>
                    <table>
                      <thead>
                        <tr><th>Rank</th><th>Player</th><th>Country</th><th>Points</th></tr>
                      </thead>
                      <tbody>
                        <tr><td>1</td><td>Sun Yingsha</td><td>CHN</td><td>9200</td></tr>
                        <tr><td>2</td><td>Wang Manyu</td><td>CHN</td><td>8100</td></tr>
                      </tbody>
                    </table>
                  </body>
                </html>
                """;
        String rankingUrl = startServer(html, "/rankings", "text/html");
        IngestionBus ingestionBus = mock(IngestionBus.class);
        ItftWttFeedClient client = new ItftWttFeedClient(
                HttpClient.newHttpClient(),
                ingestionBus,
                true,
                rankingUrl,
                "",
                "",
                "TTLEliteSeries/Test",
                5000,
                0.82
        );

        List<IngestEvent<ItftWttHistoricalPayload>> events = client.pullOnce(
                new FeedClient.PullContext(
                        Instant.parse("2026-04-19T18:45:00Z"),
                        "corr-wtt",
                        Map.of(
                                "asOfDate", "2026-04-19",
                                "limit", "1"
                        )
                )
        );

        assertEquals(1, events.size());
        IngestEvent<ItftWttHistoricalPayload> event = events.get(0);
        ItftWttHistoricalPayload payload = event.payload();
        assertEquals(SourceId.ITTF_WTT, event.source());
        assertEquals("ranking.updated", event.topic());
        assertEquals(0.82, event.confidence(), 0.0001);
        assertEquals("Sun Yingsha", payload.playerName());
        assertEquals("CHN", payload.country());
        assertEquals(1, payload.rank());
        assertEquals(9200.0, payload.points(), 0.0001);
        assertEquals(LocalDate.of(2026, 4, 19), payload.asOfDate());
        verify(ingestionBus).publishAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void pullOnceParsesJsonRankingRows() throws Exception {
        String json = """
                {
                  "data": {
                    "rankings": [
                      {
                        "id": "wtt-42",
                        "playerName": "Alexis Lebrun",
                        "country": "FRA",
                        "rank": 8,
                        "points": 3410.5
                      }
                    ]
                  }
                }
                """;
        String rankingUrl = startServer(json, "/rankings.json", "application/json");
        ItftWttFeedClient client = new ItftWttFeedClient(
                HttpClient.newHttpClient(),
                mock(IngestionBus.class),
                true,
                rankingUrl,
                "",
                "",
                "TTLEliteSeries/Test",
                5000,
                0.82
        );

        List<IngestEvent<ItftWttHistoricalPayload>> events = client.pullOnce(
                new FeedClient.PullContext(Instant.parse("2026-04-19T18:45:00Z"), "corr-json", null)
        );

        assertEquals(1, events.size());
        ItftWttHistoricalPayload payload = events.get(0).payload();
        assertEquals("wtt-42", payload.sourceKey());
        assertEquals("Alexis Lebrun", payload.playerName());
        assertEquals(8, payload.rank());
        assertEquals(3410.5, payload.points(), 0.0001);
    }

    @Test
    void disabledClientReturnsNoEventsAndKeepsIdleHealth() {
        ItftWttFeedClient client = new ItftWttFeedClient(
                HttpClient.newHttpClient(),
                mock(IngestionBus.class),
                false,
                "https://worldtabletennis.com/rankings",
                "",
                "",
                "TTLEliteSeries/Test",
                5000,
                0.82
        );

        assertTrue(client.pullOnce(FeedClient.PullContext.now("corr-disabled")).isEmpty());
        assertEquals(SourceId.ITTF_WTT, client.currentHealth().source());
        assertEquals(1.0, client.currentHealth().rollingSuccessRate5m(), 0.0001);
        assertTrue(client.capabilities().contains(FeedClient.Capability.RANKINGS));
    }

    private String startServer(String body, String path, String contentType) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
