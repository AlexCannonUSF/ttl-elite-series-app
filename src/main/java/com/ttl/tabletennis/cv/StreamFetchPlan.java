package com.ttl.tabletennis.cv;

import java.util.List;

public record StreamFetchPlan(StreamRouteResolution route,
                              int sampleRateFps,
                              List<String> ytDlpCommand,
                              List<String> ffmpegCommand) {

    public StreamFetchPlan {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        sampleRateFps = Math.max(1, Math.min(2, sampleRateFps));
        ytDlpCommand = List.copyOf(ytDlpCommand == null ? List.of() : ytDlpCommand);
        ffmpegCommand = List.copyOf(ffmpegCommand == null ? List.of() : ffmpegCommand);
    }
}
