package com.ttl.tabletennis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.cv.BoardLocator;
import com.ttl.tabletennis.cv.FrameSampler;
import com.ttl.tabletennis.cv.RoiTemplateCatalog;
import com.ttl.tabletennis.cv.ScoreboardTextReader;
import com.ttl.tabletennis.cv.StreamCvVlmFallbackHook;
import com.ttl.tabletennis.cv.StreamFetcher;
import com.ttl.tabletennis.cv.StreamRouteCatalog;
import com.ttl.tabletennis.cv.StreamRouter;
import com.ttl.tabletennis.dto.OpsStreamsDto;
import com.ttl.tabletennis.repository.StreamRouteRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsStreamsServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void snapshotListsStreamWorkersAndHookOnlyVlmUsage() throws IOException {
        FeatureFlagCatalog flags = featureCatalog("shadow");
        Path routesPath = tempDir.resolve("stream_routes.yaml");
        Files.writeString(routesPath, """
                version: 1
                routes:
                  - match: {eventCode: "WSTT", tableNumber: "*"}
                    platform: ttseries_hls
                    baseUrl: "https://stream.example.test/hls/{tableNumber}.m3u8"
                    roiTemplateId: "wstt.generic.v1"
                """);
        Path roiRoot = tempDir.resolve("roi");
        Path templateDir = roiRoot.resolve("wstt.generic.v1");
        Files.createDirectories(templateDir);
        Files.writeString(templateDir.resolve("roi.json"), """
                {
                  "templateId": "wstt.generic.v1",
                  "frameWidth": 1280,
                  "frameHeight": 720,
                  "roi": {"x": 100, "y": 80, "w": 420, "h": 160},
                  "colorProfile": "light",
                  "digitFields": [
                    {"name": "topGames", "rel": [10, 10, 40, 40]},
                    {"name": "topPoints", "rel": [60, 10, 40, 40]},
                    {"name": "botGames", "rel": [10, 60, 40, 40]},
                    {"name": "botPoints", "rel": [60, 60, 40, 40]}
                  ]
                }
                """);

        StreamCvVlmFallbackHook hook = new StreamCvVlmFallbackHook(true, 300L);
        hook.forceNextFrame("match-1", "ops", "spot check", Instant.now());

        StreamRouteRecordRepository streamRouteRecordRepository = mock(StreamRouteRecordRepository.class);
        when(streamRouteRecordRepository.count()).thenReturn(2L);
        OpsStreamsService service = service(
                flags,
                new StreamRouteCatalog(routesPath),
                new RoiTemplateCatalog(roiRoot, new ObjectMapper()),
                hook,
                streamRouteRecordRepository
        );

        OpsStreamsDto snapshot = service.snapshot();

        assertNotNull(snapshot.generatedAt());
        assertEquals(6, snapshot.summary().totalWorkers());
        assertEquals(6, snapshot.summary().enabledWorkers());
        assertEquals(0, snapshot.summary().offWorkers());
        assertEquals(2, snapshot.summary().routeOverrides());
        assertEquals(1, snapshot.summary().roiTemplates());
        assertEquals(1, snapshot.summary().activeForceRequests());
        assertEquals("HOOK_ONLY", snapshot.vlmUsage().meteringState());
        assertEquals(0L, snapshot.vlmUsage().framesSentToday());
        assertTrue(snapshot.workers().stream().anyMatch(worker -> "ScoreboardTextReader".equals(worker.component())));
    }

    @Test
    void snapshotMarksWorkersOffWhenStreamCvFlagIsOff() throws IOException {
        FeatureFlagCatalog flags = featureCatalog("off");
        StreamCvVlmFallbackHook hook = new StreamCvVlmFallbackHook(false, 300L);

        OpsStreamsDto snapshot = service(
                flags,
                new StreamRouteCatalog(tempDir.resolve("missing-stream-routes.yaml")),
                new RoiTemplateCatalog(tempDir.resolve("missing-roi"), new ObjectMapper()),
                hook,
                mock(StreamRouteRecordRepository.class)
        ).snapshot();

        assertEquals(6, snapshot.summary().totalWorkers());
        assertEquals(0, snapshot.summary().enabledWorkers());
        assertEquals(6, snapshot.summary().offWorkers());
        assertEquals("OFF", snapshot.vlmUsage().meteringState());
        assertTrue(snapshot.workers().stream().allMatch(worker -> "OFF".equals(worker.status())));
    }

    private OpsStreamsService service(FeatureFlagCatalog flags,
                                      StreamRouteCatalog routeCatalog,
                                      RoiTemplateCatalog roiTemplateCatalog,
                                      StreamCvVlmFallbackHook hook,
                                      StreamRouteRecordRepository streamRouteRecordRepository) {
        return new OpsStreamsService(
                new StreamRouter(flags, routeCatalog),
                new StreamFetcher(flags),
                new FrameSampler(flags),
                new BoardLocator(flags, roiTemplateCatalog),
                new ScoreboardTextReader(flags, roiTemplateCatalog, List.of()),
                hook,
                routeCatalog,
                roiTemplateCatalog,
                streamRouteRecordRepository
        );
    }

    private FeatureFlagCatalog featureCatalog(String state) throws IOException {
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
}
