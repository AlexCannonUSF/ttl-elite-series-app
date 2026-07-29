package com.ttl.tabletennis.prediction.calibration;

/**
 * Mondrian conformal group key per Prediction Engine Spec §8.3:
 * {@code (best_of, is_in_play, is_major_event)}. Encoded form is
 * {@code "best_of|in_play|major_event"} so it can be a Map key and matches
 * the Python writer's encoding exactly.
 */
public record MondrianGroupKey(int bestOf, boolean isInPlay, boolean isMajorEvent) {

    public String encode() {
        return bestOf + "|" + Boolean.toString(isInPlay) + "|" + Boolean.toString(isMajorEvent);
    }

    public static MondrianGroupKey decode(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        String[] parts = key.split("\\|");
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid mondrian group key: " + key);
        }
        return new MondrianGroupKey(
                Integer.parseInt(parts[0].trim()),
                Boolean.parseBoolean(parts[1].trim()),
                Boolean.parseBoolean(parts[2].trim())
        );
    }
}
