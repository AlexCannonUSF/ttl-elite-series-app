package com.ttl.tabletennis.prediction.shadow;

import java.time.Duration;

public class DisabledBlenderClient implements BlenderClient {

    private final String reason;

    public DisabledBlenderClient(String reason) {
        this.reason = reason == null || reason.isBlank() ? "blender-disabled" : reason.trim();
    }

    @Override
    public Result score(BlenderRequest request, Duration timeout) {
        return Result.disabled(reason);
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
