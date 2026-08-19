package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record WengLinRebuildDto(LocalDate fromDate,
                                LocalDate toDate,
                                int daysProcessed,
                                long playersProcessed,
                                long matchesProcessed,
                                long snapshotsWritten,
                                double beta,
                                double learningRate) {
}
