package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record TrueSkill2RatingDto(Long playerId,
                                  String playerName,
                                  LocalDate snapshotDate,
                                  double mu,
                                  double sigma,
                                  double conservativeSkill,
                                  long matchesSeen,
                                  long wins,
                                  long losses,
                                  LocalDate lastMatchDate) {
}
