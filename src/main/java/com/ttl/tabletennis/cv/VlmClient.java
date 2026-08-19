package com.ttl.tabletennis.cv;

public interface VlmClient {

    String engineId();

    VlmScoreReadingResult readScoreboard(VlmRequest request);

    default boolean isEnabled() {
        return true;
    }
}
