package com.ttl.tabletennis.dto;

import java.time.Instant;

public record OpsSettlementDiffRowDto(Long betId,
                                      String diffKind,
                                      String oldReason,
                                      String newReason,
                                      Long oldWinner,
                                      Long newWinner,
                                      Instant decidedAt,
                                      String correlationId) {
}
