package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record TrueSkill2RebuildDto(LocalDate fromDate,
                                   LocalDate toDate,
                                   int daysProcessed,
                                   long playersProcessed,
                                   long matchesProcessed,
                                   long snapshotsWritten,
                                   double beta) {
}
