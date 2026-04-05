package com.ttl.tabletennis.service;

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
    private final PredictionModelService predictionModelService;

    public MatchupAnalysisService(PlayerService playerService,
                                  StatisticsService statisticsService,
                                  AdvancedStatisticsService advancedStatisticsService,
                                  FeatureService featureService,
                                  PredictionModelService predictionModelService) {
        this.playerService = playerService;
        this.statisticsService = statisticsService;
        this.advancedStatisticsService = advancedStatisticsService;
        this.featureService = featureService;
        this.predictionModelService = predictionModelService;
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

        double[] baseline = statisticsService.getAdvancedMatchupStatistics(player1, player2);
        MatchupFeatureVectorDto features = featureService.buildMatchupFeatureVector(player1Id, player2Id, null);
        PredictionModelService.PredictionSnapshot prediction = predictionModelService.predict(player1Id, player2Id, null, modelFamily);

        double eloP1 = features.eloProbabilityPlayer1();
        double glickoP1 = features.glickoProbabilityPlayer1();
        double p1Final = clamp01(prediction.player1Probability());
        double p2Final = 1.0 - p1Final;
        double p1RecentWinPct = features.player1().recentForm();
        double p2RecentWinPct = features.player2().recentForm();

        double p1Low = clamp01(prediction.player1ConfidenceLow());
        double p1High = clamp01(prediction.player1ConfidenceHigh());
        double p2Low = clamp01(1.0 - p1High);
        double p2High = clamp01(1.0 - p1Low);

        MatchupAnalysisDto.FormDto p1Form = new MatchupAnalysisDto.FormDto(
                p1Recent.n,
                p1Recent.wins,
                p1RecentWinPct,
                p1Recent.setDiffAvg,
                p1Recent.streak,
                p1Recent.streakWin
        );

        MatchupAnalysisDto.FormDto p2Form = new MatchupAnalysisDto.FormDto(
                p2Recent.n,
                p2Recent.wins,
                p2RecentWinPct,
                p2Recent.setDiffAvg,
                p2Recent.streak,
                p2Recent.streakWin
        );

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
                p1Probability,
                p2Probability,
                contributions,
                new MatchupAnalysisDto.ModelComparisonDto(baseline[0], eloP1, glickoP1),
                explanation
        );
    }

    private double clamp01(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
    }
}
