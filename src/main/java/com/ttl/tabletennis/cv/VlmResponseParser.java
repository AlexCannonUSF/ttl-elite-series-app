package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

public final class VlmResponseParser {

    static final String SCHEMA_ERROR_UNREADABLE = "UNREADABLE";
    private static final String[] REQUIRED_FIELDS = {"topGames", "botGames", "topPoints", "botPoints", "confidence"};

    private final ObjectMapper objectMapper;

    public VlmResponseParser(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.objectMapper = objectMapper;
    }

    /**
     * Parse the VLM's JSON content per Stream-CV Spec §8.1. Returns either
     * a populated reading, or an empty Optional with {@link ParseStatus} indicating
     * unreadable / schema-violation / malformed-JSON. The caller maps that back
     * to a {@link VlmScoreReadingResult.Status}.
     */
    public ParseOutcome parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return ParseOutcome.malformed("empty response");
        }
        String json = extractJsonBlob(rawJson);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return ParseOutcome.malformed("not JSON: " + e.getMessage());
        }
        if (!root.isObject()) {
            return ParseOutcome.malformed("root must be an object");
        }

        if (root.hasNonNull("error")) {
            String errorValue = root.get("error").asText();
            if (SCHEMA_ERROR_UNREADABLE.equalsIgnoreCase(errorValue)) {
                return ParseOutcome.unreadable("UNREADABLE");
            }
            return ParseOutcome.malformed("error field: " + errorValue);
        }

        for (String field : REQUIRED_FIELDS) {
            if (!root.hasNonNull(field)) {
                return ParseOutcome.malformed("missing required field: " + field);
            }
        }
        if (!root.get("topGames").canConvertToInt()
                || !root.get("botGames").canConvertToInt()
                || !root.get("topPoints").canConvertToInt()
                || !root.get("botPoints").canConvertToInt()) {
            return ParseOutcome.malformed("score fields must be integers");
        }
        if (!root.get("confidence").isNumber()) {
            return ParseOutcome.malformed("confidence must be a number");
        }

        int topGames = root.get("topGames").asInt();
        int botGames = root.get("botGames").asInt();
        int topPoints = root.get("topPoints").asInt();
        int botPoints = root.get("botPoints").asInt();
        double confidence = root.get("confidence").asDouble();
        ServerSide server = ServerSide.fromValue(root.hasNonNull("server") ? root.get("server").asText() : null);

        if (topGames < 0 || botGames < 0 || topPoints < 0 || botPoints < 0) {
            return ParseOutcome.malformed("score fields must be non-negative");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            return ParseOutcome.malformed("confidence must be in [0, 1]");
        }

        return ParseOutcome.ok(new VlmScoreReading(topGames, botGames, topPoints, botPoints, server, confidence));
    }

    static String extractJsonBlob(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    public enum ParseStatus { OK, UNREADABLE, MALFORMED }

    public record ParseOutcome(ParseStatus status, Optional<VlmScoreReading> reading, String error) {

        public static ParseOutcome ok(VlmScoreReading reading) {
            return new ParseOutcome(ParseStatus.OK, Optional.of(reading), "");
        }

        public static ParseOutcome unreadable(String error) {
            return new ParseOutcome(ParseStatus.UNREADABLE, Optional.empty(), error);
        }

        public static ParseOutcome malformed(String error) {
            return new ParseOutcome(ParseStatus.MALFORMED, Optional.empty(), error);
        }
    }
}
