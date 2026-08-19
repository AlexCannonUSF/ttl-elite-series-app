package com.ttl.tabletennis.prediction.shadow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

public class HttpBlenderClient implements BlenderClient {

    private static final Logger log = LoggerFactory.getLogger(HttpBlenderClient.class);

    private final String endpoint;
    private final BlenderHttpExchange http;
    private final ObjectMapper objectMapper;

    public HttpBlenderClient(String endpoint, BlenderHttpExchange http, ObjectMapper objectMapper) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        if (http == null) {
            throw new IllegalArgumentException("http must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.endpoint = endpoint.trim();
        this.http = http;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result score(BlenderRequest request, Duration timeout) {
        long startNanos = System.nanoTime();
        String body;
        try {
            body = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return Result.error("blender-request-build: " + e.getMessage(), elapsedMs(startNanos));
        }

        BlenderHttpExchange.Response response;
        try {
            response = http.post(endpoint, Map.of(), body, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("blender-interrupted", elapsedMs(startNanos));
        } catch (Exception e) {
            return Result.serviceUnavailable("blender-http: " + e.getMessage(), elapsedMs(startNanos));
        }

        long latency = elapsedMs(startNanos);
        int status = response.statusCode();
        if (status == 503) {
            return Result.serviceUnavailable("blender-status-503: " + truncate(response.body()), latency);
        }
        if (status == 409) {
            return Result.schemaHashMismatch("blender-status-409: " + truncate(response.body()), latency);
        }
        if (status < 200 || status >= 300) {
            log.warn("[blender-client] non-2xx status={} body={}", status, truncate(response.body()));
            return Result.error("blender-status-" + status, latency);
        }

        try {
            return Result.ok(parseResponse(response.body()), latency);
        } catch (Exception e) {
            return Result.error("blender-response-parse: " + e.getMessage(), latency);
        }
    }

    BlenderResponse parseResponse(String body) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode pTop = root.path("pTop");
        JsonNode pBot = root.path("pBot");
        JsonNode uncertainty = root.path("uncertainty");
        return new BlenderResponse(
                root.path("matchId").asText(""),
                root.path("modelVersion").asText(""),
                root.path("calibratorVersion").asText(""),
                root.path("conformalVersion").asText(""),
                root.path("featureSchemaHash").asText(""),
                clampProbability(pTop.path("value").asDouble(Double.NaN)),
                clampProbability(pBot.path("value").asDouble(Double.NaN)),
                clampProbability(pTop.path("rawValue").asDouble(Double.NaN)),
                uncertainty.path("label").asText("UNKNOWN"),
                uncertainty.path("alpha").asDouble(0.1),
                root.path("latencyMs").asDouble(0.0),
                parseSanity(root.path("sanity"))
        );
    }

    java.util.Optional<BlenderResponse.Sanity> parseSanity(JsonNode sanityNode) {
        if (sanityNode == null || sanityNode.isMissingNode() || sanityNode.isNull()) {
            return java.util.Optional.empty();
        }
        JsonNode pTop = sanityNode.path("pTop");
        JsonNode pBot = sanityNode.path("pBot");
        JsonNode uncertainty = sanityNode.path("uncertainty");
        return java.util.Optional.of(new BlenderResponse.Sanity(
                sanityNode.path("variant").asText("B"),
                sanityNode.path("modelVersion").asText(""),
                sanityNode.path("calibratorVersion").asText(""),
                sanityNode.path("conformalVersion").asText(""),
                sanityNode.path("featureSchemaHash").asText(""),
                clampProbability(pTop.path("value").asDouble(Double.NaN)),
                clampProbability(pBot.path("value").asDouble(Double.NaN)),
                uncertainty.path("label").asText("UNKNOWN"),
                clampProbability(sanityNode.path("absoluteDiffPTop").asDouble(Double.NaN)),
                sanityNode.path("latencyMs").asDouble(0.0)
        ));
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static double clampProbability(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.5;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > 200 ? raw.substring(0, 200) + "…" : raw;
    }
}
