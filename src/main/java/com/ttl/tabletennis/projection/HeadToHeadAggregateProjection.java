package com.ttl.tabletennis.projection;

public interface HeadToHeadAggregateProjection {
    Long getPlayer1Wins();

    Long getPlayer2Wins();

    Long getTotalMatches();
}
