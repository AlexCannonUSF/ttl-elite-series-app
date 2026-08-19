package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeHaikuVisionClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VlmResponseParser parser = new VlmResponseParser(objectMapper);

    @Test
    void buildRequestBodyEmbedsImageBlockAndModelId() throws Exception {
        RecordingHttp http = new RecordingHttp(new VlmHttpExchange.Response(200, claudeOkResponse(
                "{\"topGames\":0,\"botGames\":1,\"topPoints\":4,\"botPoints\":11,\"server\":\"BOT\",\"confidence\":0.88}"
        )));
        ClaudeHaikuVisionClient client = new ClaudeHaikuVisionClient(
                "anth-test", null, null, null, http, objectMapper, parser, null);

        byte[] image = new byte[]{1, 2, 3, 4};
        VlmRequest req = new VlmRequest(image, "image/png", "match-2", "frame-2", Duration.ofSeconds(2));
        VlmScoreReadingResult result = client.readScoreboard(req);

        assertEquals(VlmScoreReadingResult.Status.OK, result.status());
        assertEquals(1, http.calls.size());
        RecordingHttp.Call call = http.calls.get(0);
        assertEquals("anth-test", call.headers().get("x-api-key"));
        assertEquals(ClaudeHaikuVisionClient.DEFAULT_ANTHROPIC_VERSION, call.headers().get("anthropic-version"));

        JsonNode body = objectMapper.readTree(call.body());
        assertEquals(ClaudeHaikuVisionClient.DEFAULT_MODEL_ID, body.path("model").asText());
        JsonNode content = body.path("messages").path(0).path("content");
        assertEquals("image", content.path(0).path("type").asText());
        assertEquals("image/png", content.path(0).path("source").path("media_type").asText());
        assertEquals(Base64.getEncoder().encodeToString(image), content.path(0).path("source").path("data").asText());
        assertEquals("text", content.path(1).path("type").asText());
    }

    @Test
    void interpretMapsOkResponseToOkResult() {
        ClaudeHaikuVisionClient client = buildClient(new RecordingHttp());
        VlmScoreReadingResult result = client.interpret(claudeOkResponse(
                "{\"topGames\":3,\"botGames\":2,\"topPoints\":0,\"botPoints\":0,\"server\":\"TOP\",\"confidence\":0.99}"
        ), System.nanoTime());

        assertEquals(VlmScoreReadingResult.Status.OK, result.status());
        VlmScoreReading reading = result.reading().orElseThrow();
        assertEquals(3, reading.topGames());
        assertEquals(0.99, reading.confidence());
        assertEquals(220, result.tokensIn());
        assertEquals(12, result.tokensOut());
        assertTrue(result.costEstimateUsd() > 0.0);
    }

    @Test
    void interpretMapsUnreadableToUnreadable() {
        ClaudeHaikuVisionClient client = buildClient(new RecordingHttp());
        VlmScoreReadingResult result = client.interpret(claudeOkResponse("{\"error\":\"UNREADABLE\"}"), System.nanoTime());

        assertEquals(VlmScoreReadingResult.Status.UNREADABLE, result.status());
    }

    @Test
    void readScoreboardReturnsErrorOnNon2xx() {
        RecordingHttp http = new RecordingHttp(new VlmHttpExchange.Response(500, "boom"));
        ClaudeHaikuVisionClient client = buildClient(http);
        VlmScoreReadingResult result = client.readScoreboard(req());
        assertEquals(VlmScoreReadingResult.Status.ERROR, result.status());
        assertTrue(result.error().contains("500"));
    }

    @Test
    void readScoreboardReturnsErrorOnIoFailure() {
        VlmHttpExchange exploding = (url, headers, body, timeout) -> { throw new IOException("dns"); };
        ClaudeHaikuVisionClient client = buildClient(exploding);
        VlmScoreReadingResult result = client.readScoreboard(req());
        assertEquals(VlmScoreReadingResult.Status.ERROR, result.status());
        assertTrue(result.error().contains("dns"));
    }

    @Test
    void constructorRejectsBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new ClaudeHaikuVisionClient(
                "", null, null, null, new RecordingHttp(), objectMapper, parser, null));
    }

    @Test
    void engineIdIsClaudeHaiku() {
        assertEquals(ClaudeHaikuVisionClient.ENGINE_ID, buildClient(new RecordingHttp()).engineId());
    }

    @Test
    void costEstimateScalesWithUsage() {
        double oneM = ClaudeHaikuVisionClient.estimateCostUsd(1_000_000, 0);
        double oneMOut = ClaudeHaikuVisionClient.estimateCostUsd(0, 1_000_000);
        assertEquals(ClaudeHaikuVisionClient.INPUT_USD_PER_M_TOKENS, oneM, 1e-9);
        assertEquals(ClaudeHaikuVisionClient.OUTPUT_USD_PER_M_TOKENS, oneMOut, 1e-9);
    }

    private ClaudeHaikuVisionClient buildClient(VlmHttpExchange http) {
        return new ClaudeHaikuVisionClient("k", null, null, null, http, objectMapper, parser, null);
    }

    private VlmRequest req() {
        return new VlmRequest("frame".getBytes(), "image/jpeg", "m", "f", Duration.ofSeconds(2));
    }

    private static String claudeOkResponse(String assistantJson) {
        String escaped = assistantJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  "content": [{"type": "text", "text": "%s"}],
                  "usage": {"input_tokens": 220, "output_tokens": 12},
                  "model": "claude-haiku-4-5-20251001",
                  "stop_reason": "end_turn"
                }
                """.formatted(escaped);
    }

    private static final class RecordingHttp implements VlmHttpExchange {
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
