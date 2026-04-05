package com.ttl.tabletennis.dto;

public record LiveStudioIntegrityDto(long trackedObservations,
                                     long boardObservations,
                                     long scoreFeedObservations,
                                     long trackedAfterCloseObservations,
                                     long scoreBackedSettlements,
                                     long targetedCompletionSettlements,
                                     long officialResultSettlements,
                                     long databaseSettlements,
                                     long heuristicSettlements,
                                     long voidedSettlements) {
}
