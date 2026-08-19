package com.ttl.tabletennis.settlement;

import java.util.Objects;

public record HoldOpen(SettlementEvidence evidence,
                       SettlementReason reason,
                       String note) implements Decision {

    public HoldOpen {
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
        note = note == null ? "" : note.trim();
    }
}
