package com.ttl.tabletennis.cv;

public record ScoreTuple(int topGames,
                         int botGames,
                         int topPoints,
                         int botPoints,
                         ServerSide server,
                         ScorePhase phase) {

    private static final int POINT_SANITY_CAP = 20;

    public ScoreTuple {
        if (topGames < 0 || botGames < 0 || topPoints < 0 || botPoints < 0) {
            throw new IllegalArgumentException("score values must be non-negative");
        }
        if (topPoints > POINT_SANITY_CAP || botPoints > POINT_SANITY_CAP) {
            throw new IllegalArgumentException("point values exceed sanity cap");
        }
        server = server == null ? ServerSide.UNKNOWN : server;
        phase = phase == null ? ScorePhase.fromPoints(topPoints, botPoints) : phase;
    }

    public boolean plausibleForBestOf(int bestOf) {
        int gamesToWin = gamesToWin(bestOf);
        return topGames <= gamesToWin && botGames <= gamesToWin;
    }

    public boolean gameEnded() {
        int leader = Math.max(topPoints, botPoints);
        int trailer = Math.min(topPoints, botPoints);
        return leader >= 11 && leader - trailer >= 2;
    }

    public boolean sameGameAs(ScoreTuple previous) {
        return previous != null && topGames == previous.topGames && botGames == previous.botGames;
    }

    public boolean validNextAfter(ScoreTuple previous, int bestOf) {
        if (!plausibleForBestOf(bestOf)) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        if (!previous.plausibleForBestOf(bestOf)) {
            return false;
        }
        if (equals(previous)) {
            return true;
        }
        if (sameGameAs(previous)) {
            int topDelta = topPoints - previous.topPoints;
            int botDelta = botPoints - previous.botPoints;
            return (topDelta == 1 && botDelta == 0) || (topDelta == 0 && botDelta == 1);
        }
        return validGameFlipAfter(previous, bestOf);
    }

    private boolean validGameFlipAfter(ScoreTuple previous, int bestOf) {
        if (!previous.gameEnded()) {
            return false;
        }
        int topGameDelta = topGames - previous.topGames;
        int botGameDelta = botGames - previous.botGames;
        boolean exactlyOneGameAdded = (topGameDelta == 1 && botGameDelta == 0)
                || (topGameDelta == 0 && botGameDelta == 1);
        if (!exactlyOneGameAdded) {
            return false;
        }
        int gamesToWin = gamesToWin(bestOf);
        return topGames <= gamesToWin
                && botGames <= gamesToWin
                && topPoints <= 1
                && botPoints <= 1;
    }

    private static int gamesToWin(int bestOf) {
        int normalized = bestOf <= 1 ? 5 : bestOf;
        if (normalized % 2 == 0) {
            normalized++;
        }
        return (normalized / 2) + 1;
    }
}
