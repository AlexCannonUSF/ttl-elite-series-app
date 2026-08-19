package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;

import java.util.List;
import java.util.Objects;

public record Escalate(SettlementEvidence evidence,
                       SettlementReason reason,
                       List<SourceId> nextSources) implements Decision {

    public Escalate {
        evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        reason = Objects.requireNonNull(reason, "reason must not be null");
        nextSources = nextSources == null || nextSources.isEmpty() ? List.of() : List.copyOf(nextSources);
    }
}
