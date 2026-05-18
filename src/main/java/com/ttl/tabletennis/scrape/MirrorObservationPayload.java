package com.ttl.tabletennis.scrape;

import org.springframework.util.StringUtils;

public record MirrorObservationPayload(String trackedEventId,
                                       String mirrorEventId,
                                       String player1Name,
                                       String player2Name,
                                       String competitionName,
                                       String phase,
                                       Integer gamesP1,
                                       Integer gamesP2,
                                       Integer pointsP1,
                                       Integer pointsP2,
                                       String server,
                                       boolean completionSignal,
                                       String payloadJson) {

    public MirrorObservationPayload {
        trackedEventId = requireText(trackedEventId, "trackedEventId");
        mirrorEventId = safeTrim(mirrorEventId);
        player1Name = safeTrim(player1Name);
        player2Name = safeTrim(player2Name);
        competitionName = safeTrim(competitionName);
        phase = safeTrim(phase);
        server = safeTrim(server);
        payloadJson = requireText(payloadJson, "payloadJson");
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
