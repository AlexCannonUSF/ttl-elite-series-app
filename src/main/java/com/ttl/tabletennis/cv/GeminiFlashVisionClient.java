package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

public class GeminiFlashVisionClient implements VlmClient {

    public static final String ENGINE_ID = "gemini-flash";
    public static final String DEFAULT_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
    public static final String DEFAULT_PROMPT = ""
            + "You are a scoreboard reader. Output ONLY JSON, no prose.\n"
            + "Read the table-tennis scoreboard in this image. Return:\n"
            + "{\"topGames\": int, \"botGames\": int, \"topPoints\": int, \"botPoints\": int,"
            + " \"server\": \"TOP\"|\"BOT\"|\"UNKNOWN\", \"confidence\": 0..1}\n"
            + "If unreadable, return {\"error\": \"UNREADABLE\"}.";

    static final double INPUT_USD_PER_M_TOKENS = 0.075;
    static final double OUTPUT_USD_PER_M_TOKENS = 0.30;

    private static final Logger log = LoggerFactory.getLogger(GeminiFlashVisionClient.class);

    private final String apiKey;
    private final String endpoint;
    private final VlmHttpExchange http;
    private final ObjectMapper objectMapper;
    private final VlmResponseParser parser;
    private final String prompt;

    public GeminiFlashVisionClient(String apiKey,
                                   String endpoint,
                                   VlmHttpExchange http,
                                   ObjectMapper objectMapper,
                                   VlmResponseParser parser,
                                   String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (http == null) {
            throw new IllegalArgumentException("http must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        if (parser == null) {
            throw new IllegalArgumentException("parser must not be null");
        }
        this.apiKey = apiKey;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.http = http;
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.prompt = prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt;
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public VlmScoreReadingResult readScoreboard(VlmRequest request) {
        long startNanos = System.nanoTime();
        String body;
        try {
            body = buildRequestBody(request);
        } catch (Exception e) {
            return VlmScoreReadingResult.error("gemini-request-build: " + e.getMessage(), elapsedSince(startNanos));
        }

        VlmHttpExchange.Response response;
        try {
            response = http.post(endpointWithKey(), Map.of(), body, request.timeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VlmScoreReadingResult.error("gemini-interrupted", elapsedSince(startNanos));
        } catch (Exception e) {
            return VlmScoreReadingResult.error("gemini-http: " + e.getMessage(), elapsedSince(startNanos));
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("[vlm-gemini] non-2xx response status={} body={}", response.statusCode(), truncate(response.body()));
            return VlmScoreReadingResult.error("gemini-status-" + response.statusCode(), elapsedSince(startNanos));
        }

        return interpret(response.body(), startNanos);
    }

    VlmScoreReadingResult interpret(String rawBody, long startNanos) {
        Duration latency = elapsedSince(startNanos);
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return VlmScoreReadingResult.error("gemini-response-not-json: " + e.getMessage(), latency);
        }
        String assistantText = extractAssistantText(root);
        TokenUsage usage = extractTokenUsage(root);
        double cost = estimateCostUsd(usage.input(), usage.output());

        VlmResponseParser.ParseOutcome outcome = parser.parse(assistantText);
        return switch (outcome.status()) {
            case OK -> VlmScoreReadingResult.ok(outcome.reading().orElseThrow(), latency, usage.input(), usage.output(), cost);
            case UNREADABLE -> VlmScoreReadingResult.unreadable(outcome.error(), latency, usage.input(), usage.output(), cost);
            case MALFORMED -> VlmScoreReadingResult.error("gemini-malformed: " + outcome.error(), latency);
        };
    }

    String buildRequestBody(VlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", prompt);
        ObjectNode inlineWrap = parts.addObject();
        ObjectNode inline = inlineWrap.putObject("inline_data");
        inline.put("mime_type", request.imageContentType());
        inline.put("data", Base64.getEncoder().encodeToString(request.imageBytes()));

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("temperature", 0.0);
        generationConfig.put("maxOutputTokens", 256);

        return root.toString();
    }

    String extractAssistantText(JsonNode root) {
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return "";
        }
        JsonNode first = candidates.get(0);
        JsonNode parts = first.path("content").path("parts");
        if (!parts.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : parts) {
            JsonNode textNode = part.path("text");
            if (textNode.isTextual()) {
                builder.append(textNode.asText());
            }
        }
        return builder.toString();
    }

    TokenUsage extractTokenUsage(JsonNode root) {
        JsonNode usage = root.path("usageMetadata");
        int input = usage.path("promptTokenCount").asInt(0);
        int output = usage.path("candidatesTokenCount").asInt(0);
        return new TokenUsage(input, output);
    }

    static double estimateCostUsd(int tokensIn, int tokensOut) {
        return (tokensIn / 1_000_000.0) * INPUT_USD_PER_M_TOKENS
                + (tokensOut / 1_000_000.0) * OUTPUT_USD_PER_M_TOKENS;
    }

    String endpointWithKey() {
        return endpoint + "?key=" + apiKey;
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > 256 ? raw.substring(0, 256) + "…" : raw;
    }

    record TokenUsage(int input, int output) { }
}
