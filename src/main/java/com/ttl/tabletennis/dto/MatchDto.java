package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record MatchDto(Long id,
                       String externalId,
                       LocalDate date,
                       PlayerDto player1,
                       PlayerDto player2,
                       String result,
                       Integer player1SetsWon,
                       Integer player2SetsWon,
                       Long winnerPlayerId,
                       boolean complete) {
}
