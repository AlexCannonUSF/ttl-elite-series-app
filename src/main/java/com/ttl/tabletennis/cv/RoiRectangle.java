package com.ttl.tabletennis.cv;

public record RoiRectangle(int x, int y, int width, int height) {

    public RoiRectangle {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("ROI origin must be non-negative");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("ROI size must be positive");
        }
    }

    public boolean fitsWithin(int frameWidth, int frameHeight) {
        return frameWidth > 0
                && frameHeight > 0
                && x + width <= frameWidth
                && y + height <= frameHeight;
    }

    public RoiRectangle scaleFromTemplate(int templateWidth,
                                          int templateHeight,
                                          int actualWidth,
                                          int actualHeight) {
        if (templateWidth <= 0 || templateHeight <= 0 || actualWidth <= 0 || actualHeight <= 0) {
            return this;
        }
        double scaleX = (double) actualWidth / templateWidth;
        double scaleY = (double) actualHeight / templateHeight;
        return new RoiRectangle(
                Math.max(0, (int) Math.round(x * scaleX)),
                Math.max(0, (int) Math.round(y * scaleY)),
                Math.max(1, (int) Math.round(width * scaleX)),
                Math.max(1, (int) Math.round(height * scaleY))
        );
    }
}
