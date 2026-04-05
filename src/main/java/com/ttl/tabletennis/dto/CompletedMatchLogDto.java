package com.ttl.tabletennis.dto;

public record CompletedMatchLogDto(Long matchId,
                                   String eventName,
                                   String matchDateIso,
                                   String startTimeIso,
                                   String player1Name,
                                   String player2Name,
                                   String winnerName,
                                   String loserName,
                                   String score,
                                   boolean picked,
                                   String pickStatus) {
}
