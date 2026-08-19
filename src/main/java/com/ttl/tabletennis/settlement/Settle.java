package com.ttl.tabletennis.settlement;

import java.util.Objects;

public record Settle(SettlementEvidence evidence,
                     long winnerPlayerId,
                     SettlementReason reason,
                     double confidence) implements Decision {

    public Settle {
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
        if (winnerPlayerId < 0L) {
            throw new IllegalArgumentException("winnerPlayerId must not be negative");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
    }
}
