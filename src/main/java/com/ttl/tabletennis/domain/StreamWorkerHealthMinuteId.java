package com.ttl.tabletennis.domain;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

public class StreamWorkerHealthMinuteId implements Serializable {

    private String matchId;
    private LocalDateTime minuteBucketUtc;

    public StreamWorkerHealthMinuteId() {
    }

    public StreamWorkerHealthMinuteId(String matchId, LocalDateTime minuteBucketUtc) {
        this.matchId = matchId;
        this.minuteBucketUtc = minuteBucketUtc;
    }

    public String getMatchId() {
        return matchId;
    }

    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    public LocalDateTime getMinuteBucketUtc() {
        return minuteBucketUtc;
    }

    public void setMinuteBucketUtc(LocalDateTime minuteBucketUtc) {
        this.minuteBucketUtc = minuteBucketUtc;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StreamWorkerHealthMinuteId that)) {
            return false;
        }
        return Objects.equals(matchId, that.matchId)
                && Objects.equals(minuteBucketUtc, that.minuteBucketUtc);
    }

    @Override
    public int hashCode() {
        return Objects.hash(matchId, minuteBucketUtc);
    }
}
