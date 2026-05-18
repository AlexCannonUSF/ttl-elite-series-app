package com.ttl.tabletennis.cv;

import java.util.List;

public record RoiTemplate(String templateId,
                          int frameWidth,
                          int frameHeight,
                          RoiRectangle roi,
                          String colorProfile,
                          List<DigitFieldTemplate> digitFields) {

    public RoiTemplate {
        if (templateId == null || templateId.trim().isEmpty()) {
            throw new IllegalArgumentException("templateId must not be blank");
        }
        templateId = templateId.trim();
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("template frame dimensions must be positive");
        }
        if (roi == null || !roi.fitsWithin(frameWidth, frameHeight)) {
            throw new IllegalArgumentException("template ROI must fit inside the frame");
        }
        colorProfile = colorProfile == null || colorProfile.isBlank()
                ? "bright-on-dark"
                : colorProfile.trim();
        digitFields = List.copyOf(digitFields == null ? List.of() : digitFields);
        if (digitFields.size() < 4) {
            throw new IllegalArgumentException("template must define at least four digit fields");
        }
        for (DigitFieldTemplate field : digitFields) {
            if (!field.relativeRoi().fitsWithin(roi.width(), roi.height())) {
                throw new IllegalArgumentException("digit field " + field.name() + " must fit inside template ROI");
            }
        }
    }

    public RoiRectangle scaledRoi(int actualFrameWidth, int actualFrameHeight) {
        return roi.scaleFromTemplate(frameWidth, frameHeight, actualFrameWidth, actualFrameHeight);
    }
}
