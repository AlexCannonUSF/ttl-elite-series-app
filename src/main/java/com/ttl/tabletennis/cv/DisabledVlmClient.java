package com.ttl.tabletennis.cv;

import java.time.Duration;

public class DisabledVlmClient implements VlmClient {

    public static final String ENGINE_ID = "disabled";

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public VlmScoreReadingResult readScoreboard(VlmRequest request) {
        return VlmScoreReadingResult.unreadable("vlm-disabled", Duration.ZERO, 0, 0, 0.0);
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
