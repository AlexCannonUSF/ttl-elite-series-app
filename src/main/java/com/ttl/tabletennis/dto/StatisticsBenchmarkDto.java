package com.ttl.tabletennis.dto;

public record StatisticsBenchmarkDto(int iterations,
                                     long players,
                                     long matches,
                                     long optimizedMillis,
                                     long legacyScanMillis,
                                     double speedupX) {
}
