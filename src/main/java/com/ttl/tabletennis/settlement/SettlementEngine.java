package com.ttl.tabletennis.settlement;

public interface SettlementEngine {

    Decision decide(SettlementEvidence evidence, SettlementPolicy policy);
}
