package com.ttl.tabletennis.prediction.markov;

public record MarkovSimulationRequest(
        String matchId,
        double pPointTopOnServe,
        Double pPointTopOnReceive,
        int bestOf
) {
    public MarkovSimulationRequest {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("matchId must not be blank");
        }
        if (!isProbability(pPointTopOnServe)) {
            throw new IllegalArgumentException("pPointTopOnServe must be in [0,1]");
        }
        if (pPointTopOnReceive != null && !isProbability(pPointTopOnReceive)) {
            throw new IllegalArgumentException("pPointTopOnReceive must be in [0,1]");
        }
        if (bestOf < 3 || bestOf > 9) {
            throw new IllegalArgumentException("bestOf must be in [3,9]");
        }
    }

    public static MarkovSimulationRequest preMatch(String matchId, double pPointTopOnServe, int bestOf) {
        return new MarkovSimulationRequest(matchId, pPointTopOnServe, null, bestOf);
    }

    private static boolean isProbability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
