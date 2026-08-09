package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.PlayerAliasDto;
import com.ttl.tabletennis.dto.DuplicatePlayerCandidateDto;
import com.ttl.tabletennis.dto.EloSyncResultDto;
import com.ttl.tabletennis.dto.Glicko2RebuildDto;
import com.ttl.tabletennis.dto.Glicko2TauTuningDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.dto.OddsRefreshResultDto;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.dto.PaperTradingSyncResultDto;
import com.ttl.tabletennis.dto.RatingSnapshotDto;
import com.ttl.tabletennis.dto.StatisticsBenchmarkDto;
import com.ttl.tabletennis.dto.TrueSkill2MatchupDto;
import com.ttl.tabletennis.dto.TrueSkill2RatingDto;
import com.ttl.tabletennis.dto.TrueSkill2RebuildDto;
import com.ttl.tabletennis.dto.WengLinMatchupDto;
import com.ttl.tabletennis.dto.WengLinRatingDto;
import com.ttl.tabletennis.dto.WengLinRebuildDto;
import com.ttl.tabletennis.mapper.AliasMapper;
import com.ttl.tabletennis.request.AliasUpsertRequest;
import com.ttl.tabletennis.request.MergePlayersRequest;
import com.ttl.tabletennis.request.RatingSnapshotRequest;
import com.ttl.tabletennis.service.Glicko2RatingService;
import com.ttl.tabletennis.service.MatchResultBackfillService;
import com.ttl.tabletennis.service.OddsValueEngineService;
import com.ttl.tabletennis.service.PaperTradingService;
import com.ttl.tabletennis.service.PredictionFacade;
import com.ttl.tabletennis.service.PlayerIdentityService;
import com.ttl.tabletennis.service.RatingSnapshotService;
import com.ttl.tabletennis.service.StatisticsBenchmarkService;
import com.ttl.tabletennis.service.TtSeriesEloSyncService;
import com.ttl.tabletennis.service.TrueSkill2Service;
import com.ttl.tabletennis.service.WengLinService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final PlayerIdentityService playerIdentityService;
    private final RatingSnapshotService ratingSnapshotService;
    private final StatisticsBenchmarkService statisticsBenchmarkService;
    private final MatchResultBackfillService matchResultBackfillService;
    private final Glicko2RatingService glicko2RatingService;
    private final TrueSkill2Service trueSkill2Service;
    private final WengLinService wengLinService;
    private final PredictionFacade predictionFacade;
    private final OddsValueEngineService oddsValueEngineService;
    private final PaperTradingService paperTradingService;
    private final TtSeriesEloSyncService ttSeriesEloSyncService;
    private final com.ttl.tabletennis.prediction.staking.ClosingLineBackfillService closingLineBackfillService;

    public AdminController(PlayerIdentityService playerIdentityService,
                           RatingSnapshotService ratingSnapshotService,
                           StatisticsBenchmarkService statisticsBenchmarkService,
                           MatchResultBackfillService matchResultBackfillService,
                           Glicko2RatingService glicko2RatingService,
                           TrueSkill2Service trueSkill2Service,
                           WengLinService wengLinService,
                           PredictionFacade predictionFacade,
                           OddsValueEngineService oddsValueEngineService,
                           PaperTradingService paperTradingService,
                           TtSeriesEloSyncService ttSeriesEloSyncService,
                           com.ttl.tabletennis.prediction.staking.ClosingLineBackfillService closingLineBackfillService) {
        this.playerIdentityService = playerIdentityService;
        this.ratingSnapshotService = ratingSnapshotService;
        this.statisticsBenchmarkService = statisticsBenchmarkService;
        this.matchResultBackfillService = matchResultBackfillService;
        this.glicko2RatingService = glicko2RatingService;
        this.trueSkill2Service = trueSkill2Service;
        this.wengLinService = wengLinService;
        this.predictionFacade = predictionFacade;
        this.oddsValueEngineService = oddsValueEngineService;
        this.paperTradingService = paperTradingService;
        this.ttSeriesEloSyncService = ttSeriesEloSyncService;
        this.closingLineBackfillService = closingLineBackfillService;
    }

    /**
     * Finish-checklist §5 — fills closing-line snapshots on settled
     * paper-trade samples that pre-date the capture wiring. Returns the
     * scan / fill / skip counts so the caller can chain pages.
     */
    @PostMapping("/clv/backfill")
    public com.ttl.tabletennis.prediction.staking.ClosingLineBackfillService.BackfillResult backfillClosingLines(
            @RequestParam(name = "limit", defaultValue = "500") int limit) {
        return closingLineBackfillService.backfill(limit);
    }

    @GetMapping("/aliases")
    public List<PlayerAliasDto> listAliases(@RequestParam(required = false) Long playerId) {
        return playerIdentityService.listAliases(playerId)
                .stream()
                .map(AliasMapper::toDto)
                .toList();
    }

    @GetMapping("/aliases/player/{playerId}")
    public List<PlayerAliasDto> aliasesForPlayer(@PathVariable Long playerId) {
        return playerIdentityService.listAliases(playerId)
                .stream()
                .map(AliasMapper::toDto)
                .toList();
    }

    @PostMapping("/aliases")
    public PlayerAliasDto upsertAlias(@Valid @RequestBody AliasUpsertRequest request) {
        return AliasMapper.toDto(playerIdentityService.upsertAlias(request.playerId(), request.aliasName()));
    }

    @PostMapping("/players/merge")
    public ResponseEntity<Map<String, Object>> mergePlayers(@Valid @RequestBody MergePlayersRequest request) {
        int impactedMatches = playerIdentityService.mergePlayers(request.sourcePlayerId(), request.targetPlayerId());
        return ResponseEntity.ok(Map.of(
                "sourcePlayerId", request.sourcePlayerId(),
                "targetPlayerId", request.targetPlayerId(),
                "impactedMatches", impactedMatches
        ));
    }

    @GetMapping("/players/potential-duplicates")
    public List<DuplicatePlayerCandidateDto> potentialDuplicates(@RequestParam(defaultValue = "0.82") double minSimilarity,
                                                                 @RequestParam(defaultValue = "50") int limit) {
        return playerIdentityService.findPotentialDuplicates(minSimilarity, limit);
    }

    @GetMapping("/ratings/player/{playerId}")
    public List<RatingSnapshotDto> getRatingHistory(@PathVariable Long playerId) {
        return ratingSnapshotService.getByPlayer(playerId);
    }

    @PostMapping("/ratings")
    public RatingSnapshotDto upsertRatingSnapshot(@Valid @RequestBody RatingSnapshotRequest request) {
        return ratingSnapshotService.upsert(request);
    }

    @PostMapping("/ratings/elo/sync")
    public EloSyncResultDto syncEloRatings() {
        return ttSeriesEloSyncService.syncFromRankingPage();
    }

    @PostMapping("/ratings/glicko2/rebuild")
    public Glicko2RebuildDto rebuildGlicko2(@RequestParam(required = false) LocalDate fromDate,
                                            @RequestParam(required = false) LocalDate toDate) {
        return glicko2RatingService.rebuild(fromDate, toDate);
    }

    @PostMapping("/ratings/glicko2/tune-tau")
    public Glicko2TauTuningDto tuneGlicko2Tau(@RequestParam(required = false) LocalDate fromDate,
                                              @RequestParam(required = false) LocalDate toDate,
                                              @RequestParam(required = false, name = "tau") List<Double> tauCandidates) {
        return glicko2RatingService.tuneTau(fromDate, toDate, tauCandidates);
    }

    @PostMapping("/ratings/trueskill2/rebuild")
    public TrueSkill2RebuildDto rebuildTrueSkill2(@RequestParam(required = false) LocalDate fromDate,
                                                  @RequestParam(required = false) LocalDate toDate) {
        return trueSkill2Service.rebuild(fromDate, toDate);
    }

    @GetMapping("/ratings/trueskill2/player/{playerId}")
    public TrueSkill2RatingDto getTrueSkill2Rating(@PathVariable Long playerId,
                                                   @RequestParam(required = false) LocalDate asOfDate) {
        return trueSkill2Service.ratingForPlayer(playerId, asOfDate);
    }

    @GetMapping("/ratings/trueskill2/matchup")
    public TrueSkill2MatchupDto getTrueSkill2Matchup(@RequestParam Long player1Id,
                                                     @RequestParam Long player2Id,
                                                     @RequestParam(required = false) LocalDate asOfDate) {
        return trueSkill2Service.matchup(player1Id, player2Id, asOfDate);
    }

    @PostMapping("/ratings/wenglin/rebuild")
    public WengLinRebuildDto rebuildWengLin(@RequestParam(required = false) LocalDate fromDate,
                                            @RequestParam(required = false) LocalDate toDate) {
        return wengLinService.rebuild(fromDate, toDate);
    }

    @GetMapping("/ratings/wenglin/player/{playerId}")
    public WengLinRatingDto getWengLinRating(@PathVariable Long playerId,
                                             @RequestParam(required = false) LocalDate asOfDate) {
        return wengLinService.ratingForPlayer(playerId, asOfDate);
    }

    @GetMapping("/ratings/wenglin/matchup")
    public WengLinMatchupDto getWengLinMatchup(@RequestParam Long player1Id,
                                               @RequestParam Long player2Id,
                                               @RequestParam(required = false) LocalDate asOfDate) {
        return wengLinService.matchup(player1Id, player2Id, asOfDate);
    }

    @PostMapping("/models/train")
    public ModelTrainingReportDto trainPredictionModels(@RequestParam(required = false) LocalDate fromDate,
                                                        @RequestParam(required = false) LocalDate toDate) {
        matchResultBackfillService.backfillStructuredResults();
        return predictionFacade.trainModels(fromDate, toDate);
    }

    @GetMapping("/models/last-report")
    public ModelTrainingReportDto lastModelTrainingReport() {
        return predictionFacade.latestTrainingReport();
    }

    @PostMapping("/odds/refresh")
    public OddsRefreshResultDto refreshOddsValueEngine(@RequestParam(defaultValue = "CONSERVATIVE") String strategy,
                                                       @RequestParam(required = false) String modelVersion) {
        return oddsValueEngineService.refresh(strategy, modelVersion);
    }

    @PostMapping("/paper-trading/sync")
    public PaperTradingSyncResultDto syncPaperTrading(@RequestParam(defaultValue = "CONSERVATIVE") String strategy,
                                                      @RequestParam(required = false) String modelVersion,
                                                      @RequestParam(required = false) Integer limit) {
        return paperTradingService.syncLiveSession(strategy, modelVersion, limit);
    }

    @PostMapping("/paper-trading/reset")
    public PaperTradingSessionDto resetPaperTrading(@RequestParam(required = false) Double startingBankroll,
                                                    @RequestParam(required = false) String label,
                                                    @RequestParam(defaultValue = "false") boolean clearHistory) {
        return paperTradingService.resetSession(startingBankroll, label, clearHistory);
    }

    @GetMapping("/benchmark/statistics")
    public StatisticsBenchmarkDto benchmarkStatistics(@RequestParam(defaultValue = "10") int iterations) {
        return statisticsBenchmarkService.benchmarkPlayerStats(iterations);
    }

    @PostMapping("/backfill/match-results")
    public ResponseEntity<Map<String, Integer>> backfillMatchResults() {
        int updated = matchResultBackfillService.backfillStructuredResults();
        return ResponseEntity.ok(Map.of("updatedMatches", updated));
    }
}
