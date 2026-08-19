package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

@Component
public class BoardLocator {

    static final double CONFIGURED_TEMPLATE_CONFIDENCE = 0.80;

    private final FeatureFlagCatalog featureFlagCatalog;
    private final RoiTemplateCatalog roiTemplateCatalog;

    public BoardLocator(FeatureFlagCatalog featureFlagCatalog, RoiTemplateCatalog roiTemplateCatalog) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.roiTemplateCatalog = roiTemplateCatalog;
    }

    public Optional<BoardLocation> locate(FrameSample frame, String roiTemplateId) {
        if (!featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG)
                || frame == null
                || roiTemplateId == null
                || roiTemplateId.isBlank()) {
            return Optional.empty();
        }
        Optional<RoiTemplate> template = roiTemplateCatalog.find(roiTemplateId);
        if (template.isEmpty()) {
            return Optional.empty();
        }
        return imageDimensions(frame).flatMap(dimensions -> {
            RoiRectangle scaled = template.get().scaledRoi(dimensions.width(), dimensions.height());
            if (!scaled.fitsWithin(dimensions.width(), dimensions.height())) {
                return Optional.empty();
            }
            return Optional.of(new BoardLocation(
                    frame.frameId(),
                    template.get().templateId(),
                    scaled,
                    dimensions.width(),
                    dimensions.height(),
                    CONFIGURED_TEMPLATE_CONFIDENCE
            ));
        });
    }

    public StreamCvComponentStatus status() {
        return new StreamCvComponentStatus(
                "BoardLocator",
                featureFlagCatalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG),
                featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG),
                "Phase 02 Tier A ROI locator; loaded " + roiTemplateCatalog.templates().size() + " templates."
        );
    }

    private Optional<ImageDimensions> imageDimensions(FrameSample frame) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.jpegBytes()));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return Optional.empty();
            }
            return Optional.of(new ImageDimensions(image.getWidth(), image.getHeight()));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private record ImageDimensions(int width, int height) {
    }
}
