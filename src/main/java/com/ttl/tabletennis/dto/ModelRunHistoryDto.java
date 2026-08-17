package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Immutable summary of one paper/model run for operator comparison. */
public record ModelRunHistoryDto(LocalDateTime generatedAt,
                                 List<Run> runs) {

    public record Run(Long sessionId,
                      String label,
                      String status,
                      String requestedModelVersion,
                      String effectiveModelVersion,
                      String effectiveModelFamily,
                      String effectiveArtifactChecksum,
                      String featureSchemaChecksum,
                      String calibrationId,
                      String policyVersion,
                      String codeRevision,
                      LocalDateTime createdAt,
                      LocalDateTime closedAt,
                      LocalDateTime lastSyncAt,
                      long modelCalls,
                      int totalBets,
                      int openBets,
                      int settledBets,
                      int wins,
                      int losses,
                      int pushes,
                      int voids,
                      double totalStaked,
                      double realizedPnl,
                      double roiPct,
                      double sampleReadinessPct,
                      String frozenRunSummaryChecksum) {
    }
}
