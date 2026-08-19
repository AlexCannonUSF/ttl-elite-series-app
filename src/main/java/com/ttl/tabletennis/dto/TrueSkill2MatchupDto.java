package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record TrueSkill2MatchupDto(LocalDate asOfDate,
                                   double player1WinProbability,
                                   double player1MuDelta,
                                   double player1ConservativeSkillDelta,
                                   TrueSkill2RatingDto player1,
                                   TrueSkill2RatingDto player2) {
}
