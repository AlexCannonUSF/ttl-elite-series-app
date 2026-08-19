package com.ttl.tabletennis.cv;

public record BoardLocation(String frameId,
                            String templateId,
                            RoiRectangle roi,
                            int frameWidth,
                            int frameHeight,
                            double confidence) {

    public BoardLocation {
        if (frameId == null || frameId.trim().isEmpty()) {
            throw new IllegalArgumentException("frameId must not be blank");
        }
        frameId = frameId.trim();
        if (templateId == null || templateId.trim().isEmpty()) {
            throw new IllegalArgumentException("templateId must not be blank");
        }
        templateId = templateId.trim();
        if (roi == null || !roi.fitsWithin(frameWidth, frameHeight)) {
            throw new IllegalArgumentException("board ROI must fit inside frame dimensions");
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}
