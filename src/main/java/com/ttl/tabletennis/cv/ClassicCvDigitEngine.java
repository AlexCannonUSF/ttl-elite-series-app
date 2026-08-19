package com.ttl.tabletennis.cv;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Order(200)
public class ClassicCvDigitEngine implements DigitOcrEngine {

    static final String READER_NAME = "classic-cv";
    static final int NORMALIZED_WIDTH = ScoreboardImagePreprocessor.OCR_WIDTH;
    static final int NORMALIZED_HEIGHT = ScoreboardImagePreprocessor.OCR_HEIGHT;

    private static final int MIN_INK_PIXELS = 8;
    private static final int MAX_DIGITS = 2;
    private static final int TEMPLATE_PADDING = 3;

    private final boolean enabled;
    private final double minConfidence;
    private final Map<Integer, boolean[]> digitTemplates;

    @Autowired
    public ClassicCvDigitEngine(@Value("${ttl.streamCv.classicCv.enabled:true}") boolean enabled,
                                @Value("${ttl.streamCv.classicCv.minConfidence:0.62}") double minConfidence) {
        this.enabled = enabled;
        this.minConfidence = Math.max(0.0, Math.min(1.0, minConfidence));
        this.digitTemplates = buildTemplates();
    }

    @Override
    public String readerName() {
        return READER_NAME;
    }

    @Override
    public Optional<DigitOcrRecognition> recognize(String fieldName, BufferedImage fieldImage) {
        if (!enabled || fieldImage == null) {
            return Optional.empty();
        }
        return classifyField(fieldImage)
                .filter(candidate -> candidate.confidence() >= minConfidence)
                .filter(candidate -> candidate.value() <= 99)
                .map(candidate -> new DigitOcrRecognition(
                        fieldName,
                        candidate.value(),
                        candidate.rawText(),
                        candidate.confidence(),
                        readerName()
                ));
    }

