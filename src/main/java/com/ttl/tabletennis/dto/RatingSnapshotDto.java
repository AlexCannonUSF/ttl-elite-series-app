package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record RatingSnapshotDto(Long id,
                                Long playerId,
                                String playerName,
                                LocalDate snapshotDate,
                                double rating,
                                Double ratingDeviation,
                                Double volatility,
                                Double confidenceLow,
                                Double confidenceHigh,
                                String ratingSystem) {
}
