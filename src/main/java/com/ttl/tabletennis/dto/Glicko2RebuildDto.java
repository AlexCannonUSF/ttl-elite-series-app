package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record Glicko2RebuildDto(LocalDate fromDate,
                                LocalDate toDate,
                                int periodsProcessed,
                                long playersProcessed,
                                long snapshotsWritten,
                                double tau) {
}
