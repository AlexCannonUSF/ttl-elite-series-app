package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;

public record TrackedMatchObservationDto(Long id,
                                         Long sessionId,
                                         Long betId,
                                         String eventKey,
                                         String dedupeKey,
                                         String externalEventId,
                                         String source,
                                         String sourceKind,
                                         double sourceConfidence,
                                         boolean displayed,
                                         boolean resulted,
                                         boolean matchCompleted,
                                         String sourceFeedCode,
                                         String sourceFeedEventId,
                                         boolean live,
                                         boolean trackedAfterClose,
                                         String eventName,
                                         String competitionName,
                                         String startTimeIso,
                                         Long player1Id,
                                         String player1Name,
                                         Long player2Id,
                                         String player2Name,
                                         String liveScore,
                                         String matchPhase,
                                         String scoreDetail,
                                         LocalDateTime observedAt) {
}