    Optional<FieldCandidate> classifyField(BufferedImage fieldImage) {
        Optional<BoundingBox> inkBox = inkBounds(fieldImage, 0, fieldImage.getWidth() - 1);
        if (inkBox.isEmpty() || inkBox.get().inkPixels() < MIN_INK_PIXELS) {
            return Optional.empty();
        }

        List<ColumnRun> runs = digitRuns(fieldImage, inkBox.get());
        if (runs.isEmpty() || runs.size() > MAX_DIGITS) {
            return Optional.empty();
        }

        StringBuilder raw = new StringBuilder();
        double confidence = 1.0;
        for (ColumnRun run : runs) {
            Optional<DigitCandidate> candidate = classifyDigit(fieldImage, run);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            raw.append(candidate.get().digit());
            confidence = Math.min(confidence, candidate.get().confidence());
        }

        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FieldCandidate(Integer.parseInt(raw.toString()), raw.toString(), confidence));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private Optional<DigitCandidate> classifyDigit(BufferedImage fieldImage, ColumnRun run) {
        Optional<BoundingBox> glyphBounds = inkBounds(fieldImage, run.startX(), run.endX());
        if (glyphBounds.isEmpty() || glyphBounds.get().inkPixels() < MIN_INK_PIXELS) {
            return Optional.empty();
        }
        if (isNarrowOne(glyphBounds.get())) {
            return Optional.of(new DigitCandidate(1, 0.88));
        }
        boolean[] glyphMask = normalizeGlyph(fieldImage, glyphBounds.get());
        DigitCandidate best = null;
        for (Map.Entry<Integer, boolean[]> entry : digitTemplates.entrySet()) {
            double confidence = similarity(glyphMask, entry.getValue());
            if (best == null || confidence > best.confidence()) {
                best = new DigitCandidate(entry.getKey(), confidence);
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean isNarrowOne(BoundingBox bounds) {
        return bounds.height() >= 10 && bounds.width() <= Math.max(4, (int) Math.round(bounds.height() * 0.42));
    }

    private List<ColumnRun> digitRuns(BufferedImage image, BoundingBox bounds) {
        List<ColumnRun> runs = new ArrayList<>();
        int runStart = -1;
        int emptyColumns = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            int ink = columnInk(image, x, bounds.minY(), bounds.maxY());
            if (ink > 0) {
                if (runStart < 0) {
                    runStart = x;
                }
                emptyColumns = 0;
            } else if (runStart >= 0) {
                emptyColumns++;
                if (emptyColumns > 2) {
                    runs.add(new ColumnRun(runStart, x - emptyColumns));
                    runStart = -1;
                    emptyColumns = 0;
                }
            }
        }
        if (runStart >= 0) {
            runs.add(new ColumnRun(runStart, bounds.maxX()));
        }

        runs = runs.stream()
                .filter(run -> run.width() >= 2)
                .toList();
        if (runs.size() <= MAX_DIGITS) {
            return runs;
        }
        return mergeToTwoRuns(runs);
    }

    private List<ColumnRun> mergeToTwoRuns(List<ColumnRun> runs) {
        List<ColumnRun> merged = new ArrayList<>(runs);
        while (merged.size() > MAX_DIGITS) {
            int mergeAt = 0;
            int smallestGap = Integer.MAX_VALUE;
            for (int i = 0; i < merged.size() - 1; i++) {
                int gap = merged.get(i + 1).startX() - merged.get(i).endX();
                if (gap < smallestGap) {
                    smallestGap = gap;
                    mergeAt = i;
                }
            }
            ColumnRun left = merged.get(mergeAt);
            ColumnRun right = merged.get(mergeAt + 1);
            merged.set(mergeAt, new ColumnRun(left.startX(), right.endX()));
            merged.remove(mergeAt + 1);
        }
        return merged;
    }

    private int columnInk(BufferedImage image, int x, int minY, int maxY) {
        int count = 0;
        for (int y = minY; y <= maxY; y++) {
            if (isInk(image.getRGB(x, y))) {
                count++;
            }
        }
        return count;
    }

    private boolean[] normalizeGlyph(BufferedImage source, BoundingBox bounds) {
        BufferedImage normalized = new BufferedImage(NORMALIZED_WIDTH, NORMALIZED_HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, NORMALIZED_WIDTH, NORMALIZED_HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int sourceWidth = bounds.width();
            int sourceHeight = bounds.height();
            double scale = Math.min(
                    (NORMALIZED_WIDTH - (TEMPLATE_PADDING * 2.0)) / sourceWidth,
                    (NORMALIZED_HEIGHT - (TEMPLATE_PADDING * 2.0)) / sourceHeight
            );
            int width = Math.max(1, (int) Math.round(sourceWidth * scale));
            int height = Math.max(1, (int) Math.round(sourceHeight * scale));
            int x = (NORMALIZED_WIDTH - width) / 2;
            int y = (NORMALIZED_HEIGHT - height) / 2;
            graphics.drawImage(
                    source,
                    x,
                    y,
                    x + width,
                    y + height,
                    bounds.minX(),
                    bounds.minY(),
                    bounds.maxX() + 1,
                    bounds.maxY() + 1,
                    null
            );
        } finally {
            graphics.dispose();
        }
        return mask(normalized);
    }

    private static Map<Integer, boolean[]> buildTemplates() {
        Map<Integer, boolean[]> templates = new LinkedHashMap<>();
        for (int digit = 0; digit <= 9; digit++) {
            templates.put(digit, mask(renderDigitTemplate(digit)));
        }
        return Collections.unmodifiableMap(templates);
    }

    private static BufferedImage renderDigitTemplate(int digit) {
        BufferedImage image = new BufferedImage(NORMALIZED_WIDTH, NORMALIZED_HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, NORMALIZED_WIDTH, NORMALIZED_HEIGHT);
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            FontMetrics metrics = graphics.getFontMetrics();
            String text = String.valueOf(digit);
            int x = (NORMALIZED_WIDTH - metrics.stringWidth(text)) / 2;
            int y = ((NORMALIZED_HEIGHT - metrics.getHeight()) / 2) + metrics.getAscent() - 1;
            graphics.drawString(text, x, y);
        } finally {
            graphics.dispose();
        }
        Optional<BoundingBox> bounds = inkBounds(image, 0, image.getWidth() - 1);
        if (bounds.isEmpty()) {
            return image;
        }
        return normalizeTemplateImage(image, bounds.get());
    }

    private static BufferedImage normalizeTemplateImage(BufferedImage image, BoundingBox bounds) {
        BufferedImage normalized = new BufferedImage(NORMALIZED_WIDTH, NORMALIZED_HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, NORMALIZED_WIDTH, NORMALIZED_HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double scale = Math.min(
                    (NORMALIZED_WIDTH - (TEMPLATE_PADDING * 2.0)) / bounds.width(),
                    (NORMALIZED_HEIGHT - (TEMPLATE_PADDING * 2.0)) / bounds.height()
            );
            int width = Math.max(1, (int) Math.round(bounds.width() * scale));
            int height = Math.max(1, (int) Math.round(bounds.height() * scale));
            int x = (NORMALIZED_WIDTH - width) / 2;
            int y = (NORMALIZED_HEIGHT - height) / 2;
            graphics.drawImage(
                    image,
                    x,
                    y,
                    x + width,
                    y + height,
                    bounds.minX(),
                    bounds.minY(),
                    bounds.maxX() + 1,
                    bounds.maxY() + 1,
                    null
            );
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private static Optional<BoundingBox> inkBounds(BufferedImage image, int minX, int maxX) {
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        int inkPixels = 0;
        int clampedMinX = Math.max(0, minX);
        int clampedMaxX = Math.min(image.getWidth() - 1, maxX);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = clampedMinX; x <= clampedMaxX; x++) {
                if (isInk(image.getRGB(x, y))) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                    inkPixels++;
                }
            }
        }
        if (inkPixels == 0) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(left, top, right, bottom, inkPixels));
    }

    private static boolean[] mask(BufferedImage image) {
        boolean[] mask = new boolean[NORMALIZED_WIDTH * NORMALIZED_HEIGHT];
        for (int y = 0; y < NORMALIZED_HEIGHT; y++) {
            for (int x = 0; x < NORMALIZED_WIDTH; x++) {
                int sourceX = Math.min(x, image.getWidth() - 1);
                int sourceY = Math.min(y, image.getHeight() - 1);
                mask[(y * NORMALIZED_WIDTH) + x] = isInk(image.getRGB(sourceX, sourceY));
            }
        }
        return mask;
    }

    private static double similarity(boolean[] left, boolean[] right) {
        int intersection = 0;
        int leftInk = 0;
        int rightInk = 0;
        for (int i = 0; i < left.length && i < right.length; i++) {
            if (left[i]) {
                leftInk++;
            }
            if (right[i]) {
                rightInk++;
            }
            if (left[i] && right[i]) {
                intersection++;
            }
        }
        if (leftInk == 0 || rightInk == 0) {
            return 0.0;
        }
        return (2.0 * intersection) / (leftInk + rightInk);
    }

    private static boolean isInk(int rgb) {
        Color color = new Color(rgb);
        int grey = (color.getRed() + color.getGreen() + color.getBlue()) / 3;
        return grey < 128;
    }

    record FieldCandidate(int value, String rawText, double confidence) {
    }

    private record DigitCandidate(int digit, double confidence) {
    }

    private record ColumnRun(int startX, int endX) {
        int width() {
            return (endX - startX) + 1;
        }
    }

    private record BoundingBox(int minX, int minY, int maxX, int maxY, int inkPixels) {
        int width() {
            return (maxX - minX) + 1;
        }

        int height() {
            return (maxY - minY) + 1;
        }
    }
}
