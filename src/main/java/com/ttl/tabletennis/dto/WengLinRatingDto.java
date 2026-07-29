package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record WengLinRatingDto(Long playerId,
                               String playerName,
                               LocalDate snapshotDate,
                               double rating,
                               double uncertainty,
                               double conservativeRating,
                               long matchesSeen,
                               long wins,
                               long losses,
                               LocalDate lastMatchDate) {
}
