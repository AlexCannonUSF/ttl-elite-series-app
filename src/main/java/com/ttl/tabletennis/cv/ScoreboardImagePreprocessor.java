package com.ttl.tabletennis.cv;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

public final class ScoreboardImagePreprocessor {

    static final int OCR_WIDTH = 64;
    static final int OCR_HEIGHT = 32;

    private ScoreboardImagePreprocessor() {
    }

    public static BufferedImage preprocess(BufferedImage source, String colorProfile) {
        if (source == null) {
            throw new IllegalArgumentException("source image must not be null");
        }
        boolean invert = colorProfile == null || colorProfile.isBlank()
                || "bright-on-dark".equalsIgnoreCase(colorProfile.trim());
        BufferedImage thresholded = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                Color color = new Color(source.getRGB(x, y));
                int grey = (color.getRed() + color.getGreen() + color.getBlue()) / 3;
                if (invert) {
                    grey = 255 - grey;
                }
                thresholded.setRGB(x, y, grey >= 128 ? Color.WHITE.getRGB() : Color.BLACK.getRGB());
            }
        }

        BufferedImage padded = new BufferedImage(OCR_WIDTH, OCR_HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = padded.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, OCR_WIDTH, OCR_HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double scale = Math.min((OCR_WIDTH - 4.0) / thresholded.getWidth(), (OCR_HEIGHT - 4.0) / thresholded.getHeight());
            int width = Math.max(1, (int) Math.round(thresholded.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(thresholded.getHeight() * scale));
            int x = (OCR_WIDTH - width) / 2;
            int y = (OCR_HEIGHT - height) / 2;
            graphics.drawImage(thresholded, x, y, width, height, null);
        } finally {
            graphics.dispose();
        }
        return padded;
    }
}
