package com.ttl.tabletennis.settlement;

import java.util.List;
import java.util.Objects;

public record ManualReview(SettlementEvidence evidence,
                           SettlementReason reason,
                           List<Contradiction> contradictions) implements Decision {

    public ManualReview {
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
        contradictions = contradictions == null || contradictions.isEmpty() ? List.of() : List.copyOf(contradictions);
    }
}
