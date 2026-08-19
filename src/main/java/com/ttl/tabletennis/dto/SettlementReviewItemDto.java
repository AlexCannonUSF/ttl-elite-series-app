package com.ttl.tabletennis.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Operator-facing explanation of one completed paper-trade settlement.
 *
 * <p>The fields are deliberately normalized rather than exposing another raw
 * audit JSON document.  This lets the admin UI answer the important forensic
 * questions without teaching operators how each settlement implementation
 * happened to serialize its evidence.</p>
 */
public record SettlementReviewItemDto(Long betId,
                                      Long sessionId,
                                      String status,
                                      String eventName,
                                      String competitionName,
                                      String player1Name,
                                      String player2Name,
                                      String selectedSide,
                                      Long winnerPlayerId,
                                      String winnerName,
                                      String settlementSource,
                                      String settlementReason,
                                      LocalDateTime settledAt,
                                      Long selectedCandidateMatchId,
                                      LocalDate selectedCandidateDate,
                                      Double playerSetConfidence,
                                      Boolean feedIdentityMatch,
                                      Double archiveConfidence,
                                      boolean selectedCandidateInRecentCompleted,
                                      int recentCompletedCandidateCount,
                                      int sameDayCandidateCount,
                                      String lastObservedScore,
                                      String lastObservedPhase,
                                      Long lateScoreDirectionPlayerId,
                                      String lateScoreDirectionName,
                                      String scoreEvidenceQuality,
                                      String scoreEvidenceFinality,
                                      Double scoreEvidenceConfidence,
                                      Integer scoreEvidenceObservationCount,
                                      Integer scoreEvidenceSourceCount,
                                      Integer scoreEvidenceAgreeingSources,
                                      Integer scoreEvidenceCompletionSignals,
                                      Long evidenceId,
                                      String coverageState,
                                      Double ambiguityScore,
                                      Double settlementConfidence,
                                      String trustBand,
                                      boolean suspicious,
                                      List<String> suspicionFlags,
                                      List<String> contradictionFlags,
                                      String explanation) {
}
