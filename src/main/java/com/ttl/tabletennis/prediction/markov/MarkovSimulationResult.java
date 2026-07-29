package com.ttl.tabletennis.prediction.markov;

public record MarkovSimulationResult(
        Status status,
        String matchId,
        double pMatchTop,
        Double p30,
        Double p31,
        Double p32,
        Double expTotalPoints,
        Double medianMatchMinutes,
        String method,
        String version,
        String note,
        String reason,
        long latencyMs
) {
    public enum Status {
        OK,
        FALLBACK
    }

    public static MarkovSimulationResult ok(
            String matchId,
            double pMatchTop,
            Double p30,
            Double p31,
            Double p32,
            Double expTotalPoints,
            Double medianMatchMinutes,
            String method,
            String version,
            String note,
            long latencyMs
    ) {
        return new MarkovSimulationResult(
                Status.OK,
                matchId,
                pMatchTop,
                p30,
                p31,
                p32,
                expTotalPoints,
                medianMatchMinutes,
                method,
                version,
                note,
                "",
                latencyMs
        );
    }

    public static MarkovSimulationResult fallback(
            String matchId,
            double pMatchTop,
            Double p30,
            Double p31,
            Double p32,
            Double expTotalPoints,
            Double medianMatchMinutes,
            String method,
            String reason,
            long latencyMs
    ) {
        return new MarkovSimulationResult(
                Status.FALLBACK,
                matchId,
                pMatchTop,
                p30,
                p31,
                p32,
                expTotalPoints,
                medianMatchMinutes,
                method,
                "java-fallback-v1",
                "JVM approximation used because the Python Markov service was unavailable or disabled.",
                reason,
                latencyMs
        );
    }
}
