package com.ttl.tabletennis.dto;

import java.util.List;

public record MatchupAnalysisDto(PlayerDto player1,
                                 PlayerDto player2,
                                 HeadToHeadStatsDto headToHead,
                                 FormDto player1Form,
                                 FormDto player2Form,
                                 FormDto player1Last50,
                                 FormDto player2Last50,
                                 RecentHeadToHeadDto recentHeadToHead,
                                 RatingDto player1Ratings,
                                 RatingDto player2Ratings,
                                 ProbabilityDto player1Probability,
                                 ProbabilityDto player2Probability,
                                 List<FeatureContributionDto> featureContributions,
                                 ModelComparisonDto modelComparison,
                                 String explanation) {

    public record FormDto(int recentMatches,
                          int recentWins,
                          double recentWinPct,
                          double averageSetMargin,
                          int streak,
                          boolean streakWin) {
    }

    public record ProbabilityDto(double probability,
                                 double confidenceLow,
                                 double confidenceHigh,
                                 int americanOdds) {
    }

    public record RecentHeadToHeadDto(int matches,
                                      int player1Wins,
                                      int player2Wins) {
    }

    public record RatingDto(double elo,
                            double glicko,
                            double glickoDeviation,
                            double trueSkill2,
                            double wengLin,
                            double stability) {
    }

    public record FeatureContributionDto(String feature, double contribution) {
    }

    public record ModelComparisonDto(double baselineProbabilityPlayer1,
                                     double eloProbabilityPlayer1,
                                     double glickoProbabilityPlayer1) {
    }
}
