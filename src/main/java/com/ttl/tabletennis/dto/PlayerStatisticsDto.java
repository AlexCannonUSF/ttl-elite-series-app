package com.ttl.tabletennis.dto;

public record PlayerStatisticsDto(Long playerId,
                                  String playerName,
                                  long wins,
                                  long losses,
                                  long matches,
                                  double winPct) {
}
