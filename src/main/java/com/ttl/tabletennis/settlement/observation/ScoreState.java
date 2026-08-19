package com.ttl.tabletennis.settlement.observation;

public record ScoreState(Integer gamesP1,
                         Integer gamesP2,
                         Integer pointsP1,
                         Integer pointsP2,
                         String server) {

    private static final ScoreState UNKNOWN = new ScoreState(null, null, null, null, "");

    public ScoreState {
        validateNonNegative(gamesP1, "gamesP1");
        validateNonNegative(gamesP2, "gamesP2");
        validateNonNegative(pointsP1, "pointsP1");
        validateNonNegative(pointsP2, "pointsP2");
        server = server == null ? "" : server.trim().toUpperCase();
    }

    public static ScoreState unknown() {
        return UNKNOWN;
    }

    public boolean hasAnyScore() {
        return gamesP1 != null
                || gamesP2 != null
                || pointsP1 != null
                || pointsP2 != null
                || !server.isEmpty();
    }

    private static void validateNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
