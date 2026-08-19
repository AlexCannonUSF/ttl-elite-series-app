package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record WengLinMatchupDto(LocalDate asOfDate,
                                double player1WinProbability,
                                double player1RatingDelta,
                                double player1ConservativeRatingDelta,
                                WengLinRatingDto player1,
                                WengLinRatingDto player2) {
}
