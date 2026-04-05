package com.ttl.tabletennis.dto;

public record DuplicatePlayerCandidateDto(Long sourcePlayerId,
                                          String sourcePlayerName,
                                          Long targetPlayerId,
                                          String targetPlayerName,
                                          double similarityScore) {
}
