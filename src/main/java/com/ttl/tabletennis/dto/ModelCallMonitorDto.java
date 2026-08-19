package com.ttl.tabletennis.dto;

import java.util.List;

public record ModelCallMonitorDto(
        Long sessionId,
        String sessionLabel,
        String generatedAt,
        int totalCalls,
        int scheduled,
        int liveTracking,
        int settlementReview,
        int viewerApproved,
        int systemConfirmed,
        int conflicts,
        List<ModelCallTrackingDto> calls) {
}
