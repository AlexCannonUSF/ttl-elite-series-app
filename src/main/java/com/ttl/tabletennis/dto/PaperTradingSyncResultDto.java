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
                                        PaperTradingSessionDto session,
                                        String status,
                                        String message) {

    public PaperTradingSyncResultDto(String strategy,
                                     String modelVersion,
                                     int rowsScanned,
                                     int betsPlaced,
                                     int betsSkipped,
                                     int betsSettled,
                                     int betsVoided,
                                     LocalDateTime syncedAt,
                                     PaperTradingSessionDto session) {
        this(
                strategy,
                modelVersion,
                rowsScanned,
                betsPlaced,
                betsSkipped,
                betsSettled,
                betsVoided,
                syncedAt,
                session,
                "COMPLETED",
                null
        );
    }
}
