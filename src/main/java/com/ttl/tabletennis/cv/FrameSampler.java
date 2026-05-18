package com.ttl.tabletennis.cv;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class FrameSampler {

    private final FeatureFlagCatalog featureFlagCatalog;

    public FrameSampler(FeatureFlagCatalog featureFlagCatalog) {
        this.featureFlagCatalog = featureFlagCatalog;
    }

    public FrameSamplingRequest request(String matchId) {
        return new FrameSamplingRequest(matchId, 1, 1L, Instant.now());
    }

    public List<FrameSample> samplesFromMjpegStream(InputStream inputStream,
                                                    FrameSamplingRequest request,
                                                    int maxFrames) throws IOException {
        if (!featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG) || inputStream == null || maxFrames <= 0) {
            return List.of();
        }
        byte[] sourceBytes = inputStream.readAllBytes();
        List<FrameSample> samples = new ArrayList<>();
        int searchFrom = 0;
        long sequence = request.firstSequence();
        while (samples.size() < maxFrames) {
            int start = findMarker(sourceBytes, searchFrom, (byte) 0xD8);
            if (start < 0) {
                break;
            }
            int end = findMarker(sourceBytes, start + 2, (byte) 0xD9);
            if (end < 0) {
                break;
            }
            byte[] jpegBytes = Arrays.copyOfRange(sourceBytes, start, end + 2);
            samples.add(sample(request, sequence, jpegBytes));
            sequence++;
            searchFrom = end + 2;
        }
        return List.copyOf(samples);
    }

    public static String ffmpegVideoFilter(int sampleRateFps) {
        int effectiveSampleRate = Math.max(1, Math.min(2, sampleRateFps));
        return "fps=" + effectiveSampleRate
                + ",scale=1280:720:force_original_aspect_ratio=decrease"
                + ",pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black";
    }

    public StreamCvComponentStatus status() {
        return new StreamCvComponentStatus(
                "FrameSampler",
                featureFlagCatalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG),
                featureFlagCatalog.isEnabled(FeatureFlagCatalog.STREAM_CV_FLAG),
                "Phase 02 1 fps MJPEG frame sampler; deuce-safe cap is 2 fps."
        );
    }

    private FrameSample sample(FrameSamplingRequest request, long sequence, byte[] jpegBytes) {
        long offsetMillis = Math.round((sequence - request.firstSequence()) * (1000.0 / request.sampleRateFps()));
        Instant capturedAt = request.capturedAtStart().plus(Duration.ofMillis(offsetMillis));
        String frameId = request.matchId() + ":" + sequence;
        return new FrameSample(request.matchId(), frameId, sequence, capturedAt, jpegBytes, jpegBytes.length);
    }

    private int findMarker(byte[] sourceBytes, int from, byte marker) {
        for (int i = Math.max(0, from); i < sourceBytes.length - 1; i++) {
            if ((sourceBytes[i] & 0xFF) == 0xFF && sourceBytes[i + 1] == marker) {
                return i;
            }
        }
        return -1;
    }
}
