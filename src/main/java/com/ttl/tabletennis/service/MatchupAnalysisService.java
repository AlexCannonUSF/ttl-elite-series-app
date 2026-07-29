package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.HeadToHeadStatsDto;
import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.mapper.PlayerMapper;
import com.ttl.tabletennis.model.AdvancedPlayerStats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class MatchupAnalysisService {

    private final PlayerService playerService;
    private final StatisticsService statisticsService;
    private final AdvancedStatisticsService advancedStatisticsService;
    private final FeatureService featureService;
    private final PredictionFacade predictionFacade;

    public MatchupAnalysisService(PlayerService playerService,
                                  StatisticsService statisticsService,
                                  AdvancedStatisticsService advancedStatisticsService,
                                  FeatureService featureService,
                                  PredictionFacade predictionFacade) {
        this.playerService = playerService;
        this.statisticsService = statisticsService;
        this.advancedStatisticsService = advancedStatisticsService;
        this.featureService = featureService;
        this.predictionFacade = predictionFacade;
    }

    public MatchupAnalysisDto analyze(Long player1Id, Long player2Id) {
        return analyze(player1Id, player2Id, null);
    }

    public MatchupAnalysisDto analyze(Long player1Id, Long player2Id, String modelFamily) {
        if (player1Id.equals(player2Id)) {
            throw new IllegalArgumentException("Select two different players");
        }

        Player player1 = playerService.getPlayerOrThrow(player1Id);
        Player player2 = playerService.getPlayerOrThrow(player2Id);

        HeadToHeadStatsDto h2h = statisticsService.getHeadToHeadStats(player1Id, player2Id);

        AdvancedPlayerStats p1Recent = advancedStatisticsService.last10(player1);
        AdvancedPlayerStats p2Recent = advancedStatisticsService.last10(player2);
        AdvancedPlayerStats p1Last50 = advancedStatisticsService.last50(player1);
        AdvancedPlayerStats p2Last50 = advancedStatisticsService.last50(player2);
        List<Match> recentHeadToHeadMatches = statisticsService.getRecentMatchesBetweenPlayers(player1, player2, 10);

        double[] baseline = statisticsService.getAdvancedMatchupStatistics(player1, player2);
        MatchupFeatureVectorDto features = featureService.buildMatchupFeatureVector(player1Id, player2Id, null);
        PredictionModelService.PredictionSnapshot prediction = predictionFacade.predict(player1Id, player2Id, null, modelFamily);

        double eloP1 = features.eloProbabilityPlayer1();
        double glickoP1 = features.glickoProbabilityPlayer1();
        double p1Final = clamp01(prediction.player1Probability());
        double p2Final = 1.0 - p1Final;
        double p1Low = clamp01(prediction.player1ConfidenceLow());
        double p1High = clamp01(prediction.player1ConfidenceHigh());
        double p2Low = clamp01(1.0 - p1High);
        double p2High = clamp01(1.0 - p1Low);

        MatchupAnalysisDto.FormDto p1Form = toForm(p1Recent);
        MatchupAnalysisDto.FormDto p2Form = toForm(p2Recent);

        MatchupAnalysisDto.ProbabilityDto p1Probability = new MatchupAnalysisDto.ProbabilityDto(
                p1Final,
                p1Low,
                p1High,
                statisticsService.computeAmericanOdds(p1Final)
        );

        MatchupAnalysisDto.ProbabilityDto p2Probability = new MatchupAnalysisDto.ProbabilityDto(
                p2Final,
                p2Low,
                p2High,
                statisticsService.computeAmericanOdds(p2Final)
        );

        List<MatchupAnalysisDto.FeatureContributionDto> contributions = prediction.featureContributions();

        String explanation = String.format(
                "Model %s favors %s at %.1f%% (calibration: %s).",
                prediction.modelFamily(),
                p1Final >= p2Final ? player1.getName() : player2.getName(),
                Math.max(p1Final, p2Final) * 100,
                prediction.calibrationMethod()
        );

        return new MatchupAnalysisDto(
                PlayerMapper.toDto(player1),
                PlayerMapper.toDto(player2),
                h2h,
                p1Form,
                p2Form,
                toForm(p1Last50),
                toForm(p2Last50),
                recentHeadToHead(recentHeadToHeadMatches, player1Id, player2Id),
                toRatings(features.player1()),
                toRatings(features.player2()),
                p1Probability,
                p2Probability,
                contributions,
                new MatchupAnalysisDto.ModelComparisonDto(baseline[0], eloP1, glickoP1),
                explanation
        );
    }

    private MatchupAnalysisDto.FormDto toForm(AdvancedPlayerStats stats) {
        double winPct = stats.n == 0 ? 0.0 : (double) stats.wins / stats.n;
        return new MatchupAnalysisDto.FormDto(
                stats.n,
                stats.wins,
                winPct,
                stats.setDiffAvg,
                stats.streak,
                stats.streakWin
        );
    }

    private MatchupAnalysisDto.RecentHeadToHeadDto recentHeadToHead(List<Match> matches,
                                                                    Long player1Id,
                                                                    Long player2Id) {
        int completed = 0;
        int player1Wins = 0;
        int player2Wins = 0;
        for (Match match : matches) {
            if (!match.isComplete() || match.getWinnerPlayerId() == null) {
                continue;
            }
            if (player1Id.equals(match.getWinnerPlayerId())) {
                player1Wins++;
                completed++;
            } else if (player2Id.equals(match.getWinnerPlayerId())) {
                player2Wins++;
                completed++;
            }
        }
        return new MatchupAnalysisDto.RecentHeadToHeadDto(completed, player1Wins, player2Wins);
    }

    private MatchupAnalysisDto.RatingDto toRatings(MatchupFeatureVectorDto.PlayerFeatureDto features) {
        return new MatchupAnalysisDto.RatingDto(
                features.eloRating(),
                features.glickoRating(),
                features.glickoRatingDeviation(),
                features.trueSkill2Mu(),
                features.wengLinRating(),
                features.ratingStability()
        );
    }

    private double clamp01(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }
}
