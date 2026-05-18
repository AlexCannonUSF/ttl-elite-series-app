package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.scrape.IngestEvent;
import com.ttl.tabletennis.scrape.SourceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamCvRecognitionTests {

    @TempDir
    Path tempDir;

    @Test
    void boardLocatorLoadsRoiTemplatesAndScalesToFrameSize() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("shadow");
        Path roiRoot = writeRoiTemplate("""
                {
                  "templateId": "test.template.v1",
                  "frameWidth": 1280,
                  "frameHeight": 720,
                  "roi": {"x": 100, "y": 50, "w": 200, "h": 100},
                  "colorProfile": "bright-on-dark",
                  "digitFields": [
                    {"name": "topGames", "rel": [0, 0, 30, 80]},
                    {"name": "topPoints", "rel": [40, 0, 50, 80]},
                    {"name": "botGames", "rel": [100, 0, 30, 80]},
                    {"name": "botPoints", "rel": [140, 0, 50, 80]}
                  ]
                }
                """);
        BoardLocator locator = new BoardLocator(catalog, new RoiTemplateCatalog(roiRoot, new ObjectMapper()));

        FrameSample frame = frameSample("match-1", "match-1:1", 1, 640, 360);
        BoardLocation location = locator.locate(frame, "test.template.v1").orElseThrow();

        assertEquals("test.template.v1", location.templateId());
        assertEquals(new RoiRectangle(50, 25, 100, 50), location.roi());
        assertEquals(640, location.frameWidth());
        assertEquals(360, location.frameHeight());
        assertEquals(BoardLocator.CONFIGURED_TEMPLATE_CONFIDENCE, location.confidence(), 1.0e-9);
        assertTrue(locator.status().detail().contains("loaded 1 templates"));
    }

    @Test
    void boardLocatorStaysDisabledWhenStreamCvFlagIsOff() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("off");
        Path roiRoot = writeRoiTemplate("""
                {
                  "templateId": "test.template.v1",
                  "frameWidth": 1280,
                  "frameHeight": 720,
                  "roi": {"x": 100, "y": 50, "w": 200, "h": 100},
                  "digitFields": [
                    {"name": "topGames", "rel": [0, 0, 30, 80]},
                    {"name": "topPoints", "rel": [40, 0, 50, 80]},
                    {"name": "botGames", "rel": [100, 0, 30, 80]},
                    {"name": "botPoints", "rel": [140, 0, 50, 80]}
                  ]
                }
                """);
        BoardLocator locator = new BoardLocator(catalog, new RoiTemplateCatalog(roiRoot, new ObjectMapper()));

        assertTrue(locator.locate(frameSample("match-1", "match-1:1", 1, 640, 360), "test.template.v1").isEmpty());
        assertFalse(locator.status().enabled());
    }

    @Test
    void paddleOcrAdapterParsesDigitJsonAndRejectsNonDigitText() {
        ObjectMapper objectMapper = new ObjectMapper();

        Optional<DigitOcrRecognition> parsed = PaddleOcrDigitEngine.parseRecognition(
                "topPoints",
                "{\"text\":\"11\",\"confidence\":0.94}",
                "paddle",
                objectMapper
        );
        Optional<DigitOcrRecognition> rejected = PaddleOcrDigitEngine.parseRecognition(
                "topPoints",
                "{\"text\":\"1O\",\"confidence\":0.94}",
                "paddle",
                objectMapper
        );

        assertTrue(parsed.isPresent());
        assertEquals(11, parsed.get().value());
        assertEquals(0.94, parsed.get().confidence(), 1.0e-9);
        assertTrue(rejected.isEmpty());
    }

    @Test
    void classicCvDigitEngineReadsPreparedSingleAndTwoDigitFields() {
        ClassicCvDigitEngine engine = new ClassicCvDigitEngine(true, 0.50);

        Optional<DigitOcrRecognition> singleDigit = engine.recognize("topGames", preparedDigitField("2", 44, 80));
        Optional<DigitOcrRecognition> twoDigits = engine.recognize("topPoints", preparedDigitField("11", 64, 80));

        assertTrue(singleDigit.isPresent());
        assertEquals(2, singleDigit.get().value());
        assertEquals(ClassicCvDigitEngine.READER_NAME, singleDigit.get().reader());
        assertTrue(singleDigit.get().confidence() >= 0.50);

        assertTrue(twoDigits.isPresent());
        assertEquals(11, twoDigits.get().value());
        assertEquals(ClassicCvDigitEngine.READER_NAME, twoDigits.get().reader());
        assertTrue(twoDigits.get().confidence() >= 0.50);
    }

    @Test
    void scoreboardTextReaderCropsDigitFieldsAndBuildsScoreFrame() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("shadow");
        Path roiRoot = writeRoiTemplate("""
                {
                  "templateId": "test.template.v1",
                  "frameWidth": 640,
                  "frameHeight": 360,
                  "roi": {"x": 64, "y": 40, "w": 240, "h": 80},
                  "colorProfile": "bright-on-dark",
                  "digitFields": [
                    {"name": "topGames", "rel": [0, 0, 40, 80]},
                    {"name": "topPoints", "rel": [44, 0, 60, 80]},
                    {"name": "botGames", "rel": [120, 0, 40, 80]},
                    {"name": "botPoints", "rel": [164, 0, 60, 80]}
                  ]
                }
                """);
        ScriptedDigitOcrEngine engine = new ScriptedDigitOcrEngine(Map.of(
                "topGames", 1,
                "topPoints", 9,
                "botGames", 2,
                "botPoints", 7
        ));
        ScoreboardTextReader reader = new ScoreboardTextReader(
                catalog,
                new RoiTemplateCatalog(roiRoot, new ObjectMapper()),
                List.of(engine)
        );
        FrameSample frame = frameSample("match-1", "match-1:9", 9, 640, 360);
        BoardLocation location = new BoardLocation(
                "match-1:9",
                "test.template.v1",
                new RoiRectangle(64, 40, 240, 80),
                640,
                360,
                0.80
        );

        StreamScoreFrame scoreFrame = reader.read(frame, location).orElseThrow();

        assertEquals(score(1, 2, 9, 7), scoreFrame.score());
        assertEquals(0.93, scoreFrame.confidence(), 1.0e-9);
        assertEquals("scripted-paddle", scoreFrame.reader());
        assertEquals(4, engine.seenFieldSizes.size());
        assertTrue(engine.seenFieldSizes.values().stream().allMatch(size -> size.equals("64x32")));
        assertTrue(reader.status().detail().contains("1 configured OCR engine"));
    }

    @Test
    void scoreboardTextReaderFallsBackToClassicCvWhenPrimaryOcrMisses() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("shadow");
        Path roiRoot = writeRoiTemplate("""
                {
                  "templateId": "test.template.v1",
                  "frameWidth": 640,
                  "frameHeight": 360,
                  "roi": {"x": 64, "y": 40, "w": 240, "h": 80},
                  "colorProfile": "bright-on-dark",
                  "digitFields": [
                    {"name": "topGames", "rel": [0, 0, 40, 80]},
                    {"name": "topPoints", "rel": [44, 0, 60, 80]},
                    {"name": "botGames", "rel": [120, 0, 40, 80]},
                    {"name": "botPoints", "rel": [164, 0, 60, 80]}
                  ]
                }
                """);
        ScoreboardTextReader reader = new ScoreboardTextReader(
                catalog,
                new RoiTemplateCatalog(roiRoot, new ObjectMapper()),
                List.of(new EmptyDigitOcrEngine(), new ClassicCvDigitEngine(true, 0.50))
        );
        FrameSample frame = scoreboardFrame("match-1", "match-1:11", 11, Map.of(
                "topGames", "1",
                "topPoints", "11",
                "botGames", "0",
                "botPoints", "9"
        ));
        BoardLocation location = new BoardLocation(
                "match-1:11",
                "test.template.v1",
                new RoiRectangle(64, 40, 240, 80),
                640,
                360,
                0.80
        );

        StreamScoreFrame scoreFrame = reader.read(frame, location).orElseThrow();

        assertEquals(score(1, 0, 11, 9), scoreFrame.score());
        assertEquals(ClassicCvDigitEngine.READER_NAME, scoreFrame.reader());
        assertTrue(scoreFrame.confidence() >= 0.50);
        assertTrue(reader.status().detail().contains("classic CV fallback"));
    }

    @Test
    void scoreboardTextReaderStaysDisabledWhenStreamCvFlagIsOff() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("off");
        Path roiRoot = writeRoiTemplate("""
                {
                  "templateId": "test.template.v1",
                  "frameWidth": 640,
                  "frameHeight": 360,
                  "roi": {"x": 64, "y": 40, "w": 240, "h": 80},
                  "digitFields": [
                    {"name": "topGames", "rel": [0, 0, 40, 80]},
                    {"name": "topPoints", "rel": [44, 0, 60, 80]},
                    {"name": "botGames", "rel": [120, 0, 40, 80]},
                    {"name": "botPoints", "rel": [164, 0, 60, 80]}
                  ]
                }
                """);
        ScoreboardTextReader reader = new ScoreboardTextReader(
                catalog,
                new RoiTemplateCatalog(roiRoot, new ObjectMapper()),
                List.of(new ScriptedDigitOcrEngine(Map.of(
                        "topGames", 1,
                        "topPoints", 9,
                        "botGames", 2,
                        "botPoints", 7
                )))
        );

        Optional<StreamScoreFrame> result = reader.read(
                frameSample("match-1", "match-1:9", 9, 640, 360),
                new BoardLocation("match-1:9", "test.template.v1", new RoiRectangle(64, 40, 240, 80), 640, 360, 0.80)
        );

        assertTrue(result.isEmpty());
        assertFalse(reader.status().enabled());
    }

    @Test
    void scoreStateMachineAcceptsProgressRejectsBackwardsAndAllowsSingleFrameMiss() {
        ScoreStateMachine machine = new ScoreStateMachine(5);

        assertEquals(ScoreFrameTransition.TransitionKind.ACCEPT, machine.ingest(scoreFrame(1, score(0, 0, 0, 0))).kind());
        assertEquals(ScoreFrameTransition.TransitionKind.ACCEPT, machine.ingest(scoreFrame(2, score(0, 0, 1, 0))).kind());
        assertEquals(ScoreFrameTransition.TransitionKind.REVISE, machine.ingest(scoreFrame(3, score(0, 0, 3, 0))).kind());
        assertEquals(ScoreFrameTransition.TransitionKind.REJECT, machine.ingest(scoreFrame(4, score(0, 0, 2, 0))).kind());

        assertEquals(score(0, 0, 3, 0), machine.lastAccepted().orElseThrow().score());
    }

    @Test
    void scoreStateMachineAcceptsValidGameFlipOnlyAfterGamePoint() {
        ScoreStateMachine machine = new ScoreStateMachine(5);

        machine.ingest(scoreFrame(1, score(0, 0, 10, 8)));
        assertEquals(ScoreFrameTransition.TransitionKind.REJECT, machine.ingest(scoreFrame(2, score(1, 0, 0, 0))).kind());

        machine.ingest(scoreFrame(3, score(0, 0, 11, 8)));
        assertEquals(ScoreFrameTransition.TransitionKind.ACCEPT, machine.ingest(scoreFrame(4, score(1, 0, 0, 0))).kind());
    }

    @Test
    void consensusEmitsAfterThreeAgreedFramesAndBuildsStreamFrameEvent() {
        ScoreStateMachine machine = new ScoreStateMachine(5);
        StreamFrameConsensus consensus = new StreamFrameConsensus();

        Optional<StreamFrameObservationPayload> first = consensus.ingest(machine.ingest(scoreFrame(1, score(0, 0, 4, 2))));
        Optional<StreamFrameObservationPayload> second = consensus.ingest(machine.ingest(scoreFrame(2, score(0, 0, 4, 2))));
        Optional<StreamFrameObservationPayload> third = consensus.ingest(machine.ingest(scoreFrame(3, score(0, 0, 4, 2))));
        Optional<StreamFrameObservationPayload> duplicate = consensus.ingest(machine.ingest(scoreFrame(4, score(0, 0, 4, 2))));

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        assertTrue(third.isPresent());
        assertTrue(duplicate.isEmpty());

        StreamFrameObservationPayload payload = third.orElseThrow();
        assertEquals(4, payload.topPoints());
        assertEquals(2, payload.botPoints());
        assertEquals("match-1:3", payload.frameId());

        IngestEvent<StreamFrameObservationPayload> event = new StreamFrameEventFactory().event(
                "",
                Instant.parse("2026-04-19T12:00:02Z"),
                payload
        );
        assertEquals(SourceId.STREAM_CV, event.source());
        assertEquals(StreamFrameEventFactory.TOPIC, event.topic());
        assertEquals("match-1:3", event.correlationId());
        assertEquals(payload, event.payload());
    }

    private ScoreTuple score(int topGames, int botGames, int topPoints, int botPoints) {
        return new ScoreTuple(topGames, botGames, topPoints, botPoints, ServerSide.UNKNOWN, ScorePhase.fromPoints(topPoints, botPoints));
    }

    private StreamScoreFrame scoreFrame(long sequence, ScoreTuple score) {
        return new StreamScoreFrame(
                "match-1",
                "match-1:" + sequence,
                sequence,
                Instant.parse("2026-04-19T12:00:00Z").plusSeconds(sequence),
                score,
                0.91,
                "test.template.v1",
                "paddle"
        );
    }

    private FrameSample frameSample(String matchId, String frameId, long sequence, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(width / 12, height / 12, width / 5, height / 8);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new FrameSample(
                matchId,
                frameId,
                sequence,
                Instant.parse("2026-04-19T12:00:00Z"),
                out.toByteArray(),
                out.size()
        );
    }

    private FrameSample scoreboardFrame(String matchId,
                                        String frameId,
                                        long sequence,
                                        Map<String, String> values) throws IOException {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            drawScoreText(graphics, values.get("topGames"), 64, 40, 40, 80);
            drawScoreText(graphics, values.get("topPoints"), 108, 40, 60, 80);
            drawScoreText(graphics, values.get("botGames"), 184, 40, 40, 80);
            drawScoreText(graphics, values.get("botPoints"), 228, 40, 60, 80);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new FrameSample(
                matchId,
                frameId,
                sequence,
                Instant.parse("2026-04-19T12:00:00Z"),
                out.toByteArray(),
                out.size()
        );
    }

    private BufferedImage preparedDigitField(String text, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            drawScoreText(graphics, text, 0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return ScoreboardImagePreprocessor.preprocess(image, "bright-on-dark");
    }

    private void drawScoreText(Graphics2D graphics, String text, int x, int y, int width, int height) {
        graphics.setColor(Color.WHITE);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(22, height - 18)));
        FontMetrics metrics = graphics.getFontMetrics();
        String safeText = text == null ? "" : text;
        int textX = x + Math.max(0, (width - metrics.stringWidth(safeText)) / 2);
        int textY = y + Math.max(metrics.getAscent(), ((height - metrics.getHeight()) / 2) + metrics.getAscent());
        graphics.drawString(safeText, textX, textY);
    }

    private Path writeRoiTemplate(String json) throws IOException {
        Path root = tempDir.resolve("roi");
        Path templateDir = root.resolve("test.template.v1");
        Files.createDirectories(templateDir);
        Files.writeString(templateDir.resolve("roi.json"), json);
        return root;
    }

    private FeatureFlagCatalog featureCatalogWithStreamCv(String state) throws IOException {
        Path catalogPath = tempDir.resolve("features-" + state + ".yaml");
        Files.writeString(catalogPath, """
                schema_version: 1
                features:
                  "features.stream-cv":
                    owner: "Alex"
                    expires_on: "2026-07-15"
                    state: "%s"
                    description: "Enables Stream-CV workers."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "on"
                """.formatted(state));
        return new FeatureFlagCatalog(catalogPath.toString());
    }

    private static class ScriptedDigitOcrEngine implements DigitOcrEngine {

        private final Map<String, Integer> values;
        private final Map<String, String> seenFieldSizes = new HashMap<>();

        ScriptedDigitOcrEngine(Map<String, Integer> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public String readerName() {
            return "scripted-paddle";
        }

        @Override
        public Optional<DigitOcrRecognition> recognize(String fieldName, BufferedImage fieldImage) {
            seenFieldSizes.put(fieldName, fieldImage.getWidth() + "x" + fieldImage.getHeight());
            Integer value = values.get(fieldName);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(new DigitOcrRecognition(fieldName, value, String.valueOf(value), 0.93, readerName()));
        }
    }

    private static class EmptyDigitOcrEngine implements DigitOcrEngine {

        @Override
        public String readerName() {
            return "empty-primary";
        }

        @Override
        public Optional<DigitOcrRecognition> recognize(String fieldName, BufferedImage fieldImage) {
            return Optional.empty();
        }
    }
}
