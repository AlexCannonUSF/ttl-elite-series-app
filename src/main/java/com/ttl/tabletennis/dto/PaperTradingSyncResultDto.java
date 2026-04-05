package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;

public record PaperTradingSyncResultDto(String strategy,
                                        String modelVersion,
                                        int rowsScanned,
                                        int betsPlaced,
                                        int betsSkipped,
                                        int betsSettled,
                                        int betsVoided,
                                        LocalDateTime syncedAt,
                                        PaperTradingSessionDto session) {
}
