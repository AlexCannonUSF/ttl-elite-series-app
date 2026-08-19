package com.ttl.tabletennis.settlement.observation;

public enum MatchPhase {
    PREMATCH,
    LIVE_EARLY,
    LIVE_MID,
    LIVE_LATE,
    FINISHED,
    UNKNOWN;

    public boolean isLive() {
        return this == LIVE_EARLY || this == LIVE_MID || this == LIVE_LATE;
    }

    public boolean isFinished() {
        return this == FINISHED;
    }
}
