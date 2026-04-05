package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;

public record ValueOpportunityDto(Long id,
                                  String source,
                                  String strategy,
                                  String modelVersion,
                                  Long player1Id,
                                  Long player2Id,
                                  Long playerSideId,
                                  String playerSideName,
                                  double modelProbability,
                                  double confidenceLow,
                                  double confidenceHigh,
                                  double impliedProbability,
                                  double edge,
                                  double threshold,
                                  int americanOdds,
                                  LocalDateTime createdAt) {
}
