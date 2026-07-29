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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiFlashVisionClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VlmResponseParser parser = new VlmResponseParser(objectMapper);

    @Test
    void buildRequestBodyEmbedsBase64ImageAndPrompt() throws Exception {
        RecordingHttp http = new RecordingHttp(new VlmHttpExchange.Response(200, geminiOkResponse(
                "{\"topGames\":1,\"botGames\":0,\"topPoints\":7,\"botPoints\":4,\"server\":\"TOP\",\"confidence\":0.91}"
        )));
        GeminiFlashVisionClient client = new GeminiFlashVisionClient(
                "test-key", "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
                http, objectMapper, parser, null);

        byte[] image = "fake-jpeg".getBytes();
        VlmRequest req = new VlmRequest(image, "image/jpeg", "match-1", "frame-1", Duration.ofSeconds(3));
        VlmScoreReadingResult result = client.readScoreboard(req);

        assertEquals(VlmScoreReadingResult.Status.OK, result.status());
        assertEquals(1, http.calls.size());
        RecordingHttp.Call call = http.calls.get(0);
        assertTrue(call.url().contains("?key=test-key"));

        JsonNode body = objectMapper.readTree(call.body());
        JsonNode parts = body.path("contents").path(0).path("parts");
        assertTrue(parts.path(0).path("text").asText().contains("scoreboard reader"));
        assertEquals("image/jpeg", parts.path(1).path("inline_data").path("mime_type").asText());
        assertEquals(Base64.getEncoder().encodeToString(image), parts.path(1).path("inline_data").path("data").asText());
        assertEquals("application/json", body.path("generationConfig").path("responseMimeType").asText());
    }

    @Test
    void interpretMapsOkResponseToOkResult() {
        GeminiFlashVisionClient client = buildClient(new RecordingHttp());
        VlmScoreReadingResult result = client.interpret(geminiOkResponse(
                "{\"topGames\":2,\"botGames\":1,\"topPoints\":11,\"botPoints\":9,\"server\":\"BOT\",\"confidence\":0.96}"
        ), System.nanoTime());

        assertEquals(VlmScoreReadingResult.Status.OK, result.status());
        VlmScoreReading reading = result.reading().orElseThrow();
        assertEquals(11, reading.topPoints());
        assertEquals(ServerSide.BOT, reading.server());
        assertEquals(120, result.tokensIn());
        assertEquals(8, result.tokensOut());
        assertTrue(result.costEstimateUsd() > 0.0);
    }

    @Test
    void interpretMapsUnreadablePayloadToUnreadableResult() {
        GeminiFlashVisionClient client = buildClient(new RecordingHttp());
        VlmScoreReadingResult result = client.interpret(geminiOkResponse("{\"error\":\"UNREADABLE\"}"), System.nanoTime());

        assertEquals(VlmScoreReadingResult.Status.UNREADABLE, result.status());
    }

    @Test
    void interpretMapsMalformedPayloadToErrorResult() {
        GeminiFlashVisionClient client = buildClient(new RecordingHttp());
        VlmScoreReadingResult result = client.interpret(geminiOkResponse("not even close to json"), System.nanoTime());

        assertEquals(VlmScoreReadingResult.Status.ERROR, result.status());
        assertTrue(result.error().contains("malformed"));
    }

    @Test
    void readScoreboardReturnsErrorOnNon2xx() {
        RecordingHttp http = new RecordingHttp(new VlmHttpExchange.Response(429, "{\"error\":\"rate\"}"));
        GeminiFlashVisionClient client = buildClient(http);

        VlmScoreReadingResult result = client.readScoreboard(req());
        assertEquals(VlmScoreReadingResult.Status.ERROR, result.status());
        assertTrue(result.error().contains("429"));
    }

    @Test
    void readScoreboardReturnsErrorOnHttpException() {
        VlmHttpExchange exploding = (url, headers, body, timeout) -> { throw new IOException("network down"); };
        GeminiFlashVisionClient client = buildClient(exploding);

        VlmScoreReadingResult result = client.readScoreboard(req());
        assertEquals(VlmScoreReadingResult.Status.ERROR, result.status());
        assertTrue(result.error().contains("network down"));
    }

    @Test
    void constructorRejectsBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new GeminiFlashVisionClient(
                "", "https://example.com", new RecordingHttp(), objectMapper, parser, null));
    }

    @Test
    void engineIdIsGeminiFlash() {
        assertEquals(GeminiFlashVisionClient.ENGINE_ID, buildClient(new RecordingHttp()).engineId());
        assertNotEquals(DisabledVlmClient.ENGINE_ID, GeminiFlashVisionClient.ENGINE_ID);
    }

    @Test
    void estimateCostUsesPublishedPricingConstants() {
        double cost = GeminiFlashVisionClient.estimateCostUsd(1_000_000, 1_000_000);
        assertEquals(GeminiFlashVisionClient.INPUT_USD_PER_M_TOKENS + GeminiFlashVisionClient.OUTPUT_USD_PER_M_TOKENS, cost, 1e-9);
    }

    private GeminiFlashVisionClient buildClient(VlmHttpExchange http) {
        return new GeminiFlashVisionClient("k", null, http, objectMapper, parser, null);
    }

    private VlmRequest req() {
        return new VlmRequest("frame-bytes".getBytes(), "image/jpeg", "match-1", "frame-1", Duration.ofSeconds(2));
    }

    private static String geminiOkResponse(String assistantJson) {
        String escaped = assistantJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {
                  "candidates": [{
                    "content": {"parts": [{"text": "%s"}]},
                    "finishReason": "STOP"
                  }],
                  "usageMetadata": {"promptTokenCount": 120, "candidatesTokenCount": 8, "totalTokenCount": 128}
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
