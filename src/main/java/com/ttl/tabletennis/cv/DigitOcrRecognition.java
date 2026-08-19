package com.ttl.tabletennis.cv;

public record DigitOcrRecognition(String fieldName,
                                  int value,
                                  String rawText,
                                  double confidence,
                                  String reader) {

    public DigitOcrRecognition {
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new IllegalArgumentException("fieldName must not be blank");
        }
        fieldName = fieldName.trim();
        if (value < 0 || value > 99) {
            throw new IllegalArgumentException("OCR digit value must be between 0 and 99");
        }
        rawText = rawText == null ? String.valueOf(value) : rawText.trim();
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reader = reader == null || reader.isBlank() ? "unknown" : reader.trim();
    }
}
