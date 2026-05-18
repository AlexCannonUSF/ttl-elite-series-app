package com.ttl.tabletennis.settlement;

public record TrackedEventId(String value) {

    public TrackedEventId {
        value = requireText(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
