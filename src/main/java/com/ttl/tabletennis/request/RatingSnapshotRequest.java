package com.ttl.tabletennis.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record RatingSnapshotRequest(@NotNull Long playerId,
                                    @NotNull LocalDate snapshotDate,
                                    @Positive double rating,
                                    Double ratingDeviation,
                                    Double volatility,
                                    String ratingSystem) {
}
