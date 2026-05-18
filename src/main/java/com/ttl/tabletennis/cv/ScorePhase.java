package com.ttl.tabletennis.cv;

import java.util.Locale;

public enum ScorePhase {
    NORMAL,
    DEUCE,
    INTERGAME;

    public static ScorePhase fromPoints(int topPoints, int botPoints) {
        return topPoints >= 10 && botPoints >= 10 ? DEUCE : NORMAL;
    }

    public static ScorePhase fromValue(String value, int topPoints, int botPoints) {
        if (value == null || value.trim().isEmpty()) {
            return fromPoints(topPoints, botPoints);
        }
        try {
            return ScorePhase.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fromPoints(topPoints, botPoints);
        }
    }
}
