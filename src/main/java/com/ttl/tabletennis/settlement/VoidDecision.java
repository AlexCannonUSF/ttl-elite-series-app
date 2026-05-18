package com.ttl.tabletennis.settlement;

import java.util.Objects;

public record VoidDecision(SettlementEvidence evidence,
                           SettlementReason reason) implements Decision {

    public VoidDecision {
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }
}
