package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class ClaudeHaikuVisionClient implements VlmClient {

    public static final String ENGINE_ID = "claude-haiku";
    public static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    public static final String DEFAULT_MODEL_ID = "claude-haiku-4-5-20251001";
    public static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";

    static final double INPUT_USD_PER_M_TOKENS = 1.0;
    static final double OUTPUT_USD_PER_M_TOKENS = 5.0;
    static final int MAX_OUTPUT_TOKENS = 256;

    private static final Logger log = LoggerFactory.getLogger(ClaudeHaikuVisionClient.class);

    private final String apiKey;
    private final String modelId;
    private final String endpoint;
    private final String anthropicVersion;
    private final VlmHttpExchange http;
    private final ObjectMapper objectMapper;
    private final VlmResponseParser parser;
    private final String prompt;

    public ClaudeHaikuVisionClient(String apiKey,
                                   String modelId,
                                   String endpoint,
                                   String anthropicVersion,
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
        this.modelId = modelId == null || modelId.isBlank() ? DEFAULT_MODEL_ID : modelId.trim();
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
        this.anthropicVersion = anthropicVersion == null || anthropicVersion.isBlank()
                ? DEFAULT_ANTHROPIC_VERSION : anthropicVersion.trim();
        this.http = http;
        this.objectMapper = objectMapper;
        this.parser = parser;
        this.prompt = prompt == null || prompt.isBlank() ? GeminiFlashVisionClient.DEFAULT_PROMPT : prompt;
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
            return VlmScoreReadingResult.error("claude-request-build: " + e.getMessage(), elapsedSince(startNanos));
        }

        VlmHttpExchange.Response response;
        try {
            response = http.post(endpoint, headers(), body, request.timeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return VlmScoreReadingResult.error("claude-interrupted", elapsedSince(startNanos));
        } catch (Exception e) {
            return VlmScoreReadingResult.error("claude-http: " + e.getMessage(), elapsedSince(startNanos));
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("[vlm-claude] non-2xx response status={} body={}", response.statusCode(), truncate(response.body()));
            return VlmScoreReadingResult.error("claude-status-" + response.statusCode(), elapsedSince(startNanos));
        }

        return interpret(response.body(), startNanos);
    }

    VlmScoreReadingResult interpret(String rawBody, long startNanos) {
        Duration latency = elapsedSince(startNanos);
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return VlmScoreReadingResult.error("claude-response-not-json: " + e.getMessage(), latency);
        }
        String assistantText = extractAssistantText(root);
        TokenUsage usage = extractTokenUsage(root);
        double cost = estimateCostUsd(usage.input(), usage.output());

        VlmResponseParser.ParseOutcome outcome = parser.parse(assistantText);
        return switch (outcome.status()) {
            case OK -> VlmScoreReadingResult.ok(outcome.reading().orElseThrow(), latency, usage.input(), usage.output(), cost);
            case UNREADABLE -> VlmScoreReadingResult.unreadable(outcome.error(), latency, usage.input(), usage.output(), cost);
            case MALFORMED -> VlmScoreReadingResult.error("claude-malformed: " + outcome.error(), latency);
        };
    }

    String buildRequestBody(VlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelId);
        root.put("max_tokens", MAX_OUTPUT_TOKENS);
        root.put("temperature", 0.0);

        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");

        ArrayNode content = message.putArray("content");
        ObjectNode imageBlock = content.addObject();
        imageBlock.put("type", "image");
        ObjectNode source = imageBlock.putObject("source");
        source.put("type", "base64");
        source.put("media_type", request.imageContentType());
        source.put("data", Base64.getEncoder().encodeToString(request.imageBytes()));

        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", prompt);

        return root.toString();
    }

    Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", anthropicVersion);
        return headers;
    }

    String extractAssistantText(JsonNode root) {
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                JsonNode textNode = block.path("text");
                if (textNode.isTextual()) {
                    builder.append(textNode.asText());
                }
            }
        }
        return builder.toString();
    }

    TokenUsage extractTokenUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        return new TokenUsage(input, output);
    }

    static double estimateCostUsd(int tokensIn, int tokensOut) {
        return (tokensIn / 1_000_000.0) * INPUT_USD_PER_M_TOKENS
                + (tokensOut / 1_000_000.0) * OUTPUT_USD_PER_M_TOKENS;
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
