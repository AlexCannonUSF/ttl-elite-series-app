package com.ttl.tabletennis.cv;

public record VlmScoreReading(int topGames,
                              int botGames,
                              int topPoints,
                              int botPoints,
                              ServerSide server,
                              double confidence) {

    public VlmScoreReading {
        if (topGames < 0 || botGames < 0 || topPoints < 0 || botPoints < 0) {
            throw new IllegalArgumentException("score fields must be non-negative");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        server = server == null ? ServerSide.UNKNOWN : server;
    }
}
