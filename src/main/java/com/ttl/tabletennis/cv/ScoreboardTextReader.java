package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ScoreboardTextReader {

    private static final Set<String> REQUIRED_FIELDS = Set.of("topGames", "topPoints", "botGames", "botPoints");

    private final FeatureFlagCatalog featureFlagCatalog;
    private final RoiTemplateCatalog roiTemplateCatalog;
    private final List<DigitOcrEngine> engines;

    public ScoreboardTextReader(FeatureFlagCatalog featureFlagCatalog,
                                RoiTemplateCatalog roiTemplateCatalog,
                                List<DigitOcrEngine> engines) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.roiTemplateCatalog = roiTemplateCatalog;
        this.engines = List.copyOf(engines == null ? List.of() : engines);
    }

    public Optional<StreamScoreFrame> read(FrameSample frame, BoardLocation location) {
        if (!featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG)
                || frame == null
                || location == null
                || !frame.frameId().equals(location.frameId())) {
            return Optional.empty();
        }
        Optional<RoiTemplate> template = roiTemplateCatalog.find(location.templateId());
        if (template.isEmpty() || engines.isEmpty()) {
            return Optional.empty();
        }
        return decode(frame).flatMap(image -> readFromImage(frame, location, template.get(), image));
    }

    public StreamCvComponentStatus status() {
        return new StreamCvComponentStatus(
                "ScoreboardTextReader",
                featureFlagCatalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG),
                featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG),
                "Phase 03 OCR chain with PaddleOCR primary and classic CV fallback; "
                        + engines.size() + " configured OCR engine(s)."
        );
    }

    private Optional<StreamScoreFrame> readFromImage(FrameSample frame,
                                                     BoardLocation location,
                                                     RoiTemplate template,
                                                     BufferedImage image) {
        if (!location.roi().fitsWithin(image.getWidth(), image.getHeight())) {
            return Optional.empty();
        }

        Map<String, DigitOcrRecognition> recognitions = new LinkedHashMap<>();
        for (DigitFieldTemplate field : template.digitFields()) {
            if (!REQUIRED_FIELDS.contains(field.name())) {
                continue;
            }
            RoiRectangle fieldRoi = scaledFieldRoi(template, location, field);
            if (!fieldRoi.fitsWithin(image.getWidth(), image.getHeight())) {
                return Optional.empty();
            }
            BufferedImage crop = image.getSubimage(fieldRoi.x(), fieldRoi.y(), fieldRoi.width(), fieldRoi.height());
            BufferedImage prepared = ScoreboardImagePreprocessor.preprocess(crop, template.colorProfile());
            Optional<DigitOcrRecognition> recognition = recognize(field.name(), prepared);
            if (recognition.isEmpty()) {
                return Optional.empty();
            }
            recognitions.put(field.name(), recognition.get());
        }

        if (!recognitions.keySet().containsAll(REQUIRED_FIELDS)) {
            return Optional.empty();
        }
        try {
            ScoreTuple score = new ScoreTuple(
                    recognitions.get("topGames").value(),
                    recognitions.get("botGames").value(),
                    recognitions.get("topPoints").value(),
                    recognitions.get("botPoints").value(),
                    ServerSide.UNKNOWN,
                    ScorePhase.fromPoints(recognitions.get("topPoints").value(), recognitions.get("botPoints").value())
            );
            double confidence = recognitions.values().stream()
                    .mapToDouble(DigitOcrRecognition::confidence)
                    .min()
                    .orElse(0.0);
            String reader = recognitions.values().stream()
                    .map(DigitOcrRecognition::reader)
                    .distinct()
                    .collect(Collectors.joining("+"));
            return Optional.of(new StreamScoreFrame(
                    frame.matchId(),
                    frame.frameId(),
                    frame.sequence(),
                    frame.capturedAtUtc(),
                    score,
                    confidence,
                    template.templateId(),
                    reader
            ));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private RoiRectangle scaledFieldRoi(RoiTemplate template, BoardLocation location, DigitFieldTemplate field) {
        RoiRectangle scaled = field.relativeRoi().scaleFromTemplate(
                template.roi().width(),
                template.roi().height(),
                location.roi().width(),
                location.roi().height()
        );
        return new RoiRectangle(
                location.roi().x() + scaled.x(),
                location.roi().y() + scaled.y(),
                scaled.width(),
                scaled.height()
        );
    }

    private Optional<DigitOcrRecognition> recognize(String fieldName, BufferedImage image) {
        for (DigitOcrEngine engine : engines) {
            Optional<DigitOcrRecognition> recognition = engine.recognize(fieldName, image);
            if (recognition.isPresent()) {
                return recognition;
            }
        }
        return Optional.empty();
    }

    private Optional<BufferedImage> decode(FrameSample frame) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.jpegBytes()));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return Optional.empty();
            }
            return Optional.of(image);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}
