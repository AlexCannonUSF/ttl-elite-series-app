package com.ttl.tabletennis.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record EloSyncResultDto(boolean success,
                               String sourceUrl,
                               LocalDate snapshotDate,
                               int rankingRows,
                               int matchedPlayers,
                               int snapshotsInserted,
                               int snapshotsUpdated,
                               int unchangedPlayers,
                               int unresolvedPlayers,
                               List<String> unresolvedSample,
                               String message,
                               LocalDateTime syncedAt) {
}
