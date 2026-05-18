package com.ttl.tabletennis.cv;

public record DigitFieldTemplate(String name, RoiRectangle relativeRoi) {

    public DigitFieldTemplate {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("digit field name must not be blank");
        }
        name = name.trim();
        if (relativeRoi == null) {
            throw new IllegalArgumentException("digit field ROI must not be null");
        }
    }
}
