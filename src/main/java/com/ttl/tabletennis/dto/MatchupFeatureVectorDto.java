package com.ttl.tabletennis.dto;

import java.time.LocalDate;

public record MatchupFeatureVectorDto(Long player1Id,
                                      Long player2Id,
                                      LocalDate asOfDate,
                                      double headToHeadWinRatePlayer1,
                                      double headToHeadWinRatePlayer2,
                                      double headToHeadSampleWeight,
                                      double headToHeadReliability,
                                      PlayerFeatureDto player1,
                                      PlayerFeatureDto player2,
                                      double eloProbabilityPlayer1,
                                      double glickoProbabilityPlayer1,
                                      double trueSkill2ProbabilityPlayer1,
                                      double wengLinProbabilityPlayer1,
                                      double raterEnsembleProbabilityPlayer1,
                                      double raterEnsembleDelta,
                                      ReliabilitySummaryDto reliabilitySummary,
                                      SignificanceSummaryDto significanceSummary,
                                      RatingIntervalDto player1Rating95PctInterval,
                                      RatingIntervalDto player2Rating95PctInterval) {

    public record PlayerFeatureDto(double recentForm,
                                   double opponentAdjustedForm,
                                   double scheduleStrength,
                                   double eloRating,
                                   double glickoRating,
                                   double glickoRatingDeviation,
                                   double glickoVolatility,
                                   double trueSkill2Mu,
                                   double trueSkill2Sigma,
                                   double wengLinRating,
                                   double wengLinUncertainty,
                                   double recentFormSampleWeight,
                                   double opponentAdjustedSampleWeight,
                                   double scheduleStrengthSampleWeight,
                                   double recentFormReliability,
                                   double opponentAdjustedReliability,
                                   double scheduleStrengthReliability,
                                   double ratingStability) {
    }

    public record ReliabilitySummaryDto(double overallReliability,
                                        double ratingAgreement,
                                        double player1BaselineStability,
                                        double player2BaselineStability) {
    }

    public record SignificanceSummaryDto(double sampleDepth,
                                         double headToHeadSupport,
                                         double recentFormSupport,
                                         double opponentAdjustedSupport,
                                         double scheduleStrengthSupport,
                                         double baselineSupport,
                                         int strongSignalCount,
                                         int usableSignalCount,
                                         int thinSignalCount,
                                         String strongestSupportLabel,
                                         double strongestSupportValue,
                                         String weakestSupportLabel,
                                         double weakestSupportValue) {
    }

    public record RatingIntervalDto(double low, double high) {
    }
}
