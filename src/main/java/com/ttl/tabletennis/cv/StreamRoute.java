package com.ttl.tabletennis.cv;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

public record StreamRoute(String eventCode,
                          String tableNumber,
                          StreamPlatform platform,
                          String channelId,
                          String baseUrl,
                          String streamUrl,
                          String roiTemplateId,
                          String notes) {

    public StreamRoute {
        eventCode = requireText(eventCode, "eventCode").toUpperCase(Locale.ROOT);
        tableNumber = requireText(tableNumber, "tableNumber");
        platform = platform == null ? StreamPlatform.UNKNOWN : platform;
        channelId = clean(channelId);
        baseUrl = clean(baseUrl);
        streamUrl = clean(streamUrl);
        roiTemplateId = requireText(roiTemplateId, "roiTemplateId");
        notes = clean(notes);
    }

    public boolean matches(StreamRoutingRequest request) {
        if (request == null || request.eventCode().isBlank()) {
            return false;
        }
        boolean eventMatches = eventCode.equalsIgnoreCase(request.eventCode());
        boolean tableMatches = "*".equals(tableNumber) || tableNumber.equalsIgnoreCase(request.tableNumber());
        return eventMatches && tableMatches;
    }

    public Optional<String> resolvedStreamUrl(String requestedTableNumber) {
        if (!streamUrl.isBlank()) {
            return Optional.of(streamUrl);
        }
        if (!baseUrl.isBlank()) {
            return Optional.of(baseUrl.replace("{tableNumber}", encode(requestedTableNumber)));
        }
        if (!channelId.isBlank() && platform == StreamPlatform.YOUTUBE) {
            return Optional.of("https://www.youtube.com/channel/" + encode(channelId) + "/live");
        }
        if (!channelId.isBlank() && platform == StreamPlatform.TWITCH) {
            return Optional.of("https://www.twitch.tv/" + encode(channelId));
        }
        return Optional.empty();
    }

    public String key() {
        return eventCode + ":" + tableNumber;
    }

    private static String encode(String value) {
        return URLEncoder.encode(clean(value), StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String field) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return cleaned;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
