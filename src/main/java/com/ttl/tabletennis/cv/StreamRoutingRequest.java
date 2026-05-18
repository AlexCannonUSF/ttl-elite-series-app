package com.ttl.tabletennis.cv;

public record StreamRoutingRequest(String matchId,
                                   String eventCode,
                                   String tableNumber,
                                   String directStreamUrl,
                                   String hardRockStreamHint,
                                   String roiTemplateId) {

    public StreamRoutingRequest {
        matchId = clean(matchId);
        eventCode = clean(eventCode);
        tableNumber = clean(tableNumber);
        directStreamUrl = clean(directStreamUrl);
        hardRockStreamHint = clean(hardRockStreamHint);
        roiTemplateId = clean(roiTemplateId);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
