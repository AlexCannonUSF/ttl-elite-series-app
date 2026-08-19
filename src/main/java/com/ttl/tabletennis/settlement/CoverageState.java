package com.ttl.tabletennis.settlement;

public enum CoverageState {
    FULL,
    PARTIAL,
    DARK;

    public boolean hasCoverage() {
        return this != DARK;
    }
}
