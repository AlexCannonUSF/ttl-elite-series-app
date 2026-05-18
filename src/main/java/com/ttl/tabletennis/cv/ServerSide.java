package com.ttl.tabletennis.cv;

import java.util.Locale;

public enum ServerSide {
    TOP,
    BOT,
    UNKNOWN;

    public static ServerSide fromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return UNKNOWN;
        }
        try {
            return ServerSide.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
