package com.ttl.tabletennis.cv;

import java.util.Locale;
import java.util.Optional;

public enum StreamPlatform {
    YOUTUBE,
    TWITCH,
    TT_SERIES_HLS,
    DIRECT_HLS,
    UNKNOWN;

    public static Optional<StreamPlatform> fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalized = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        if ("TTSERIES_HLS".equals(normalized) || "TTSERIESHLS".equals(normalized)) {
            normalized = "TT_SERIES_HLS";
        }
        try {
            return Optional.of(StreamPlatform.valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static StreamPlatform inferFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return UNKNOWN;
        }
        String normalized = url.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("youtube.com") || normalized.contains("youtu.be")) {
            return YOUTUBE;
        }
        if (normalized.contains("twitch.tv")) {
            return TWITCH;
        }
        if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) {
            return DIRECT_HLS;
        }
        return UNKNOWN;
    }
}
