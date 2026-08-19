package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamCvScaffoldTests {

    @TempDir
    Path tempDir;

    @Test
    void streamCvComponentsStayDisabledWhileFlagIsOff() throws IOException {
        Path catalogPath = tempDir.resolve("features.yaml");
        Files.writeString(catalogPath, """
                schema_version: 1
                features:
                  "features.stream-cv":
                    owner: "Alex"
                    expires_on: "2026-07-15"
                    state: "off"
                    description: "Enables Stream-CV workers."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "on"
                """);

        FeatureFlagCatalog catalog = new FeatureFlagCatalog(catalogPath.toString());
        StreamRouteCatalog routeCatalog = new StreamRouteCatalog(tempDir.resolve("missing-stream-routes.yaml"));
        StreamRouter router = new StreamRouter(catalog, routeCatalog);
        StreamFetcher fetcher = new StreamFetcher(catalog);
        FrameSampler sampler = new FrameSampler(catalog);

        assertDisabled(router.status(), "StreamRouter");
        assertDisabled(fetcher.status(), "StreamFetcher");
        assertDisabled(sampler.status(), "FrameSampler");
        assertTrue(router.resolve(new StreamRoutingRequest("m1", "WSTT", "1", "", "", "")).isEmpty());
    }

    @Test
    void streamCvComponentsReflectPhase02RolloutStates() throws IOException {
        Path catalogPath = tempDir.resolve("features.yaml");
        Files.writeString(catalogPath, """
                schema_version: 1
                features:
                  "features.stream-cv":
                    owner: "Alex"
                    expires_on: "2026-07-15"
                    state: "shadow"
                    description: "Enables Stream-CV workers."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "on"
                """);

        FeatureFlagCatalog catalog = new FeatureFlagCatalog(catalogPath.toString());
        StreamRouteCatalog routeCatalog = new StreamRouteCatalog(tempDir.resolve("missing-stream-routes.yaml"));

        assertTrue(new StreamRouter(catalog, routeCatalog).status().enabled());
        assertEquals("shadow", new StreamFetcher(catalog).status().rolloutState());
        assertTrue(new FrameSampler(catalog).status().detail().contains("Phase 02"));
    }

    @Test
    void streamRouterResolvesOperatorRouteOverrides() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("shadow");
        Path routesPath = tempDir.resolve("stream_routes.yaml");
        Files.writeString(routesPath, """
                version: 1
                routes:
                  - match: {eventCode: "TTCUP", tableNumber: 1}
                    platform: youtube
                    channelId: "UCTestChannel"
                    roiTemplateId: "ttcup.table1.v2"
                  - match: {eventCode: "WSTT", tableNumber: "*"}
                    platform: ttseries_hls
                    baseUrl: "https://stream.example.test/hls/{tableNumber}.m3u8"
                    roiTemplateId: "wstt.generic.v1"
                """);

        StreamRouter router = new StreamRouter(catalog, new StreamRouteCatalog(routesPath));

        Optional<StreamRouteResolution> ttCup = router.resolve(new StreamRoutingRequest(
                "match-1",
                "TTCUP",
                "1",
                "",
                "",
                ""
        ));
        Optional<StreamRouteResolution> wstt = router.resolve(new StreamRoutingRequest(
                "match-2",
                "WSTT",
                "7",
                "",
                "",
                ""
        ));

        assertTrue(ttCup.isPresent());
        assertEquals(StreamPlatform.YOUTUBE, ttCup.get().platform());
        assertEquals(StreamRouteSource.ROUTE_OVERRIDE, ttCup.get().source());
        assertEquals("ttcup.table1.v2", ttCup.get().roiTemplateId());
        assertEquals("https://www.youtube.com/channel/UCTestChannel/live", ttCup.get().streamUrl());

        assertTrue(wstt.isPresent());
        assertEquals(StreamPlatform.TT_SERIES_HLS, wstt.get().platform());
        assertEquals("https://stream.example.test/hls/7.m3u8", wstt.get().streamUrl());
        assertEquals("wstt.generic.v1", wstt.get().roiTemplateId());
    }

    @Test
    void streamRouteCatalogAcceptsAliasesPlaceholdersAndReportsWarnings() throws IOException {
        System.setProperty("TTL_TEST_ROUTE_HOST", "cdn.example.test");
        try {
            Path routesPath = tempDir.resolve("stream_routes.yaml");
            Files.writeString(routesPath, """
                    version: 1
                    routes:
                      - match: {event_code: "WSTT", table: "*"}
                        platform: ttseries-hls
                        url_template: "https://${TTL_TEST_ROUTE_HOST:fallback.example}/hls/{tableNumber}.m3u8"
                        roi_template_id: "wstt.generic.v1"
                      - eventCode: "WSTT"
                        tableNumber: "*"
                        streamUrl: "https://duplicate.example/live.m3u8"
                        roiTemplateId: "duplicate.template"
                      - match: {eventCode: "BAD", tableNumber: 1}
                        platform: youtube
                        roiTemplateId: "missing.url"
                    """);

            StreamRouteCatalog routeCatalog = new StreamRouteCatalog(routesPath);

            assertEquals(1, routeCatalog.routes().size());
            assertEquals(2, routeCatalog.warnings().size());
            assertTrue(routeCatalog.warnings().stream().anyMatch(warning -> warning.contains("duplicate route key WSTT:*")));
            assertTrue(routeCatalog.warnings().stream().anyMatch(warning -> warning.contains("no stream URL")));
            assertEquals("https://cdn.example.test/hls/7.m3u8",
                    routeCatalog.routes().get(0).resolvedStreamUrl("7").orElseThrow());
        } finally {
            System.clearProperty("TTL_TEST_ROUTE_HOST");
        }
    }

    @Test
    void directAndHardRockStreamHintsWinBeforeRouteOverrides() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("shadow");
        Path routesPath = tempDir.resolve("stream_routes.yaml");
        Files.writeString(routesPath, """
                version: 1
                routes:
                  - match: {eventCode: "TTCUP", tableNumber: 1}
                    platform: youtube
                    channelId: "UCTestChannel"
                    roiTemplateId: "ttcup.table1.v2"
                """);

        StreamRouter router = new StreamRouter(catalog, new StreamRouteCatalog(routesPath));

        StreamRouteResolution direct = router.resolve(new StreamRoutingRequest(
                "match-1",
                "TTCUP",
                "1",
                "https://youtu.be/direct-stream",
                "https://twitch.tv/hardrock-hint",
                "custom.roi"
        )).orElseThrow();

        StreamRouteResolution hardRock = router.resolve(new StreamRoutingRequest(
                "match-2",
                "UNKNOWN",
                "1",
                "",
                "https://twitch.tv/hardrock-hint",
                "hint.roi"
        )).orElseThrow();

        assertEquals(StreamRouteSource.DETAIL_PAGE, direct.source());
        assertEquals(StreamPlatform.YOUTUBE, direct.platform());
        assertEquals("custom.roi", direct.roiTemplateId());
        assertNotEquals("https://www.youtube.com/channel/UCTestChannel/live", direct.streamUrl());

        assertEquals(StreamRouteSource.HARD_ROCK_HINT, hardRock.source());
        assertEquals(StreamPlatform.TWITCH, hardRock.platform());
        assertEquals("hint.roi", hardRock.roiTemplateId());
    }

    @Test
    void streamFetcherBuildsYtDlpAndFfmpegPlan() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("shadow");
        StreamFetcher fetcher = new StreamFetcher(catalog);
        StreamRouteResolution route = new StreamRouteResolution(
                "match-1",
                StreamPlatform.TT_SERIES_HLS,
                "https://stream.example.test/hls/1.m3u8",
                "wstt.generic.v1",
                StreamRouteSource.ROUTE_OVERRIDE,
                "WSTT:*",
                Instant.parse("2026-04-19T12:00:00Z")
        );

        StreamFetchPlan plan = fetcher.plan(route, 2).orElseThrow();

        assertEquals(2, plan.sampleRateFps());
        assertEquals("yt-dlp", plan.ytDlpCommand().get(0));
        assertTrue(plan.ytDlpCommand().contains("--hls-use-mpegts"));
        assertTrue(plan.ytDlpCommand().contains(StreamFetcher.TT_SERIES_USER_AGENT));
        assertEquals("ffmpeg", plan.ffmpegCommand().get(0));
        assertTrue(plan.ffmpegCommand().contains(FrameSampler.ffmpegVideoFilter(2)));
    }

    @Test
    void frameSamplerSplitsMjpegPipeIntoTimestampedSamples() throws IOException {
        FeatureFlagCatalog catalog = featureCatalogWithStreamCv("shadow");
        FrameSampler sampler = new FrameSampler(catalog);
        byte[] pipeBytes = {
                0x01, 0x02,
                (byte) 0xff, (byte) 0xd8, 0x11, 0x12, (byte) 0xff, (byte) 0xd9,
                0x55,
                (byte) 0xff, (byte) 0xd8, 0x21, 0x22, (byte) 0xff, (byte) 0xd9
        };

        List<FrameSample> samples = sampler.samplesFromMjpegStream(
                new ByteArrayInputStream(pipeBytes),
                new FrameSamplingRequest("match-1", 1, 10L, Instant.parse("2026-04-19T12:00:00Z")),
                10
        );

        assertEquals(2, samples.size());
        assertEquals("match-1:10", samples.get(0).frameId());
        assertEquals("match-1:11", samples.get(1).frameId());
        assertEquals(Instant.parse("2026-04-19T12:00:01Z"), samples.get(1).capturedAtUtc());
        assertEquals(6, samples.get(0).sourceByteCount());
        assertEquals("fps=1,scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black",
                FrameSampler.ffmpegVideoFilter(1));
    }

    private static void assertDisabled(StreamCvComponentStatus status, String component) {
        assertEquals(component, status.component());
        assertEquals("off", status.rolloutState());
        assertFalse(status.enabled());
        assertTrue(status.detail().contains("Phase"));
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
}
