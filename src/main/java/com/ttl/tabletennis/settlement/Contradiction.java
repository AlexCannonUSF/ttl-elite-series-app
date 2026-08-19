package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.settlement.observation.Observation;

import java.util.Objects;

public record Contradiction(Observation a,
                            Observation b,
                            ContradictionKind kind,
                            double severity) {

    public Contradiction {
        a = Objects.requireNonNull(a, "a must not be null");
        b = Objects.requireNonNull(b, "b must not be null");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        if (severity < 0.0 || severity > 1.0) {
            throw new IllegalArgumentException("severity must be between 0.0 and 1.0");
        }
    }
}
