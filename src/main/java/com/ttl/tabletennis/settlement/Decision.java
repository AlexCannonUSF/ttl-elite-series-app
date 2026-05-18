package com.ttl.tabletennis.settlement;

public sealed interface Decision permits Settle, HoldOpen, Escalate, VoidDecision, ManualReview {

    SettlementEvidence evidence();

    SettlementReason reason();

    default boolean terminal() {
        return this instanceof Settle || this instanceof VoidDecision || this instanceof ManualReview;
    }
}
