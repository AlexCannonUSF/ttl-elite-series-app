package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class StreamFetcher {

    static final String TT_SERIES_USER_AGENT = "TTLElite-StreamCV/3.0 (+ops@ttl-elite.local)";
    static final int DEFAULT_SAMPLE_RATE_FPS = 1;

    private final FeatureFlagCatalog featureFlagCatalog;

    public StreamFetcher(FeatureFlagCatalog featureFlagCatalog) {
        this.featureFlagCatalog = featureFlagCatalog;
    }

    public Optional<StreamFetchPlan> plan(StreamRouteResolution route) {
        return plan(route, DEFAULT_SAMPLE_RATE_FPS);
    }

    public Optional<StreamFetchPlan> plan(StreamRouteResolution route, int sampleRateFps) {
        if (!featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG) || route == null) {
            return Optional.empty();
        }
        int effectiveSampleRate = Math.max(1, Math.min(2, sampleRateFps));
        return Optional.of(new StreamFetchPlan(
                route,
                effectiveSampleRate,
                ytDlpCommand(route),
                ffmpegCommand(effectiveSampleRate)
        ));
    }

    public StreamCvComponentStatus status() {
        return new StreamCvComponentStatus(
                "StreamFetcher",
                featureFlagCatalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG),
                featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG),
                "Phase 02 fetch planner for yt-dlp -> ffmpeg image2pipe workers."
        );
    }

    private List<String> ytDlpCommand(StreamRouteResolution route) {
        List<String> command = new ArrayList<>(List.of(
                "yt-dlp",
                "--live-from-start=false",
                "--no-part",
                "-q",
                "-f",
                "best[height<=720]/best",
                "--hls-use-mpegts",
                "--output",
                "-"
        ));
        if (route.platform() == StreamPlatform.TT_SERIES_HLS || route.platform() == StreamPlatform.DIRECT_HLS) {
            command.add("--user-agent");
            command.add(TT_SERIES_USER_AGENT);
        }
        command.add(route.streamUrl());
        return command;
    }

    private List<String> ffmpegCommand(int sampleRateFps) {
        return List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "warning",
                "-i",
                "pipe:0",
                "-vf",
                FrameSampler.ffmpegVideoFilter(sampleRateFps),
                "-f",
                "image2pipe",
                "-vcodec",
                "mjpeg",
                "-q:v",
                "5",
                "-"
        );
    }
}
