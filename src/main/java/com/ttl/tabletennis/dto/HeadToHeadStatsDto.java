package com.ttl.tabletennis.dto;

public record HeadToHeadStatsDto(String player1Name,
                                 String player2Name,
                                 long player1Wins,
                                 long player2Wins,
                                 long totalMatches,
                                 double player1WinPct,
                                 double player2WinPct) {
}
