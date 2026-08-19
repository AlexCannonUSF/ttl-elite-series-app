package com.ttl.tabletennis.cv;

import java.time.Instant;

public record StreamRouteResolution(String matchId,
                                    StreamPlatform platform,
                                    String streamUrl,
                                    String roiTemplateId,
                                    StreamRouteSource source,
                                    String routeKey,
                                    Instant resolvedAt) {

    public StreamRouteResolution {
        matchId = clean(matchId);
        platform = platform == null ? StreamPlatform.UNKNOWN : platform;
        streamUrl = requireText(streamUrl, "streamUrl");
        roiTemplateId = clean(roiTemplateId);
        source = source == null ? StreamRouteSource.ROUTE_OVERRIDE : source;
        routeKey = clean(routeKey);
        resolvedAt = resolvedAt == null ? Instant.now() : resolvedAt;
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
