package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.dto.CompletedMatchLogDto;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.AdaptiveRegimeProfileDto;
import com.ttl.tabletennis.dto.ModelRegistryEntryDto;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.dto.ValueOpportunityDto;
import com.ttl.tabletennis.service.FeatureService;
import com.ttl.tabletennis.service.MatchupAnalysisService;
import com.ttl.tabletennis.service.OddsValueEngineService;
import com.ttl.tabletennis.service.PaperTradingService;
import com.ttl.tabletennis.service.PredictionFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final MatchupAnalysisService matchupAnalysisService;
    private final FeatureService featureService;
    private final PredictionFacade predictionFacade;
    private final OddsValueEngineService oddsValueEngineService;
    private final PaperTradingService paperTradingService;

    public AnalyticsController(MatchupAnalysisService matchupAnalysisService,
                               FeatureService featureService,
                               PredictionFacade predictionFacade,
                               OddsValueEngineService oddsValueEngineService,
                               PaperTradingService paperTradingService) {
        this.matchupAnalysisService = matchupAnalysisService;
        this.featureService = featureService;
        this.predictionFacade = predictionFacade;
        this.oddsValueEngineService = oddsValueEngineService;
        this.paperTradingService = paperTradingService;
    }

    @GetMapping("/matchup")
    public MatchupAnalysisDto analyzeMatchup(@RequestParam Long player1Id,
                                             @RequestParam Long player2Id,
                                             @RequestParam(required = false) String modelVersion) {
        return matchupAnalysisService.analyze(player1Id, player2Id, modelVersion);
    }

    @GetMapping("/features")
    public MatchupFeatureVectorDto matchupFeatures(@RequestParam Long player1Id,
                                                   @RequestParam Long player2Id,
                                                   @RequestParam(required = false) LocalDate asOfDate) {
        return featureService.buildMatchupFeatureVector(player1Id, player2Id, asOfDate);
    }

    @GetMapping("/value-opportunities")
    public java.util.List<ValueOpportunityDto> valueOpportunities(@RequestParam(defaultValue = "CONSERVATIVE") String strategy,
                                                                  @RequestParam(defaultValue = "30") int limit) {
        return oddsValueEngineService.listValueOpportunities(strategy, limit);
    }

    @GetMapping("/models/registry")
    public java.util.List<ModelRegistryEntryDto> modelRegistry(@RequestParam(required = false) String family,
                                                               @RequestParam(defaultValue = "50") int limit) {
        return predictionFacade.recentRegistry(family, limit);
    }

    @GetMapping("/models/adaptive-regimes")
    public java.util.List<AdaptiveRegimeProfileDto> adaptiveRegimes() {
        return predictionFacade.currentAdaptiveRegimeProfiles();
    }

    @GetMapping("/live-odds")
    public java.util.List<LiveOddsRecommendationDto> liveOdds(@RequestParam(defaultValue = "CONSERVATIVE") String strategy,
                                                               @RequestParam(required = false) String modelVersion,
                                                               @RequestParam(defaultValue = "40") int limit,
                                                               @RequestParam(defaultValue = "false") boolean includeUnresolved) {
        return oddsValueEngineService.liveOddsRecommendations(strategy, modelVersion, limit, includeUnresolved);
    }

    @GetMapping("/paper-trading/session")
    public PaperTradingSessionDto paperTradingSession() {
        return paperTradingService.getSessionSnapshot();
    }

    @GetMapping("/paper-trading/completed-matches")
    public java.util.List<CompletedMatchLogDto> paperTradingCompletedMatches(@RequestParam(defaultValue = "3") int days,
                                                                              @RequestParam(defaultValue = "120") int limit) {
        return paperTradingService.recentCompletedMatchesLog(days, limit);
    }
}
