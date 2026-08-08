package com.ttl.tabletennis.dto;

import java.util.List;

public record ModelCallScorecardDto(Long sessionId,
                                    String sessionLabel,
                                    String generatedAt,
                                    int totalCalls,
                                    int awaitingResult,
                                    int settledCalls,
                                    int correct,
                                    int incorrect,
                                    int noLean,
                                    double accuracyPct,
                                    int pregameSettled,
                                    int pregameCorrect,
                                    double pregameAccuracyPct,
                                    int liveFirstSettled,
                                    int liveFirstCorrect,
                                    double liveFirstAccuracyPct,
                                    double averageConfidencePct,
                                    Double brierScore,
                                    List<ModelCallResultDto> recentResults) {
}
