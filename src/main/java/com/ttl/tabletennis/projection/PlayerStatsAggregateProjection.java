package com.ttl.tabletennis.projection;

public interface PlayerStatsAggregateProjection {
    Long getPlayerId();

    String getFirstName();

    String getLastName();

    Long getMatches();

    Long getWins();

    Long getLosses();
}
