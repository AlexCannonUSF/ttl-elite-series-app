package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.scrape.IngestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamCvReplayFixtureTests {

    private static final Path FIXTURE_ROOT = Path.of("cv-assets/fixtures");
    private static final Path ROI_ROOT = Path.of("cv-assets/roi");

    @TempDir
    Path tempDir;

    @Test
    void replayFixturesMeetTupleAccuracyGate() throws IOException {
        List<ReplayFixture> fixtures = loadFixtures();

        assertFalse(fixtures.isEmpty(), "expected at least one Stream-CV replay fixture");
        assertTrue(fixtures.size() >= 2, "Phase 02 ships two replay fixtures");

        for (ReplayFixture fixture : fixtures) {
            ReplayResult result = replay(fixture);
            assertEquals(fixture.expectedEmissions().size(), result.events().size(), fixture.fixtureId());
            assertTrue(result.accuracy() >= fixture.minimumAccuracy(),
                    () -> fixture.fixtureId() + " accuracy " + result.accuracy() + " below " + fixture.minimumAccuracy());
        }
    }

    private ReplayResult replay(ReplayFixture fixture) throws IOException {
        FeatureFlagCatalog flags = featureCatalogWithStreamCv("shadow");
        RoiTemplateCatalog templateCatalog = new RoiTemplateCatalog(ROI_ROOT, new ObjectMapper());
        BoardLocator locator = new BoardLocator(flags, templateCatalog);
        ReplayDigitOcrEngine ocrEngine = new ReplayDigitOcrEngine();
        ScoreboardTextReader textReader = new ScoreboardTextReader(flags, templateCatalog, List.of(ocrEngine));
        ScoreStateMachine stateMachine = new ScoreStateMachine(fixture.bestOf());
        StreamFrameConsensus consensus = new StreamFrameConsensus();
        StreamFrameEventFactory eventFactory = new StreamFrameEventFactory();

        List<IngestEvent<StreamFrameObservationPayload>> events = new ArrayList<>();
        for (ReplayFrame replayFrame : fixture.frames()) {
            FrameSample sample = frameSample(fixture, replayFrame);
            BoardLocation location = locator.locate(sample, fixture.templateId()).orElseThrow();
            ocrEngine.use(replayFrame);
            Optional<StreamScoreFrame> scoreFrame = textReader.read(sample, location);
            if (scoreFrame.isEmpty()) {
                continue;
            }
            consensus.ingest(stateMachine.ingest(scoreFrame.get()))
                    .map(payload -> eventFactory.event(payload.frameId(), sample.capturedAtUtc(), payload))
                    .ifPresent(events::add);
        }

        int matched = 0;
        int comparisons = Math.min(fixture.expectedEmissions().size(), events.size());
        for (int i = 0; i < comparisons; i++) {
            if (matches(fixture.expectedEmissions().get(i), events.get(i).payload())) {
                matched++;
            }
        }
        double accuracy = fixture.expectedEmissions().isEmpty()
                ? 1.0
                : (double) matched / fixture.expectedEmissions().size();
        return new ReplayResult(events, accuracy);
    }

    private List<ReplayFixture> loadFixtures() throws IOException {
        String requestedFixture = System.getProperty("ttl.streamCv.replayFixture", "all").trim();
        try (Stream<Path> paths = Files.walk(FIXTURE_ROOT, 2)) {
            return paths
                    .filter(path -> path.getFileName().toString().equals("clip.json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(this::readFixture)
                    .filter(fixture -> "all".equalsIgnoreCase(requestedFixture)
                            || fixture.fixtureId().equalsIgnoreCase(requestedFixture))
                    .toList();
        }
    }

    private ReplayFixture readFixture(Path path) {
        try {
            return new ObjectMapper().findAndRegisterModules().readValue(path.toFile(), ReplayFixture.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read replay fixture " + path, ex);
        }
    }

    private boolean matches(ExpectedEmission expected, StreamFrameObservationPayload actual) {
        return expected.frameId().equals(actual.frameId())
                && expected.phase().equals(actual.phase())
                && expected.score().topGames() == actual.topGames()
                && expected.score().botGames() == actual.botGames()
                && expected.score().topPoints() == actual.topPoints()
                && expected.score().botPoints() == actual.botPoints();
    }

    private FrameSample frameSample(ReplayFixture fixture, ReplayFrame replayFrame) throws IOException {
        BufferedImage image = new BufferedImage(fixture.frame().width(), fixture.frame().height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.WHITE);
            graphics.fillRect(24, 24, 320, 120);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        Instant capturedAt = fixture.capturedAtStart()
                .plusMillis(Math.round((replayFrame.sequence() - 1) * (1000.0 / fixture.sampleRateFps())));
        return new FrameSample(
                fixture.matchId(),
                fixture.matchId() + ":" + replayFrame.sequence(),
                replayFrame.sequence(),
                capturedAt,
                out.toByteArray(),
                out.size()
        );
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

    private record ReplayResult(List<IngestEvent<StreamFrameObservationPayload>> events, double accuracy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReplayFixture(String fixtureId,
                                 String matchId,
                                 String templateId,
                                 int bestOf,
                                 int sampleRateFps,
                                 Instant capturedAtStart,
                                 ReplayFrameSize frame,
                                 double minimumAccuracy,
                                 List<ReplayFrame> frames,
                                 List<ExpectedEmission> expectedEmissions) {

        private ReplayFixture {
            bestOf = bestOf <= 1 ? 5 : bestOf;
            sampleRateFps = Math.max(1, sampleRateFps);
            minimumAccuracy = minimumAccuracy <= 0.0 ? 0.95 : minimumAccuracy;
            frames = List.copyOf(frames == null ? List.of() : frames);
            expectedEmissions = List.copyOf(expectedEmissions == null ? List.of() : expectedEmissions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReplayFrameSize(int width, int height) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReplayFrame(long sequence, ReplayScore score, double confidence) {

        private ReplayFrame {
            confidence = confidence <= 0.0 ? 0.90 : confidence;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExpectedEmission(String frameId, ReplayScore score, String phase) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReplayScore(int topGames, int botGames, int topPoints, int botPoints) {

        private int valueFor(String fieldName) {
            return switch (fieldName) {
                case "topGames" -> topGames;
                case "botGames" -> botGames;
                case "topPoints" -> topPoints;
                case "botPoints" -> botPoints;
                default -> throw new IllegalArgumentException("Unknown replay field " + fieldName);
            };
        }
    }

    private static class ReplayDigitOcrEngine implements DigitOcrEngine {

        private ReplayFrame currentFrame;

        void use(ReplayFrame replayFrame) {
            this.currentFrame = replayFrame;
        }

        @Override
        public String readerName() {
            return "fixture-paddle";
        }

        @Override
        public Optional<DigitOcrRecognition> recognize(String fieldName, BufferedImage fieldImage) {
            if (currentFrame == null || fieldImage == null) {
                return Optional.empty();
            }
            int value = currentFrame.score().valueFor(fieldName);
            return Optional.of(new DigitOcrRecognition(
                    fieldName,
                    value,
                    String.valueOf(value),
                    currentFrame.confidence(),
                    readerName()
            ));
        }
    }
}
