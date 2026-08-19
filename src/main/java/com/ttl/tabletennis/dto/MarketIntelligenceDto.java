package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;
import java.util.List;

public record MarketIntelligenceDto(
        LocalDateTime generatedAt,
        String eventIdentity,
        String primarySource,
        boolean executionAvailable,
        int sourceCount,
        int consensusSourceCount,
        Double consensusPlayer1Probability,
        Double consensusPlayer2Probability,
        Double consensusDispersionPctPoints,
        long freshestQuoteAgeSeconds,
        List<BookLine> books,
        List<HistoryPoint> history,
        List<String> warnings) {

    public record BookLine(String sourceCode, String displayName, String role, boolean executable,
                           boolean authorized, String marketState, LocalDateTime observedAt,
                           long ageSeconds, boolean stale, Double player1DecimalOdds,
                           Double player2DecimalOdds, Integer player1AmericanOdds,
                           Integer player2AmericanOdds, Double player1NoVigProbability,
                           Double player2NoVigProbability, Double overroundPct) { }

    public record HistoryPoint(String sourceCode, LocalDateTime observedAt,
                               Double player1DecimalOdds, Double player2DecimalOdds,
                               String marketState) { }
}
