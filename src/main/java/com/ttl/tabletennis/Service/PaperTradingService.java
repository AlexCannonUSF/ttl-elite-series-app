package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.CompletedMatchLogDto;
import com.ttl.tabletennis.dto.LiveStudioIntegrityDto;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.LiveScoreSnapshotDto;
import com.ttl.tabletennis.dto.PaperTradeBetDto;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.dto.PaperTradingSyncResultDto;
import com.ttl.tabletennis.dto.TrackedMatchObservationDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.scrape.TtSeriesScraper;
import com.ttl.tabletennis.util.NameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);
    private static final double EPS = 1e-9;
    private static final Pattern SCORE_PAIR_PATTERN = Pattern.compile("(\\d{1,2})\\s*[-:]\\s*(\\d{1,2})");
    private static final Pattern SOURCE_EVENT_ID_PATTERN =
            Pattern.compile("\\|event=([A-Za-z0-9:_-]+)", Pattern.CASE_INSENSITIVE);
    private static final String OBSERVATION_SOURCE_MARKET_BOARD = "MARKET_BOARD";
    private static final String OBSERVATION_SOURCE_SCORE_FEED = "SCORE_FEED";
    private static final String SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE = "DECISIVE_LIVE_SCORE";
    private static final String SETTLEMENT_SOURCE_OFFICIAL_RESULT = "OFFICIAL_RESULT";
    private static final String SETTLEMENT_SOURCE_DATABASE_RESULT = "DATABASE_RESULT";
    private static final String SETTLEMENT_SOURCE_HEURISTIC_FALLBACK = "HEURISTIC_FALLBACK";
    private static final String SETTLEMENT_SOURCE_TIMEOUT_VOID = "TIMEOUT_VOID";

    private final OddsValueEngineService oddsValueEngineService;
    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradeBetRepository betRepository;
    private final PaperTradeDecisionSampleRepository decisionSampleRepository;
    private final PaperTradeLearningSampleRepository learningSampleRepository;
    private final MatchRepository matchRepository;
    private final TrackedMatchObservationRepository trackedMatchObservationRepository;
    private final TtSeriesScraper ttSeriesScraper;

    @Value("${ttl.paper.startingBankroll:1000.0}")
    private double defaultStartingBankroll;

    @Value("${ttl.paper.baseStakePct:0.025}")
    private double baseStakePct;

    @Value("${ttl.paper.maxStakePct:0.07}")
    private double maxStakePct;

    @Value("${ttl.paper.minStake:10.0}")
    private double minStake;

    @Value("${ttl.paper.maxStake:250.0}")
    private double maxStake;

    @Value("${ttl.paper.minEdge:0.024}")
    private double minEdgeForBet;

    @Value("${ttl.paper.minEdgeLive:0.027}")
    private double minEdgeLive;

    @Value("${ttl.paper.minEdgePrematch:0.024}")
    private double minEdgePrematch;

    @Value("${ttl.paper.liveRowsLimit:80}")
    private int liveRowsLimit;

    @Value("${ttl.paper.requireRecommendation:false}")
    private boolean requireRecommendation;

    @Value("${ttl.paper.allowLive:true}")
    private boolean allowLive;

    @Value("${ttl.paper.allowPrematch:true}")
    private boolean allowPrematch;

    @Value("${ttl.paper.maxLongshotAmericanOdds:190}")
    private int maxLongshotAmericanOdds;

    @Value("${ttl.paper.minImpliedProbability:0.20}")
    private double minImpliedProbability;

    @Value("${ttl.paper.maxConfidenceWidth:0.30}")
    private double maxConfidenceWidth;

    @Value("${ttl.paper.maxNewBetsPerSync:4}")
    private int maxNewBetsPerSync;

    @Value("${ttl.paper.maxConcurrentOpenBets:16}")
    private int maxConcurrentOpenBets;

    @Value("${ttl.paper.maxOpenExposurePct:0.60}")
    private double maxOpenExposurePct;

    @Value("${ttl.paper.maxExposurePerPlayerPct:0.18}")
    private double maxExposurePerPlayerPct;

    @Value("${ttl.paper.maxExposurePerTriggerPct:0.24}")
    private double maxExposurePerTriggerPct;

    @Value("${ttl.paper.minSelectionScore:4.2}")
    private double minSelectionScore;

    @Value("${ttl.paper.minModelImpliedGap:0.040}")
    private double minModelImpliedGap;

    @Value("${ttl.paper.minModelImpliedGapLive:0.030}")
    private double minModelImpliedGapLive;

    @Value("${ttl.paper.minModelImpliedGapPrematch:0.040}")
    private double minModelImpliedGapPrematch;

    @Value("${ttl.paper.maxPositiveAmericanOdds:170}")
    private int maxPositiveAmericanOdds;

    @Value("${ttl.paper.maxSettlementLagDays:21}")
    private int maxSettlementLagDays;

    @Value("${ttl.paper.onlyUpcoming:true}")
    private boolean onlyUpcoming;

    @Value("${ttl.paper.startTimeGraceMinutes:0}")
    private int startTimeGraceMinutes;

    @Value("${ttl.paper.settlementDelayMinutes:15}")
    private int settlementDelayMinutes;

    @Value("${ttl.paper.scoreSettlementTargetSets:3}")
    private int scoreSettlementTargetSets;

    @Value("${ttl.paper.scoreSettlementMinMarginSets:2}")
    private int scoreSettlementMinMarginSets;

    @Value("${ttl.paper.databaseSettlementFallback:false}")
    private boolean databaseSettlementFallback;

    @Value("${ttl.paper.databaseSettlementFallbackForTrackedAfterClose:true}")
    private boolean databaseSettlementFallbackForTrackedAfterClose;

    @Value("${ttl.paper.officialResultConfirmationEnabled:true}")
    private boolean officialResultConfirmationEnabled;

    @Value("${ttl.paper.officialResultRefreshEnabled:true}")
    private boolean officialResultRefreshEnabled;

    @Value("${ttl.paper.officialResultRefreshPages:1}")
    private int officialResultRefreshPages;

    @Value("${ttl.paper.officialResultMaxAgeDays:3}")
    private int officialResultMaxAgeDays;

    @Value("${ttl.paper.officialResultRefreshTrackedAfterCloseOnly:true}")
    private boolean officialResultRefreshTrackedAfterCloseOnly;

    @Value("${ttl.paper.unmatchedRefundMinutes:180}")
    private int unmatchedRefundMinutes;

    @Value("${ttl.paper.unmatchedMissingSyncs:4}")
    private int unmatchedMissingSyncs;

    @Value("${ttl.paper.minMissingObservationsForScoreSettle:2}")
    private int minMissingObservationsForScoreSettle;

    @Value("${ttl.paper.lastScoreBackfillMinutes:60}")
    private int lastScoreBackfillMinutes;

    @Value("${ttl.paper.trackedAfterCloseGraceMinutes:30}")
    private int trackedAfterCloseGraceMinutes;

    @Value("${ttl.paper.nearFinishFallbackEnabled:true}")
    private boolean nearFinishFallbackEnabled;

    @Value("${ttl.paper.nearFinishFallbackMissingSyncs:5}")
    private int nearFinishFallbackMissingSyncs;

    @Value("${ttl.paper.nearFinishFallbackMinutes:25}")
    private int nearFinishFallbackMinutes;

    @Value("${ttl.paper.nearFinishFallbackMinPointLead:2}")
    private int nearFinishFallbackMinPointLead;

    @Value("${ttl.paper.nearFinishFallbackPointFloor:10}")
    private int nearFinishFallbackPointFloor;

    @Value("${ttl.paper.staleOnBoardMinObservations:4}")
    private int staleOnBoardMinObservations;

    @Value("${ttl.paper.staleOnBoardSettleMinutes:8}")
    private int staleOnBoardSettleMinutes;

    @Value("${ttl.paper.minExpectedRoi:0.024}")
    private double minExpectedRoi;

    @Value("${ttl.paper.adaptive.enabled:true}")
    private boolean adaptiveEnabled;

    @Value("${ttl.paper.adaptive.minSettledDecisions:12}")
    private int adaptiveMinSettledDecisions;

    @Value("${ttl.paper.adaptive.historyWindow:150}")
    private int adaptiveHistoryWindow;

    @Value("${ttl.paper.adaptive.maxEdgeShift:0.02}")
    private double adaptiveMaxEdgeShift;

    @Value("${ttl.paper.adaptive.maxSelectionScoreShift:1.0}")
    private double adaptiveMaxSelectionScoreShift;

    @Value("${ttl.paper.adaptive.maxStakeMultiplierDelta:0.18}")
    private double adaptiveMaxStakeMultiplierDelta;

    @Value("${ttl.paper.adaptive.triggerMinDecisions:5}")
    private int adaptiveTriggerMinDecisions;

    @Value("${ttl.paper.adaptive.learningHalfLifeDays:21}")
    private double adaptiveLearningHalfLifeDays;

    @Value("${ttl.paper.adaptive.backfillOnStartup:true}")
    private boolean adaptiveBackfillOnStartup;

    @Value("${ttl.paper.adaptive.backfillLimit:5000}")
    private int adaptiveBackfillLimit;

    @Value("${ttl.paper.adaptive.incrementalBackfillLimit:600}")
    private int adaptiveIncrementalBackfillLimit;

    public PaperTradingService(OddsValueEngineService oddsValueEngineService,
                               PaperTradeSessionRepository sessionRepository,
                               PaperTradeBetRepository betRepository,
                               PaperTradeDecisionSampleRepository decisionSampleRepository,
                               PaperTradeLearningSampleRepository learningSampleRepository,
                               MatchRepository matchRepository,
                               TrackedMatchObservationRepository trackedMatchObservationRepository,
                               TtSeriesScraper ttSeriesScraper) {
        this.oddsValueEngineService = oddsValueEngineService;
        this.sessionRepository = sessionRepository;
        this.betRepository = betRepository;
        this.decisionSampleRepository = decisionSampleRepository;
        this.learningSampleRepository = learningSampleRepository;
        this.matchRepository = matchRepository;
        this.trackedMatchObservationRepository = trackedMatchObservationRepository;
        this.ttSeriesScraper = ttSeriesScraper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void warmLearningStoreOnStartup() {
        if (!adaptiveBackfillOnStartup) {
            return;
        }
        int limit = clamp(adaptiveBackfillLimit, 100, 50000);
        int inserted = backfillLearningSamples(limit);
        if (inserted > 0) {
            log.info("[paper] learning backfill complete: inserted={} (limit={})", inserted, limit);
        }
    }

    @Transactional
    public PaperTradingSyncResultDto syncLiveSession(String strategyRaw,
                                                     String modelVersionRaw,
                                                     Integer limit) {
        PaperTradeSession session = getOrCreateActiveSession();
        String strategy = normalizeStrategy(strategyRaw);
        String modelVersion = StringUtils.hasText(modelVersionRaw) ? modelVersionRaw.trim() : "ENSEMBLE";
        int take = clamp(limit == null ? liveRowsLimit : limit, 5, 250);
        List<PaperTradeBet> existingOpenBets = betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(
                session.getId(),
                PaperTradeBet.STATUS_OPEN
        );
        long openBetCount = existingOpenBets.size();
        if (openBetCount > 0) {
            int coverageFloor = clamp((int) Math.min(250L, openBetCount * 4L), 80, 250);
            take = Math.max(take, coverageFloor);
        }

        List<LiveOddsRecommendationDto> rows = oddsValueEngineService.liveOddsRecommendations(strategy, modelVersion, take, false);
        if (rows == null) {
            rows = List.of();
        }
        int openCoverageTarget = clamp(
                (int) Math.min(1600L, Math.max(250L, openBetCount * 25L)),
                120,
                1600
        );
        int snapshotTake = Math.max(Math.max(take, liveRowsLimit), openCoverageTarget);
        List<LiveScoreSnapshotDto> scoreSnapshots = oddsValueEngineService.liveScoreSnapshots(
                snapshotTake,
                true
        );
        if (scoreSnapshots == null) {
            scoreSnapshots = List.of();
        }
        Set<String> openBetExternalEventIds = collectOpenBetExternalEventIds(session.getId());
        if (!openBetExternalEventIds.isEmpty()) {
            List<LiveScoreSnapshotDto> targetedSnapshots = oddsValueEngineService.liveScoreSnapshotsForEventIds(
                    openBetExternalEventIds,
                    snapshotTake,
                    true
            );
            if (targetedSnapshots != null && !targetedSnapshots.isEmpty()) {
                List<LiveScoreSnapshotDto> mergedSnapshots = new ArrayList<>(scoreSnapshots.size() + targetedSnapshots.size());
                mergedSnapshots.addAll(scoreSnapshots);
                mergedSnapshots.addAll(targetedSnapshots);
                scoreSnapshots = mergedSnapshots;
            }
        }
        AdaptiveProfile adaptiveProfile = buildAdaptiveProfile(session);

        int placed = 0;
        int skipped = 0;
        int maxPlacements = clamp(maxNewBetsPerSync, 1, 30);
        List<RankedCandidate> rankedCandidates = new ArrayList<>();
        List<RankedCandidate> fallbackCandidates = new ArrayList<>();
        double minScore = clamp(minSelectionScore + adaptiveProfile.selectionScoreShift(), -5.0, 30.0);
        ExposureProfile exposureProfile = ExposureProfile.fromOpenBets(existingOpenBets);
        double exposureCapitalBase = Math.max(
                session.getCurrentBankroll(),
                round2(session.getCurrentBankroll() + exposureProfile.openStake())
        );

        for (LiveOddsRecommendationDto row : rows) {
            String eligibilityFailure = eligibilityRejectionReason(row, adaptiveProfile);
            if (eligibilityFailure != null) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        row,
                        null,
                        resolveDecisionEventKey(row),
                        resolveDecisionDedupeKey(row, null, null),
                        null,
                        null,
                        null,
                        false,
                        "SKIPPED",
                        eligibilityFailure
                );
                skipped++;
                continue;
            }

            CandidateResolution candidateResolution = resolveCandidate(row, adaptiveProfile);
            BetCandidate candidate = candidateResolution.candidate();
            if (candidate == null) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        row,
                        null,
                        resolveDecisionEventKey(row),
                        resolveDecisionDedupeKey(row, null, null),
                        null,
                        null,
                        null,
                        false,
                        "SKIPPED",
                        candidateResolution.rejectionReason()
                );
                skipped++;
                continue;
            }

            String candidateSafetyFailure = candidateSafetyRejectionReason(row, candidate, adaptiveProfile);
            if (candidateSafetyFailure != null) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        row,
                        candidate,
                        resolveDecisionEventKey(row),
                        resolveDecisionDedupeKey(row, null, candidate),
                        null,
                        null,
                        null,
                        false,
                        "SKIPPED",
                        candidateSafetyFailure
                );
                skipped++;
                continue;
            }

            String eventKey = StringUtils.hasText(row.matchupKey())
                    ? row.matchupKey().trim()
                    : buildEventKey(row);
            String dedupeKey = StringUtils.hasText(row.suggestedDedupeKey())
                    ? row.suggestedDedupeKey().trim()
                    : eventKey + "|" + normalizeKey(candidate.sideName());
            if (betRepository.existsBySessionIdAndEventKeyAndStatus(session.getId(), eventKey, PaperTradeBet.STATUS_OPEN)) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        row,
                        candidate,
                        eventKey,
                        dedupeKey,
                        null,
                        null,
                        null,
                        false,
                        "SKIPPED",
                        "DUPLICATE_OPEN_EVENT"
                );
                skipped++;
                continue;
            }
            if (betRepository.existsBySessionIdAndDedupeKey(session.getId(), dedupeKey)) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        row,
                        candidate,
                        eventKey,
                        dedupeKey,
                        null,
                        null,
                        null,
                        false,
                        "SKIPPED",
                        "DUPLICATE_OPEN_DEDUPE"
                );
                skipped++;
                continue;
            }

            double selectionScore = scoreCandidate(row, candidate, adaptiveProfile);
            RankedCandidate ranked = new RankedCandidate(row, candidate, eventKey, dedupeKey, selectionScore, false);
            if (selectionScore >= minScore) {
                rankedCandidates.add(ranked);
            } else {
                fallbackCandidates.add(new RankedCandidate(row, candidate, eventKey, dedupeKey, selectionScore, true));
            }
        }

        rankedCandidates.sort(rankComparator());
        fallbackCandidates.sort(rankComparator());

        // If nothing clears the score threshold, still simulate a tiny number of highest-quality safe edges.
        if (rankedCandidates.isEmpty() && !fallbackCandidates.isEmpty()) {
            int fallbackTake = Math.min(maxPlacements, 2);
            for (int i = 0; i < Math.min(fallbackTake, fallbackCandidates.size()); i++) {
                rankedCandidates.add(fallbackCandidates.get(i));
            }
            for (int i = fallbackTake; i < fallbackCandidates.size(); i++) {
                RankedCandidate skippedCandidate = fallbackCandidates.get(i);
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        skippedCandidate.row(),
                        skippedCandidate.candidate(),
                        skippedCandidate.eventKey(),
                        skippedCandidate.dedupeKey(),
                        skippedCandidate.selectionScore(),
                        null,
                        null,
                        true,
                        "SKIPPED",
                        "BELOW_SELECTION_SCORE"
                );
                skipped++;
            }
        } else {
            for (RankedCandidate skippedCandidate : fallbackCandidates) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        skippedCandidate.row(),
                        skippedCandidate.candidate(),
                        skippedCandidate.eventKey(),
                        skippedCandidate.dedupeKey(),
                        skippedCandidate.selectionScore(),
                        null,
                        null,
                        skippedCandidate.fallbackPick(),
                        "SKIPPED",
                        "BELOW_SELECTION_SCORE"
                );
                skipped++;
            }
        }

        Set<String> placedEventKeys = new HashSet<>();
        Set<String> placedDedupeKeys = new HashSet<>();

        for (int i = 0; i < rankedCandidates.size(); i++) {
            RankedCandidate ranked = rankedCandidates.get(i);
            if (i >= maxPlacements) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        ranked.row(),
                        ranked.candidate(),
                        ranked.eventKey(),
                        ranked.dedupeKey(),
                        ranked.selectionScore(),
                        null,
                        null,
                        ranked.fallbackPick(),
                        "SKIPPED",
                        "OVER_MAX_NEW_BETS"
                );
                skipped++;
                continue;
            }
            if (placedEventKeys.contains(ranked.eventKey())
                    || placedDedupeKeys.contains(ranked.dedupeKey())) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        ranked.row(),
                        ranked.candidate(),
                        ranked.eventKey(),
                        ranked.dedupeKey(),
                        ranked.selectionScore(),
                        null,
                        null,
                        ranked.fallbackPick(),
                        "SKIPPED",
                        "DUPLICATE_BATCH"
                );
                skipped++;
                continue;
            }
            if (betRepository.existsBySessionIdAndEventKeyAndStatus(session.getId(), ranked.eventKey(), PaperTradeBet.STATUS_OPEN)
                    || betRepository.existsBySessionIdAndDedupeKey(session.getId(), ranked.dedupeKey())) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        ranked.row(),
                        ranked.candidate(),
                        ranked.eventKey(),
                        ranked.dedupeKey(),
                        ranked.selectionScore(),
                        null,
                        null,
                        ranked.fallbackPick(),
                        "SKIPPED",
                        "DUPLICATE_OPEN_AFTER_RANK"
                );
                skipped++;
                continue;
            }
            if (exposureProfile.openBets() >= clamp(maxConcurrentOpenBets, 1, 60)) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        ranked.row(),
                        ranked.candidate(),
                        ranked.eventKey(),
                        ranked.dedupeKey(),
                        ranked.selectionScore(),
                        null,
                        null,
                        ranked.fallbackPick(),
                        "SKIPPED",
                        "MAX_CONCURRENT_OPEN_BETS"
                );
                skipped++;
                continue;
            }
            LiveOddsRecommendationDto row = ranked.row();
            BetCandidate candidate = ranked.candidate();

            double proposedStake = computeStake(
                    session.getCurrentBankroll(),
                    candidate.edge(),
                    candidate.modelProbability(),
                    candidate.decimalOdds(),
                    row.confidenceLow(),
                    row.confidenceHigh(),
                    candidate.signalQuality(),
                    row.live(),
                    row.matchPhase(),
                    candidate.americanOdds(),
                    adaptiveProfile
            );
            double stake = applyExposureCaps(
                    proposedStake,
                    candidate,
                    exposureProfile,
                    exposureCapitalBase
            );
            if (stake + EPS < minStake || stake > session.getCurrentBankroll()) {
                persistDecisionSample(
                        session.getId(),
                        strategy,
                        modelVersion,
                        ranked.row(),
                        ranked.candidate(),
                        ranked.eventKey(),
                        ranked.dedupeKey(),
                        ranked.selectionScore(),
                        proposedStake,
                        stake,
                        ranked.fallbackPick(),
                        "SKIPPED",
                        "STAKE_BELOW_MIN_OR_BANKROLL"
                );
                skipped++;
                continue;
            }

            PaperTradeBet bet = new PaperTradeBet();
            bet.setSessionId(session.getId());
            bet.setStatus(PaperTradeBet.STATUS_OPEN);
            bet.setSource(safeText(row.source(), "HARD_ROCK"));
            bet.setStrategy(strategy);
            bet.setModelVersion(safeText(row.modelVersion(), modelVersion));
            bet.setEventKey(ranked.eventKey());
            bet.setDedupeKey(ranked.dedupeKey());
            bet.setEventName(safeText(row.eventName(), "Unknown Event"));
            bet.setCompetitionName(safeText(row.competitionName(), "Table Tennis"));
            bet.setStartTimeIso(row.startTimeIso());
            bet.setExternalEventId(StringUtils.hasText(row.externalEventId())
                    ? row.externalEventId().trim()
                    : extractExternalEventId(row.source()));
            bet.setLiveAtPlacement(row.live());
            bet.setPlayer1Id(row.player1Id());
            bet.setPlayer2Id(row.player2Id());
            bet.setSidePlayerId(candidate.sidePlayerId());
            bet.setPlayer1Name(safeText(row.player1Name(), "Player 1"));
            bet.setPlayer2Name(safeText(row.player2Name(), "Player 2"));
            bet.setSideName(safeText(candidate.sideName(), "Player"));
            String placementScore = normalizeScoreForBet(
                    bet,
                    row.liveScore(),
                    row.player1Id(),
                    row.player1Name(),
                    row.player2Id(),
                    row.player2Name()
            );
            if (StringUtils.hasText(placementScore)) {
                bet.setLastObservedScore(placementScore.trim());
            }
            if (StringUtils.hasText(row.matchPhase())) {
                bet.setLastObservedPhase(row.matchPhase().trim());
            }
            bet.setLastScoreSource(inferObservationSourceKind(row));
            bet.setLastScoreConfidence(observationSourceConfidence(row));
            bet.setLastObservationDisplayed(row.displayed());
            bet.setLastObservationResulted(row.resulted());
            bet.setLastMatchCompleted(row.matchCompleted());
            bet.setLastSourceFeedCode(StringUtils.hasText(row.sourceFeedCode()) ? row.sourceFeedCode().trim() : null);
            bet.setLastSourceFeedEventId(StringUtils.hasText(row.sourceFeedEventId()) ? row.sourceFeedEventId().trim() : null);
            bet.setLastScoreDetail(StringUtils.hasText(row.scoreDetail()) ? row.scoreDetail().trim() : null);
            bet.setTrackedAfterClose(false);
            bet.setLastObservedAt(LocalDateTime.now());
            bet.setDecimalOdds(candidate.decimalOdds());
            bet.setAmericanOdds(candidate.americanOdds());
            bet.setImpliedProbability(candidate.impliedProbability());
            bet.setModelProbability(candidate.modelProbability());
            bet.setEdge(candidate.edge());
            bet.setConfidenceLow(row.confidenceLow());
            bet.setConfidenceHigh(row.confidenceHigh());
            bet.setStake(stake);
            bet.setPotentialPayout(round2(stake * candidate.decimalOdds()));
            bet.setTopTrigger(row.topTrigger());
            bet.setTopTriggerContribution(row.topTriggerContribution());
            bet.setGrade(row.grade());
            String rationale = safeText(row.rationale(), "Model value pick");
            String fallbackTag = ranked.fallbackPick() ? " | fallbackPick=true" : "";
            String exposureTag = stake + 0.009 < proposedStake
                    ? String.format(Locale.ROOT, " | exposureCapApplied=%.2f->%.2f", proposedStake, stake)
                    : "";
            bet.setRationale(rationale + String.format(
                    Locale.ROOT,
                    " | selectionScore=%.2f | modelShift=%+.3f%s%s%s",
                    ranked.selectionScore(),
                    candidate.modelProbabilityShiftApplied(),
                    fallbackTag,
                    exposureTag,
                    candidate.triggerSignal().sampleSize() > 0
                            ? " | triggerSamples=" + candidate.triggerSignal().sampleSize()
                            : ""
            ));
            bet.setPlacedAt(LocalDateTime.now());
            betRepository.save(bet);
            persistDecisionSample(
                    session.getId(),
                    strategy,
                    modelVersion,
                    ranked.row(),
                    ranked.candidate(),
                    ranked.eventKey(),
                    ranked.dedupeKey(),
                    ranked.selectionScore(),
                    proposedStake,
                    stake,
                    ranked.fallbackPick(),
                    "PLACED",
                    ranked.fallbackPick()
                            ? "PLACED_FALLBACK"
                            : (stake + 0.009 < proposedStake ? "PLACED_CAPPED" : "PLACED_PRIMARY")
            );
            recordObservation(session.getId(), bet, row, placementScore, LocalDateTime.now());
            placedEventKeys.add(ranked.eventKey());
            placedDedupeKeys.add(ranked.dedupeKey());

            session.setCurrentBankroll(round2(session.getCurrentBankroll() - stake));
            session.setTotalStaked(round2(session.getTotalStaked() + stake));
            session.setTotalBets(session.getTotalBets() + 1);
            exposureProfile = exposureProfile.addPlacement(candidate.sidePlayerId(), candidate.triggerKey(), stake);
            placed++;
        }

        SettlementStats settlementStats = settleOpenBets(session, mergeSettlementRows(rows, scoreSnapshots));
        int settled = settlementStats.settled();
        int voided = settlementStats.voided();
        int incrementalBackfill = clamp(adaptiveIncrementalBackfillLimit, 0, 5000);
        if (incrementalBackfill > 0) {
            backfillLearningSamples(incrementalBackfill);
        }
        session.setPeakBankroll(Math.max(session.getPeakBankroll(), session.getCurrentBankroll()));
        session.setLastSyncAt(LocalDateTime.now());
        session.setSimulationRowsScanned(session.getSimulationRowsScanned() + rows.size());
        session.setSimulationBetsPlaced(session.getSimulationBetsPlaced() + placed);
        session.setSimulationBetsSettled(session.getSimulationBetsSettled() + settled);
        session.setSimulationBetsVoided(session.getSimulationBetsVoided() + voided);
        AdaptiveProfile postSyncProfile = buildAdaptiveProfile(session);
        applyAdaptiveSnapshot(session, postSyncProfile, LocalDateTime.now());
        sessionRepository.save(session);

        return new PaperTradingSyncResultDto(
                strategy,
                modelVersion,
                rows.size(),
                placed,
                skipped,
                settled,
                voided,
                LocalDateTime.now(),
                buildSessionDto(session, 20, 40)
        );
    }

    @Transactional(readOnly = true)
    public PaperTradingSessionDto getSessionSnapshot() {
        PaperTradeSession session = getOrCreateActiveSession();
        return buildSessionDto(session, 20, 40);
    }

    @Transactional(readOnly = true)
    public List<LiveOddsRecommendationDto> getLiveStudioBoard(String strategyRaw,
                                                              String modelVersionRaw,
                                                              Integer limit,
                                                              boolean includeUnresolved) {
        String strategy = normalizeStrategy(strategyRaw);
        String modelVersion = StringUtils.hasText(modelVersionRaw) ? modelVersionRaw.trim() : "ENSEMBLE";
        int take = clamp(limit == null ? liveRowsLimit : limit, 5, 250);
        return oddsValueEngineService.liveOddsRecommendations(strategy, modelVersion, take, includeUnresolved);
    }

    @Transactional
    public List<PaperTradeBetDto> getLiveStudioOpenBets() {
        PaperTradeSession session = getOrCreateActiveSession();
        return betRepository.findBySessionIdAndStatusOrderByPlacedAtDesc(session.getId(), PaperTradeBet.STATUS_OPEN)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public List<PaperTradeBetDto> getLiveStudioSettledTape(int limit) {
        PaperTradeSession session = getOrCreateActiveSession();
        int take = clamp(limit, 5, 200);
        return betRepository.findBySessionIdAndStatusInOrderByPlacedAtDesc(
                        session.getId(),
                        List.of(
                                PaperTradeBet.STATUS_WON,
                                PaperTradeBet.STATUS_LOST,
                                PaperTradeBet.STATUS_PUSHED,
                                PaperTradeBet.STATUS_VOIDED
                        ),
                        PageRequest.of(0, take)
                )
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public LiveStudioIntegrityDto getLiveStudioIntegrity() {
        PaperTradeSession session = getOrCreateActiveSession();
        long trackedObservations = trackedMatchObservationRepository.countBySessionIdAndSourceKind(
                session.getId(),
                OBSERVATION_SOURCE_MARKET_BOARD
        ) + trackedMatchObservationRepository.countBySessionIdAndSourceKind(
                session.getId(),
                OBSERVATION_SOURCE_SCORE_FEED
        );
        long boardObservations = trackedMatchObservationRepository.countBySessionIdAndSourceKind(
                session.getId(),
                OBSERVATION_SOURCE_MARKET_BOARD
        );
        long scoreFeedObservations = trackedMatchObservationRepository.countBySessionIdAndSourceKind(
                session.getId(),
                OBSERVATION_SOURCE_SCORE_FEED
        );
        long trackedAfterCloseObservations = trackedMatchObservationRepository.findBySessionIdOrderByObservedAtDesc(
                        session.getId(),
                        PageRequest.of(0, 5000)
                ).stream()
                .filter(TrackedMatchObservation::isTrackedAfterClose)
                .count();

        List<PaperTradeBet> settledBets = betRepository.findBySessionIdAndStatusInOrderBySettledAtAsc(
                session.getId(),
                List.of(
                        PaperTradeBet.STATUS_WON,
                        PaperTradeBet.STATUS_LOST,
                        PaperTradeBet.STATUS_PUSHED,
                        PaperTradeBet.STATUS_VOIDED
                )
        );
        long scoreBacked = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE)
                        || matchesSettlementSource(bet, SETTLEMENT_SOURCE_HEURISTIC_FALLBACK))
                .count();
        long targetedCompletion = settledBets.stream()
                .filter(this::isTargetedCompletionSettlement)
                .count();
        long officialResult = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_OFFICIAL_RESULT))
                .count();
        long databaseResult = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_DATABASE_RESULT))
                .count();
        long heuristic = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_HEURISTIC_FALLBACK))
                .count();
        long voided = settledBets.stream()
                .filter(bet -> matchesSettlementSource(bet, SETTLEMENT_SOURCE_TIMEOUT_VOID)
                        || PaperTradeBet.STATUS_VOIDED.equalsIgnoreCase(safeText(bet.getStatus(), "")))
                .count();

        return new LiveStudioIntegrityDto(
                trackedObservations,
                boardObservations,
                scoreFeedObservations,
                trackedAfterCloseObservations,
                scoreBacked,
                targetedCompletion,
                officialResult,
                databaseResult,
                heuristic,
                voided
        );
    }

    @Transactional(readOnly = true)
    public List<TrackedMatchObservationDto> getMatchTimeline(String eventKey) {
        if (!StringUtils.hasText(eventKey)) {
            return List.of();
        }
        return trackedMatchObservationRepository.findByEventKeyOrderByObservedAtAsc(eventKey.trim())
                .stream()
                .map(this::toObservationDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompletedMatchLogDto> recentCompletedMatchesLog(int days, int limit) {
        int withinDays = clamp(days, 1, 30);
        int take = clamp(limit, 10, 400);
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(withinDays);

        List<Match> completed = new ArrayList<>(matchRepository.findCompletedMatchesBetween(fromDate, toDate));
        completed.sort(Comparator
                .comparing(Match::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Match::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        if (completed.size() > take) {
            completed = completed.subList(0, take);
        }

        Long activeSessionId = sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE)
                .map(PaperTradeSession::getId)
                .orElse(null);

        List<CompletedMatchLogDto> out = new ArrayList<>();
        for (Match match : completed) {
            Optional<PaperTradeBet> activePick = Optional.empty();
            if (activeSessionId != null && match.getId() != null) {
                activePick = betRepository.findFirstBySessionIdAndResultMatchIdOrderByIdAsc(activeSessionId, match.getId());
            }
            Optional<PaperTradeBet> historicalPick = match.getId() == null
                    ? Optional.empty()
                    : betRepository.findFirstByResultMatchIdOrderBySettledAtDesc(match.getId());
            Optional<PaperTradeBet> pick = activePick.or(() -> historicalPick);

            String p1 = match.getPlayer1() == null ? "Player 1" : match.getPlayer1().getName();
            String p2 = match.getPlayer2() == null ? "Player 2" : match.getPlayer2().getName();
            String winner = winnerName(match, p1, p2);
            String loser = loserName(match, p1, p2, winner);
            String score = scoreLabel(match);

            String matchDateIso = match.getDate() == null ? null : match.getDate().toString();
            String startTimeIso = pick.map(PaperTradeBet::getStartTimeIso)
                    .filter(StringUtils::hasText)
                    .or(() -> historicalPick.map(PaperTradeBet::getStartTimeIso).filter(StringUtils::hasText))
                    .orElse(null);

            out.add(new CompletedMatchLogDto(
                    match.getId(),
                    p1 + " vs " + p2,
                    matchDateIso,
                    startTimeIso,
                    p1,
                    p2,
                    winner,
                    loser,
                    score,
                    activePick.isPresent() || historicalPick.isPresent(),
                    pick.map(PaperTradeBet::getStatus).orElse(null)
            ));
        }
        return out;
    }

    @Transactional
    public PaperTradingSessionDto resetSession(Double startingBankroll, String label) {
        return resetSession(startingBankroll, label, false);
    }

    @Transactional
    public PaperTradingSessionDto resetSession(Double startingBankroll, String label, boolean clearHistory) {
        if (clearHistory) {
            trackedMatchObservationRepository.deleteAllInBatch();
            decisionSampleRepository.deleteAllInBatch();
            betRepository.deleteAllInBatch();
            sessionRepository.deleteAllInBatch();
            PaperTradeSession created = createSession(startingBankroll, label);
            AdaptiveProfile profile = buildAdaptiveProfile(created);
            applyAdaptiveSnapshot(created, profile, LocalDateTime.now());
            sessionRepository.save(created);
            return buildSessionDto(created, 20, 40);
        }

        List<PaperTradeSession> activeSessions = sessionRepository.findByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
        if (!activeSessions.isEmpty()) {
            activeSessions.forEach(active -> active.setStatus(PaperTradeSession.STATUS_CLOSED));
            sessionRepository.saveAll(activeSessions);
        }

        PaperTradeSession created = createSession(startingBankroll, label);
        AdaptiveProfile profile = buildAdaptiveProfile(created);
        applyAdaptiveSnapshot(created, profile, LocalDateTime.now());
        sessionRepository.save(created);
        return buildSessionDto(created, 20, 40);
    }

    private SettlementStats settleOpenBets(PaperTradeSession session, List<LiveOddsRecommendationDto> rows) {
        List<PaperTradeBet> openBets = betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(
                session.getId(),
                PaperTradeBet.STATUS_OPEN
        );
        RowLookup rowLookup = buildRowLookup(rows);
        OfficialResultRefreshContext officialResultRefreshContext = new OfficialResultRefreshContext();
        LocalDateTime now = LocalDateTime.now();
        int minMissingForScoreSettle = clamp(minMissingObservationsForScoreSettle, 1, 10);
        int settled = 0;
        int voided = 0;
        for (PaperTradeBet bet : openBets) {
            if (bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
                continue;
            }
            LocalDate targetDate = settlementTargetDate(bet);
            String scoreBeforeUpdate = bet.getLastObservedScore();
            String phaseBeforeUpdate = bet.getLastObservedPhase();
            LiveOddsRecommendationDto currentRow = findCurrentRowForBet(bet, rowLookup);
            String currentScore = normalizeScoreForBet(
                    bet,
                    currentRow == null ? null : currentRow.liveScore(),
                    currentRow == null ? null : currentRow.player1Id(),
                    currentRow == null ? null : currentRow.player1Name(),
                    currentRow == null ? null : currentRow.player2Id(),
                    currentRow == null ? null : currentRow.player2Name()
            );
            boolean changed = updateLastObservedFromRow(bet, currentRow, currentScore, now);

            if (currentRow != null) {
                recordObservation(session.getId(), bet, currentRow, currentScore, now);
                boolean settlementWindowOpen = isSettlementWindowOpen(bet, targetDate);
                boolean allowScoreSettlementWithoutWindow = settlementWindowOpen
                        || shouldBypassSettlementWindowForCurrentRow(bet, currentRow, currentScore, now);
                boolean previousScorePresent = StringUtils.hasText(scoreBeforeUpdate);
                boolean currentScorePresent = StringUtils.hasText(currentScore);
                boolean scoreChanged = currentScorePresent && textChanged(scoreBeforeUpdate, currentScore);
                boolean scoreDropped = previousScorePresent && !currentScorePresent;
                boolean phaseChanged = textChanged(phaseBeforeUpdate, currentRow.matchPhase());
                boolean phaseDegraded = phaseChanged && isPhaseDegradation(phaseBeforeUpdate, currentRow.matchPhase());
                if (scoreChanged || (phaseChanged && !phaseDegraded)) {
                    if (bet.getMissingBoardCount() != 0) {
                        bet.setMissingBoardCount(0);
                        changed = true;
                    }
                } else if (shouldTrackStaleObservation(currentRow) || scoreDropped || phaseDegraded) {
                    int nextMissing = Math.max(0, bet.getMissingBoardCount()) + 1;
                    if (bet.getMissingBoardCount() != nextMissing) {
                        bet.setMissingBoardCount(nextMissing);
                        changed = true;
                    }
                }
                if (allowScoreSettlementWithoutWindow) {
                    boolean finishedPhase = isFinishedPhase(currentRow.matchPhase());
                    Optional<Long> winnerFromCurrent = determineWinnerFromScore(
                            currentScore,
                            bet.getPlayer1Id(),
                            bet.getPlayer2Id(),
                            currentRow.matchPhase(),
                            false
                    );
                    if (winnerFromCurrent.isPresent()) {
                        String reason = isTargetedCompletedScoreRow(currentRow)
                                ? "SETTLED_FROM_TARGETED_MATCH_COMPLETED"
                                : (finishedPhase
                                ? "SETTLED_FROM_FINISHED_LIVE_SCORE"
                                : "SETTLED_FROM_DECISIVE_LIVE_SCORE");
                        applySettlement(session, bet, winnerFromCurrent.get(), null, reason);
                        betRepository.save(bet);
                        settled++;
                        continue;
                    }
                    if (finishedPhase) {
                        Optional<Long> winnerFromCurrentLenient = determineWinnerFromScore(
                                currentScore,
                                bet.getPlayer1Id(),
                                bet.getPlayer2Id(),
                                currentRow.matchPhase(),
                                true
                        );
                        if (winnerFromCurrentLenient.isPresent()) {
                            applySettlement(session, bet, winnerFromCurrentLenient.get(), null, "SETTLED_FROM_FINISHED_LIVE_SCORE_LENIENT");
                            betRepository.save(bet);
                            settled++;
                            continue;
                        }

                        String fallbackScore = StringUtils.hasText(scoreBeforeUpdate) ? scoreBeforeUpdate : bet.getLastObservedScore();
                        String fallbackPhase = StringUtils.hasText(currentRow.matchPhase())
                                ? currentRow.matchPhase()
                                : (StringUtils.hasText(phaseBeforeUpdate) ? phaseBeforeUpdate : bet.getLastObservedPhase());
                        Optional<Long> winnerFromLastFinished = determineWinnerFromScore(
                                fallbackScore,
                                bet.getPlayer1Id(),
                                bet.getPlayer2Id(),
                                fallbackPhase,
                                true
                        );
                        if (winnerFromLastFinished.isPresent()) {
                            applySettlement(session, bet, winnerFromLastFinished.get(), null, "SETTLED_FROM_FINISHED_PHASE_LAST_SCORE");
                            betRepository.save(bet);
                            settled++;
                            continue;
                        }
                    }
                    Optional<Long> winnerFromTargetedCompletion = determineWinnerFromTargetedCompletionSignal(
                            bet,
                            currentRow,
                            currentScore,
                            scoreBeforeUpdate
                    );
                    if (winnerFromTargetedCompletion.isPresent()) {
                        applySettlement(session, bet, winnerFromTargetedCompletion.get(), null, "SETTLED_FROM_TARGETED_MATCH_COMPLETED");
                        betRepository.save(bet);
                        settled++;
                        continue;
                    }
                    String staleScore = StringUtils.hasText(currentScore)
                            ? currentScore
                            : bet.getLastObservedScore();
                    Optional<Long> winnerFromStaleOnBoard = determineWinnerFromNearFinishFallback(
                            staleScore,
                            bet.getPlayer1Id(),
                            bet.getPlayer2Id()
                    );
                    if (winnerFromStaleOnBoard.isPresent()
                            && shouldAllowStaleOnBoardFallback(bet, currentRow, currentScore, now)
                            && canSettleFromLastObservation(bet, now)) {
                        applySettlement(session, bet, winnerFromStaleOnBoard.get(), null, "SETTLED_FROM_STALE_ONBOARD_SCORE");
                        betRepository.save(bet);
                        settled++;
                        continue;
                    }
                }
                if (changed) {
                    betRepository.save(bet);
                }
                continue;
            }

            Optional<TrackedMatchObservation> latestTrackedObservation = preferredTrackedObservationForBet(bet);
            if (latestTrackedObservation.isPresent()) {
                changed = applyLatestTrackedObservation(bet, latestTrackedObservation.get()) || changed;
                if (shouldHoldOpenWithTrackedObservation(latestTrackedObservation.get(), now)) {
                    if (bet.getMissingBoardCount() != 0) {
                        bet.setMissingBoardCount(0);
                        changed = true;
                    }
                    if (changed) {
                        betRepository.save(bet);
                    }
                    continue;
                }
            }

            bet.setMissingBoardCount(Math.max(0, bet.getMissingBoardCount()) + 1);
            changed = true;
            boolean settlementWindowOpen = isSettlementWindowOpen(bet, targetDate);
            boolean allowScoreSettlementWithoutWindow = settlementWindowOpen
                    || shouldBypassSettlementWindowForLastScore(bet, now);
            boolean allowLenientFromLastScore = true;
            boolean overdueForBackfill = isOverdueForLastScoreBackfill(bet, now);

            Optional<Long> winnerFromLastFastPath = determineWinnerFromScore(
                    bet.getLastObservedScore(),
                    bet.getPlayer1Id(),
                    bet.getPlayer2Id(),
                    bet.getLastObservedPhase(),
                    allowLenientFromLastScore
            );
            if (winnerFromLastFastPath.isPresent()
                    && allowScoreSettlementWithoutWindow
                    && (bet.getMissingBoardCount() >= minMissingForScoreSettle || overdueForBackfill)
                    && canSettleFromLastObservation(bet, now)) {
                applySettlement(session, bet, winnerFromLastFastPath.get(), null, "SETTLED_FROM_LAST_SCORE_HEURISTIC");
                betRepository.save(bet);
                settled++;
                continue;
            }

            if (!settlementWindowOpen) {
                if (changed) {
                    betRepository.save(bet);
                }
                continue;
            }

            Optional<Long> winnerFromLast = determineWinnerFromScore(
                    bet.getLastObservedScore(),
                    bet.getPlayer1Id(),
                    bet.getPlayer2Id(),
                    bet.getLastObservedPhase(),
                    allowLenientFromLastScore
            );
            if (winnerFromLast.isPresent()
                    && (bet.getMissingBoardCount() >= minMissingForScoreSettle || overdueForBackfill)
                    && canSettleFromLastObservation(bet, now)) {
                applySettlement(session, bet, winnerFromLast.get(), null, "SETTLED_FROM_LAST_SCORE_WINDOW");
                betRepository.save(bet);
                settled++;
                continue;
            }

            Optional<Long> winnerFromNearFinishScore = determineWinnerFromNearFinishFallback(
                    bet.getLastObservedScore(),
                    bet.getPlayer1Id(),
                    bet.getPlayer2Id()
            );
            if (winnerFromNearFinishScore.isPresent()
                    && shouldAllowNearFinishFallback(bet, now)
                    && canSettleFromLastObservation(bet, now)) {
                applySettlement(session, bet, winnerFromNearFinishScore.get(), null, "SETTLED_FROM_NEAR_FINISH_LAST_SCORE");
                betRepository.save(bet);
                settled++;
                continue;
            }

            boolean trackedAfterCloseDatabaseContext = isTrackedAfterCloseDatabaseContext(
                    bet,
                    latestTrackedObservation,
                    now
            );
            boolean allowTrackedAfterCloseDatabaseFallback = databaseSettlementFallbackForTrackedAfterClose
                    && trackedAfterCloseDatabaseContext;
            Match officialResultMatch = resolveOfficialResultSettlementCandidate(
                    session.getId(),
                    bet,
                    targetDate,
                    now,
                    trackedAfterCloseDatabaseContext,
                    officialResultRefreshContext
            );
            if (officialResultMatch != null) {
                boolean feedIdentityMatch = matchesFeedIdentity(bet, officialResultMatch);
                boolean trackedAfterCloseEvidence = hasTrackedAfterCloseDatabaseEvidence(bet, latestTrackedObservation);
                String settlementReason;
                if (feedIdentityMatch && trackedAfterCloseEvidence) {
                    settlementReason = "SETTLED_FROM_OFFICIAL_RESULT_FEED_IDENTITY_TRACKED_AFTER_CLOSE";
                } else if (feedIdentityMatch) {
                    settlementReason = "SETTLED_FROM_OFFICIAL_RESULT_FEED_IDENTITY";
                } else if (trackedAfterCloseEvidence) {
                    settlementReason = "SETTLED_FROM_OFFICIAL_RESULT_TRACKED_AFTER_CLOSE";
                } else {
                    settlementReason = "SETTLED_FROM_OFFICIAL_RESULT";
                }
                applySettlement(session, bet, officialResultMatch, settlementReason);
                betRepository.save(bet);
                settled++;
                continue;
            }
            if (databaseSettlementFallback || allowTrackedAfterCloseDatabaseFallback) {
                Match resolvedMatch = resolveDatabaseSettlementCandidate(session.getId(), bet, targetDate);
                if (resolvedMatch != null) {
                    boolean feedIdentityMatch = matchesFeedIdentity(bet, resolvedMatch);
                    boolean trackedAfterCloseEvidence = hasTrackedAfterCloseDatabaseEvidence(bet, latestTrackedObservation);
                    String settlementReason;
                    if (feedIdentityMatch && trackedAfterCloseEvidence) {
                        settlementReason = "SETTLED_FROM_DATABASE_RESULT_FEED_IDENTITY_TRACKED_AFTER_CLOSE";
                    } else if (feedIdentityMatch) {
                        settlementReason = "SETTLED_FROM_DATABASE_RESULT_FEED_IDENTITY";
                    } else if (trackedAfterCloseEvidence) {
                        settlementReason = "SETTLED_FROM_DATABASE_RESULT_TRACKED_AFTER_CLOSE";
                    } else {
                        settlementReason = "SETTLED_FROM_DATABASE_RESULT";
                    }
                    applySettlement(session, bet, resolvedMatch, settlementReason);
                    betRepository.save(bet);
                    settled++;
                    continue;
                }
            }

            if (shouldVoidMissingBoardBet(bet, targetDate, now)) {
                applySettlement(session, bet, null, null, "VOIDED_MISSING_BOARD_TIMEOUT");
                betRepository.save(bet);
                settled++;
                voided++;
                continue;
            }

            if (changed) {
                betRepository.save(bet);
            }
        }
        return new SettlementStats(settled, voided);
    }

    private void applySettlement(PaperTradeSession session, PaperTradeBet bet, Match match) {
        applySettlement(session, bet, match, "SETTLED_FROM_DATABASE_RESULT");
    }

    private void applySettlement(PaperTradeSession session, PaperTradeBet bet, Match match, String settlementReason) {
        applySettlement(
                session,
                bet,
                match == null ? null : match.getWinnerPlayerId(),
                match == null ? null : match.getId(),
                settlementReason
        );
    }

    private void applySettlement(PaperTradeSession session, PaperTradeBet bet, Long winnerPlayerId, Long resultMatchId) {
        applySettlement(session, bet, winnerPlayerId, resultMatchId, null);
    }

    private void applySettlement(PaperTradeSession session,
                                 PaperTradeBet bet,
                                 Long winnerPlayerId,
                                 Long resultMatchId,
                                 String settlementReason) {
        LocalDateTime now = LocalDateTime.now();
        bet.setResultMatchId(resultMatchId);
        bet.setWinnerPlayerId(winnerPlayerId);
        bet.setSettledAt(now);
        bet.setSettlementReason(StringUtils.hasText(settlementReason) ? settlementReason.trim() : null);
        bet.setSettlementSource(determineSettlementSource(settlementReason));

        double returned;
        double pnl;

        if (winnerPlayerId == null || bet.getSidePlayerId() == null) {
            if (StringUtils.hasText(settlementReason) && settlementReason.toUpperCase(Locale.ROOT).contains("VOID")) {
                bet.setStatus(PaperTradeBet.STATUS_VOIDED);
            } else {
                bet.setStatus(PaperTradeBet.STATUS_PUSHED);
                session.setPushes(session.getPushes() + 1);
            }
            returned = bet.getStake();
            pnl = 0.0;
        } else if (Objects.equals(winnerPlayerId, bet.getSidePlayerId())) {
            bet.setStatus(PaperTradeBet.STATUS_WON);
            returned = round2(bet.getStake() * bet.getDecimalOdds());
            pnl = round2(returned - bet.getStake());
            session.setWins(session.getWins() + 1);
        } else {
            bet.setStatus(PaperTradeBet.STATUS_LOST);
            returned = 0.0;
            pnl = -round2(bet.getStake());
            session.setLosses(session.getLosses() + 1);
        }

        bet.setProfitLoss(pnl);
        session.setTotalReturned(round2(session.getTotalReturned() + returned));
        session.setRealizedPnl(round2(session.getRealizedPnl() + pnl));
        session.setCurrentBankroll(round2(session.getCurrentBankroll() + returned));
        session.setPeakBankroll(Math.max(session.getPeakBankroll(), session.getCurrentBankroll()));
        persistLearningSample(bet);
    }

    private String determineSettlementSource(String settlementReason) {
        if (!StringUtils.hasText(settlementReason)) {
            return null;
        }
        String reason = settlementReason.trim().toUpperCase(Locale.ROOT);
        if (reason.contains("DECISIVE_LIVE_SCORE")
                || reason.contains("FINISHED_LIVE_SCORE")
                || reason.contains("TARGETED_MATCH_COMPLETED")) {
            return SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE;
        }
        if (reason.contains("DATABASE")) {
            return SETTLEMENT_SOURCE_DATABASE_RESULT;
        }
        if (reason.contains("VOID")) {
            return SETTLEMENT_SOURCE_TIMEOUT_VOID;
        }
        if (reason.contains("LAST_SCORE") || reason.contains("NEAR_FINISH") || reason.contains("STALE_ONBOARD")) {
            return SETTLEMENT_SOURCE_HEURISTIC_FALLBACK;
        }
        if (reason.contains("OFFICIAL") || reason.contains("RESULT")) {
            return SETTLEMENT_SOURCE_OFFICIAL_RESULT;
        }
        return SETTLEMENT_SOURCE_HEURISTIC_FALLBACK;
    }

    private boolean isTargetedCompletionSettlement(PaperTradeBet bet) {
        if (bet == null || !StringUtils.hasText(bet.getSettlementReason())) {
            return false;
        }
        return bet.getSettlementReason().trim().toUpperCase(Locale.ROOT).contains("TARGETED_MATCH_COMPLETED");
    }

    private boolean isTrackedAfterCloseDatabaseContext(PaperTradeBet bet,
                                                       Optional<TrackedMatchObservation> latestTrackedObservation,
                                                       LocalDateTime now) {
        if (bet == null || !bet.isTrackedAfterClose()) {
            return false;
        }
        if (latestTrackedObservation.isPresent()) {
            TrackedMatchObservation observation = latestTrackedObservation.get();
            if (shouldHoldOpenWithTrackedObservation(observation, now)) {
                return false;
            }
            return StringUtils.hasText(observation.getLiveScore())
                    || StringUtils.hasText(observation.getMatchPhase());
        }
        return StringUtils.hasText(bet.getLastObservedScore())
                || StringUtils.hasText(bet.getLastObservedPhase());
    }

    private boolean hasTrackedAfterCloseDatabaseEvidence(PaperTradeBet bet,
                                                         Optional<TrackedMatchObservation> latestTrackedObservation) {
        if (bet == null || !bet.isTrackedAfterClose()) {
            return false;
        }
        if (latestTrackedObservation.isPresent()) {
            TrackedMatchObservation observation = latestTrackedObservation.get();
            return StringUtils.hasText(observation.getLiveScore())
                    || StringUtils.hasText(observation.getMatchPhase())
                    || observation.isTrackedAfterClose();
        }
        return StringUtils.hasText(bet.getLastObservedScore())
                || StringUtils.hasText(bet.getLastObservedPhase());
    }

    private List<LiveOddsRecommendationDto> mergeSettlementRows(List<LiveOddsRecommendationDto> rows,
                                                                List<LiveScoreSnapshotDto> scoreSnapshots) {
        boolean noOddsRows = rows == null || rows.isEmpty();
        boolean noScoreRows = scoreSnapshots == null || scoreSnapshots.isEmpty();
        if (noOddsRows && noScoreRows) {
            return List.of();
        }

        List<LiveOddsRecommendationDto> merged = new ArrayList<>();
        if (!noOddsRows) {
            merged.addAll(rows);
        }
        if (!noScoreRows) {
            for (LiveScoreSnapshotDto snapshot : scoreSnapshots) {
                LiveOddsRecommendationDto row = toScoreboardSettlementRow(snapshot);
                if (row != null) {
                    merged.add(row);
                }
            }
        }
        return merged;
    }

    private LiveOddsRecommendationDto toScoreboardSettlementRow(LiveScoreSnapshotDto snapshot) {
        if (snapshot == null
                || !StringUtils.hasText(snapshot.player1Name())
                || !StringUtils.hasText(snapshot.player2Name())) {
            return null;
        }
        String matchupKey = StringUtils.hasText(snapshot.matchupKey())
                ? snapshot.matchupKey().trim()
                : toPairStartKey(
                snapshot.player1Id(),
                snapshot.player1Name(),
                snapshot.player2Id(),
                snapshot.player2Name(),
                snapshot.startTimeIso()
        );
        return new LiveOddsRecommendationDto(
                safeText(snapshot.source(), "HARD_ROCK_SCORE"),
                "SCOREBOARD",
                "SCOREBOARD",
                safeText(snapshot.eventName(), snapshot.player1Name() + " vs " + snapshot.player2Name()),
                safeText(snapshot.competitionName(), "Table Tennis"),
                snapshot.live(),
                snapshot.startTimeIso(),
                snapshot.liveScore(),
                safeText(snapshot.matchPhase(), snapshot.live() ? "LIVE" : "UPCOMING"),
                snapshot.player1Id(),
                snapshot.player1Name(),
                snapshot.player2Id(),
                snapshot.player2Name(),
                2.0,
                2.0,
                100,
                100,
                0.5,
                0.5,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "N/A",
                "Scoreboard fallback row",
                null,
                null,
                null,
                null,
                null,
                null,
                matchupKey,
                null,
                snapshot.sourceType(),
                snapshot.sourceConfidence(),
                snapshot.externalEventId(),
                snapshot.displayed(),
                snapshot.resulted(),
                snapshot.matchCompleted(),
                snapshot.sourceFeedCode(),
                snapshot.sourceFeedEventId(),
                snapshot.scoreDetail()
        );
    }

    private Set<String> collectOpenBetExternalEventIds(Long sessionId) {
        if (sessionId == null) {
            return Set.of();
        }
        List<PaperTradeBet> openBets = betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(
                sessionId,
                PaperTradeBet.STATUS_OPEN
        );
        if (openBets == null || openBets.isEmpty()) {
            return Set.of();
        }
        Set<String> eventIds = new HashSet<>();
        for (PaperTradeBet bet : openBets) {
            String eventId = bet == null
                    ? ""
                    : (StringUtils.hasText(bet.getExternalEventId())
                    ? bet.getExternalEventId()
                    : extractExternalEventId(bet.getSource()));
            if (StringUtils.hasText(eventId)) {
                eventIds.add(eventId);
            }
        }
        return eventIds;
    }

    private String extractExternalEventId(String source) {
        if (!StringUtils.hasText(source)) {
            return "";
        }
        Matcher matcher = SOURCE_EVENT_ID_PATTERN.matcher(source.trim());
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group(1);
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().replaceAll("[^A-Za-z0-9:_-]", "");
    }

    private String inferObservationSourceKind(LiveOddsRecommendationDto row) {
        if (row == null) {
            return OBSERVATION_SOURCE_MARKET_BOARD;
        }
        if (StringUtils.hasText(row.sourceType())) {
            String sourceType = row.sourceType().trim().toUpperCase(Locale.ROOT);
            if (sourceType.contains("SCORE")) {
                return OBSERVATION_SOURCE_SCORE_FEED;
            }
        }
        if (!StringUtils.hasText(row.source())) {
            return OBSERVATION_SOURCE_MARKET_BOARD;
        }
        String source = row.source().trim().toUpperCase(Locale.ROOT);
        if (source.contains("SCORE")) {
            return OBSERVATION_SOURCE_SCORE_FEED;
        }
        return OBSERVATION_SOURCE_MARKET_BOARD;
    }

    private double observationSourceConfidence(LiveOddsRecommendationDto row) {
        if (row == null) {
            return 0.50;
        }
        if (row.sourceConfidence() != null) {
            return clamp(row.sourceConfidence(), 0.0, 1.0);
        }
        String sourceKind = inferObservationSourceKind(row);
        if (OBSERVATION_SOURCE_SCORE_FEED.equals(sourceKind)) {
            return hasExplicitCompletionSignal(row) ? 0.98 : (isFinishedPhase(row.matchPhase()) ? 0.95 : 0.88);
        }
        return StringUtils.hasText(row.liveScore()) ? 0.72 : 0.60;
    }

    private boolean isTrackedAfterCloseObservation(LiveOddsRecommendationDto row) {
        if (row == null) {
            return false;
        }
        if (!OBSERVATION_SOURCE_SCORE_FEED.equals(inferObservationSourceKind(row))) {
            return false;
        }
        return !row.displayed() || row.resulted() || row.matchCompleted();
    }

    private void recordObservation(Long sessionId,
                                   PaperTradeBet bet,
                                   LiveOddsRecommendationDto row,
                                   String normalizedScore,
                                   LocalDateTime observedAt) {
        if (sessionId == null || bet == null || row == null || bet.getId() == null) {
            return;
        }
        String eventKey = StringUtils.hasText(bet.getEventKey())
                ? bet.getEventKey().trim()
                : buildEventKey(row);
        if (!StringUtils.hasText(eventKey)) {
            return;
        }
        String score = StringUtils.hasText(normalizedScore) ? normalizedScore.trim() : null;
        String phase = StringUtils.hasText(row.matchPhase()) ? row.matchPhase().trim() : null;
        String sourceKind = inferObservationSourceKind(row);
        Optional<TrackedMatchObservation> previous = trackedMatchObservationRepository.findTopByBetIdOrderByObservedAtDesc(bet.getId());
        if (previous.isPresent()) {
            TrackedMatchObservation last = previous.get();
            boolean sameEvent = eventKey.equals(safeText(last.getEventKey(), ""));
            boolean sameScore = safeText(score, "").equals(safeText(last.getLiveScore(), ""));
            boolean samePhase = safeText(phase, "").equals(safeText(last.getMatchPhase(), ""));
            boolean sameSourceKind = sourceKind.equals(safeText(last.getSourceKind(), ""));
            boolean sameLiveFlag = row.live() == last.isLive();
            boolean sameDisplayed = row.displayed() == last.isDisplayed();
            boolean sameResulted = row.resulted() == last.isResulted();
            boolean sameMatchCompleted = row.matchCompleted() == last.isMatchCompleted();
            boolean sameScoreDetail = safeText(row.scoreDetail(), "").equals(safeText(last.getScoreDetail(), ""));
            if (sameEvent
                    && sameScore
                    && samePhase
                    && sameSourceKind
                    && sameLiveFlag
                    && sameDisplayed
                    && sameResulted
                    && sameMatchCompleted
                    && sameScoreDetail) {
                return;
            }
        }

        TrackedMatchObservation observation = new TrackedMatchObservation();
        observation.setSessionId(sessionId);
        observation.setBetId(bet.getId());
        observation.setEventKey(eventKey);
        observation.setDedupeKey(bet.getDedupeKey());
        observation.setExternalEventId(StringUtils.hasText(bet.getExternalEventId())
                ? bet.getExternalEventId()
                : (StringUtils.hasText(row.externalEventId())
                ? row.externalEventId().trim()
                : extractExternalEventId(row.source())));
        observation.setSource(safeText(row.source(), "UNKNOWN"));
        observation.setSourceKind(sourceKind);
        observation.setSourceConfidence(observationSourceConfidence(row));
        observation.setDisplayed(row.displayed());
        observation.setResulted(row.resulted());
        observation.setMatchCompleted(row.matchCompleted());
        observation.setSourceFeedCode(StringUtils.hasText(row.sourceFeedCode()) ? row.sourceFeedCode().trim() : null);
        observation.setSourceFeedEventId(StringUtils.hasText(row.sourceFeedEventId()) ? row.sourceFeedEventId().trim() : null);
        observation.setLive(row.live());
        observation.setTrackedAfterClose(isTrackedAfterCloseObservation(row));
        observation.setEventName(safeText(row.eventName(), bet.getEventName()));
        observation.setCompetitionName(safeText(row.competitionName(), bet.getCompetitionName()));
        observation.setStartTimeIso(StringUtils.hasText(row.startTimeIso()) ? row.startTimeIso() : bet.getStartTimeIso());
        observation.setPlayer1Id(row.player1Id() != null ? row.player1Id() : bet.getPlayer1Id());
        observation.setPlayer1Name(safeText(row.player1Name(), bet.getPlayer1Name()));
        observation.setPlayer2Id(row.player2Id() != null ? row.player2Id() : bet.getPlayer2Id());
        observation.setPlayer2Name(safeText(row.player2Name(), bet.getPlayer2Name()));
        observation.setLiveScore(score);
        observation.setMatchPhase(phase);
        observation.setScoreDetail(StringUtils.hasText(row.scoreDetail()) ? row.scoreDetail().trim() : null);
        observation.setObservedAt(observedAt == null ? LocalDateTime.now() : observedAt);
        trackedMatchObservationRepository.save(observation);
    }

    private Optional<TrackedMatchObservation> latestTrackedObservationForBet(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null) {
            return Optional.empty();
        }
        return trackedMatchObservationRepository.findTopByBetIdOrderByObservedAtDesc(bet.getId());
    }

    private Optional<TrackedMatchObservation> preferredTrackedObservationForBet(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null) {
            return Optional.empty();
        }
        if (bet.isTrackedAfterClose()) {
            Optional<TrackedMatchObservation> trackedAfterCloseObservation =
                    trackedMatchObservationRepository.findTopByBetIdAndTrackedAfterCloseTrueOrderByObservedAtDesc(bet.getId());
            if (trackedAfterCloseObservation.isPresent()) {
                return trackedAfterCloseObservation;
            }
        }
        return latestTrackedObservationForBet(bet);
    }

    private boolean applyLatestTrackedObservation(PaperTradeBet bet, TrackedMatchObservation observation) {
        if (bet == null || observation == null) {
            return false;
        }
        boolean changed = false;

        if (StringUtils.hasText(observation.getExternalEventId())
                && !observation.getExternalEventId().equals(safeText(bet.getExternalEventId(), ""))) {
            bet.setExternalEventId(observation.getExternalEventId().trim());
            changed = true;
        }

        if (StringUtils.hasText(observation.getLiveScore())
                && !observation.getLiveScore().equals(safeText(bet.getLastObservedScore(), ""))) {
            bet.setLastObservedScore(observation.getLiveScore().trim());
            changed = true;
        }

        if (StringUtils.hasText(observation.getMatchPhase())) {
            String phase = observation.getMatchPhase().trim();
            String currentPhase = safeText(bet.getLastObservedPhase(), "");
            if (!phase.equals(currentPhase) && !isPhaseDegradation(currentPhase, phase)) {
                bet.setLastObservedPhase(phase);
                changed = true;
            }
        }

        if (StringUtils.hasText(observation.getSourceKind())
                && !observation.getSourceKind().equals(safeText(bet.getLastScoreSource(), ""))) {
            bet.setLastScoreSource(observation.getSourceKind());
            changed = true;
        }

        Double currentConfidence = bet.getLastScoreConfidence();
        if (currentConfidence == null || Math.abs(currentConfidence - observation.getSourceConfidence()) > EPS) {
            bet.setLastScoreConfidence(observation.getSourceConfidence());
            changed = true;
        }

        if (bet.isLastObservationDisplayed() != observation.isDisplayed()) {
            bet.setLastObservationDisplayed(observation.isDisplayed());
            changed = true;
        }

        if (bet.isLastObservationResulted() != observation.isResulted()) {
            bet.setLastObservationResulted(observation.isResulted());
            changed = true;
        }

        if (bet.isLastMatchCompleted() != observation.isMatchCompleted()) {
            bet.setLastMatchCompleted(observation.isMatchCompleted());
            changed = true;
        }

        String sourceFeedCode = StringUtils.hasText(observation.getSourceFeedCode()) ? observation.getSourceFeedCode().trim() : null;
        if (!safeText(sourceFeedCode, "").equals(safeText(bet.getLastSourceFeedCode(), ""))) {
            bet.setLastSourceFeedCode(sourceFeedCode);
            changed = true;
        }

        String sourceFeedEventId = StringUtils.hasText(observation.getSourceFeedEventId()) ? observation.getSourceFeedEventId().trim() : null;
        if (!safeText(sourceFeedEventId, "").equals(safeText(bet.getLastSourceFeedEventId(), ""))) {
            bet.setLastSourceFeedEventId(sourceFeedEventId);
            changed = true;
        }

        String scoreDetail = StringUtils.hasText(observation.getScoreDetail()) ? observation.getScoreDetail().trim() : null;
        if (!safeText(scoreDetail, "").equals(safeText(bet.getLastScoreDetail(), ""))) {
            bet.setLastScoreDetail(scoreDetail);
            changed = true;
        }

        if (bet.isTrackedAfterClose() != observation.isTrackedAfterClose()) {
            bet.setTrackedAfterClose(observation.isTrackedAfterClose());
            changed = true;
        }

        if (observation.getObservedAt() != null
                && (bet.getLastObservedAt() == null || observation.getObservedAt().isAfter(bet.getLastObservedAt()))) {
            bet.setLastObservedAt(observation.getObservedAt());
            changed = true;
        }

        return changed;
    }

    private boolean matchesSettlementSource(PaperTradeBet bet, String expectedSource) {
        if (bet == null || !StringUtils.hasText(expectedSource)) {
            return false;
        }
        return expectedSource.equalsIgnoreCase(safeText(bet.getSettlementSource(), ""));
    }

    private RowLookup buildRowLookup(List<LiveOddsRecommendationDto> rows) {
        Map<String, LiveOddsRecommendationDto> byDedupe = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> byEvent = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> byExternalEventId = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> byPairStart = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> byPair = new HashMap<>();
        List<LiveOddsRecommendationDto> allRows = new ArrayList<>();
        if (rows == null) {
            return new RowLookup(byDedupe, byEvent, byExternalEventId, byPairStart, byPair, allRows);
        }
        for (LiveOddsRecommendationDto row : rows) {
            if (row == null) {
                continue;
            }
            allRows.add(row);
            String dedupeKey = row.suggestedDedupeKey();
            if (!StringUtils.hasText(dedupeKey) && StringUtils.hasText(row.matchupKey()) && StringUtils.hasText(row.suggestedSide())) {
                dedupeKey = row.matchupKey().trim() + "|" + normalizeKey(row.suggestedSide());
            }
            putPreferredRow(byDedupe, dedupeKey, row);
            putPreferredRow(byEvent, row.matchupKey(), row);
            putPreferredRow(byEvent, buildEventKey(row), row);
            String externalEventId = StringUtils.hasText(row.externalEventId())
                    ? row.externalEventId().trim()
                    : extractExternalEventId(row.source());
            putPreferredRow(byExternalEventId, externalEventId, row);
            String pairStartKey = toPairStartKey(
                    row.player1Id(),
                    row.player1Name(),
                    row.player2Id(),
                    row.player2Name(),
                    row.startTimeIso()
            );
            putPreferredRow(byPairStart, pairStartKey, row);
            String namePairStartKey = toPairStartKey(
                    null,
                    row.player1Name(),
                    null,
                    row.player2Name(),
                    row.startTimeIso()
            );
            putPreferredRow(byPairStart, namePairStartKey, row);
            String pairKey = toPairKey(
                    row.player1Id(),
                    row.player1Name(),
                    row.player2Id(),
                    row.player2Name()
            );
            putPreferredRow(byPair, pairKey, row);
            String namePairKey = toPairKey(
                    null,
                    row.player1Name(),
                    null,
                    row.player2Name()
            );
            putPreferredRow(byPair, namePairKey, row);
        }
        return new RowLookup(byDedupe, byEvent, byExternalEventId, byPairStart, byPair, allRows);
    }

    private void putPreferredRow(Map<String, LiveOddsRecommendationDto> index,
                                 String rawKey,
                                 LiveOddsRecommendationDto candidate) {
        if (index == null || candidate == null || !StringUtils.hasText(rawKey)) {
            return;
        }
        String key = rawKey.trim();
        LiveOddsRecommendationDto current = index.get(key);
        if (current == null || preferSettlementRow(candidate, current)) {
            index.put(key, candidate);
        }
    }

    private boolean preferSettlementRow(LiveOddsRecommendationDto candidate, LiveOddsRecommendationDto current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        int candidateRank = settlementRowRank(candidate);
        int currentRank = settlementRowRank(current);
        if (candidateRank != currentRank) {
            return candidateRank > currentRank;
        }
        int candidatePairs = parseScorePairs(candidate.liveScore()).size();
        int currentPairs = parseScorePairs(current.liveScore()).size();
        if (candidatePairs != currentPairs) {
            return candidatePairs > currentPairs;
        }
        boolean candidateScoreSource = OBSERVATION_SOURCE_SCORE_FEED.equals(inferObservationSourceKind(candidate));
        boolean currentScoreSource = OBSERVATION_SOURCE_SCORE_FEED.equals(inferObservationSourceKind(current));
        if (candidateScoreSource != currentScoreSource) {
            return candidateScoreSource;
        }
        return false;
    }

    private int settlementRowRank(LiveOddsRecommendationDto row) {
        if (row == null) {
            return 0;
        }
        int rank = 0;
        if (StringUtils.hasText(row.liveScore())) {
            rank += 4;
        }
        if (hasExplicitCompletionSignal(row)) {
            rank += 5;
        }
        if (isFinishedPhase(row.matchPhase())) {
            rank += 3;
        }
        if (isLateLikePhase(row.matchPhase())) {
            rank += 2;
        }
        if (row.live()) {
            rank += 1;
        }
        return rank;
    }

    private LiveOddsRecommendationDto findCurrentRowForBet(PaperTradeBet bet, RowLookup lookup) {
        if (bet == null || lookup == null) {
            return null;
        }
        LiveOddsRecommendationDto best = null;
        String betExternalEventId = StringUtils.hasText(bet.getExternalEventId())
                ? bet.getExternalEventId().trim()
                : extractExternalEventId(bet.getSource());
        if (StringUtils.hasText(betExternalEventId)) {
            LiveOddsRecommendationDto byExternalEventId = lookup.byExternalEventId().get(betExternalEventId);
            if (byExternalEventId != null) {
                return byExternalEventId;
            }
        }
        if (StringUtils.hasText(bet.getDedupeKey())) {
            LiveOddsRecommendationDto byDedupe = lookup.byDedupe().get(bet.getDedupeKey().trim());
            if (byDedupe != null) {
                best = preferSettlementRow(byDedupe, best) ? byDedupe : best;
            }
            int sideSep = bet.getDedupeKey().lastIndexOf('|');
            if (sideSep > 0) {
                String dedupeEventKey = bet.getDedupeKey().substring(0, sideSep).trim();
                if (StringUtils.hasText(dedupeEventKey)) {
                    LiveOddsRecommendationDto byDedupeEvent = lookup.byEvent().get(dedupeEventKey);
                    if (byDedupeEvent != null) {
                        best = preferSettlementRow(byDedupeEvent, best) ? byDedupeEvent : best;
                    }
                }
            }
        }
        if (StringUtils.hasText(bet.getEventKey())) {
            LiveOddsRecommendationDto byEvent = lookup.byEvent().get(bet.getEventKey().trim());
            if (byEvent != null) {
                best = preferSettlementRow(byEvent, best) ? byEvent : best;
            }
        }
        String pairStartKey = toPairStartKey(
                bet.getPlayer1Id(),
                bet.getPlayer1Name(),
                bet.getPlayer2Id(),
                bet.getPlayer2Name(),
                bet.getStartTimeIso()
        );
        if (StringUtils.hasText(pairStartKey)) {
            LiveOddsRecommendationDto byPairStart = lookup.byPairStart().get(pairStartKey);
            if (byPairStart != null) {
                best = preferSettlementRow(byPairStart, best) ? byPairStart : best;
            }
        }
        String namePairStartKey = toPairStartKey(
                null,
                bet.getPlayer1Name(),
                null,
                bet.getPlayer2Name(),
                bet.getStartTimeIso()
        );
        if (StringUtils.hasText(namePairStartKey)) {
            LiveOddsRecommendationDto byNamePairStart = lookup.byPairStart().get(namePairStartKey);
            if (byNamePairStart != null) {
                best = preferSettlementRow(byNamePairStart, best) ? byNamePairStart : best;
            }
        }
        String pairKey = toPairKey(
                bet.getPlayer1Id(),
                bet.getPlayer1Name(),
                bet.getPlayer2Id(),
                bet.getPlayer2Name()
        );
        if (StringUtils.hasText(pairKey)) {
            LiveOddsRecommendationDto byPair = lookup.byPair().get(pairKey);
            if (byPair != null && isCompatibleStartTime(bet.getStartTimeIso(), byPair.startTimeIso())) {
                best = preferSettlementRow(byPair, best) ? byPair : best;
            }
        }
        String namePairKey = toPairKey(
                null,
                bet.getPlayer1Name(),
                null,
                bet.getPlayer2Name()
        );
        if (StringUtils.hasText(namePairKey)) {
            LiveOddsRecommendationDto byNamePair = lookup.byPair().get(namePairKey);
            if (byNamePair != null && isCompatibleStartTime(bet.getStartTimeIso(), byNamePair.startTimeIso())) {
                best = preferSettlementRow(byNamePair, best) ? byNamePair : best;
            }
        }
        LiveOddsRecommendationDto loose = findLooseRowForBet(bet, lookup);
        if (loose != null) {
            best = preferSettlementRow(loose, best) ? loose : best;
        }
        return best;
    }

    private LiveOddsRecommendationDto findLooseRowForBet(PaperTradeBet bet, RowLookup lookup) {
        if (bet == null || lookup == null) {
            return null;
        }
        String betA = normalizePersonToken(bet.getPlayer1Name());
        String betB = normalizePersonToken(bet.getPlayer2Name());
        if (!StringUtils.hasText(betA) || !StringUtils.hasText(betB) || "na".equals(betA) || "na".equals(betB)) {
            return null;
        }

        LiveOddsRecommendationDto fallback = null;
        LiveOddsRecommendationDto compatible = null;
        for (LiveOddsRecommendationDto row : lookup.allRows()) {
            if (row == null) {
                continue;
            }
            String rowA = normalizePersonToken(row.player1Name());
            String rowB = normalizePersonToken(row.player2Name());
            boolean strictPair = isSamePair(betA, betB, rowA, rowB);
            boolean loosePair = strictPair || isLoosePairNameMatch(bet, row);
            if (!loosePair) {
                continue;
            }
            if (isCompatibleStartTime(bet.getStartTimeIso(), row.startTimeIso())) {
                compatible = preferSettlementRow(row, compatible) ? row : compatible;
                continue;
            }
            fallback = preferSettlementRow(row, fallback) ? row : fallback;
        }
        return compatible != null ? compatible : fallback;
    }

    private boolean isLoosePairNameMatch(PaperTradeBet bet, LiveOddsRecommendationDto row) {
        if (bet == null || row == null) {
            return false;
        }
        return (isSameParticipantLoose(bet.getPlayer1Name(), row.player1Name())
                && isSameParticipantLoose(bet.getPlayer2Name(), row.player2Name()))
                || (isSameParticipantLoose(bet.getPlayer1Name(), row.player2Name())
                && isSameParticipantLoose(bet.getPlayer2Name(), row.player1Name()));
    }

    private boolean isSameParticipantLoose(String betName, String rowName) {
        if (!StringUtils.hasText(betName) || !StringUtils.hasText(rowName)) {
            return false;
        }
        if (NameUtils.areNamesSimilar(betName, rowName)) {
            return true;
        }

        String betLookup = NameUtils.normalizeForLookup(betName);
        String rowLookup = NameUtils.normalizeForLookup(rowName);
        if (!StringUtils.hasText(betLookup) || !StringUtils.hasText(rowLookup)) {
            return false;
        }
        if (betLookup.equals(rowLookup)) {
            return true;
        }

        String[] betParts = betLookup.split("\\s+");
        String[] rowParts = rowLookup.split("\\s+");
        if (betParts.length == 0 || rowParts.length == 0) {
            return false;
        }
        String betLast = betParts[betParts.length - 1];
        String rowLast = rowParts[rowParts.length - 1];
        if (!betLast.equals(rowLast)) {
            return false;
        }
        String betFirst = betParts[0];
        String rowFirst = rowParts[0];
        if (!StringUtils.hasText(betFirst) || !StringUtils.hasText(rowFirst)) {
            return true;
        }
        return betFirst.charAt(0) == rowFirst.charAt(0);
    }

    private boolean isSamePair(String a1, String a2, String b1, String b2) {
        if (!StringUtils.hasText(a1) || !StringUtils.hasText(a2) || !StringUtils.hasText(b1) || !StringUtils.hasText(b2)) {
            return false;
        }
        return (a1.equals(b1) && a2.equals(b2)) || (a1.equals(b2) && a2.equals(b1));
    }

    private boolean isCompatibleStartTime(String betStartIso, String rowStartIso) {
        if (!StringUtils.hasText(betStartIso) || !StringUtils.hasText(rowStartIso)) {
            return true;
        }
        Optional<LocalDateTime> betStart = parseStartDateTime(betStartIso);
        Optional<LocalDateTime> rowStart = parseStartDateTime(rowStartIso);
        if (betStart.isPresent() && rowStart.isPresent()) {
            long diffMinutes = Math.abs(ChronoUnit.MINUTES.between(betStart.get(), rowStart.get()));
            return diffMinutes <= 720;
        }
        return startBucket(betStartIso).equals(startBucket(rowStartIso));
    }

    private boolean updateLastObservedFromRow(PaperTradeBet bet,
                                              LiveOddsRecommendationDto row,
                                              String normalizedScore,
                                              LocalDateTime observedAt) {
        if (bet == null || row == null) {
            return false;
        }
        boolean changed = false;
        String inferredSource = inferObservationSourceKind(row);
        double sourceConfidence = observationSourceConfidence(row);
        if (StringUtils.hasText(row.startTimeIso())) {
            String startIso = row.startTimeIso().trim();
            if (shouldReplaceStartTimeIso(bet.getStartTimeIso(), startIso)) {
                bet.setStartTimeIso(startIso);
                changed = true;
            }
        }
        String externalEventId = StringUtils.hasText(row.externalEventId())
                ? row.externalEventId().trim()
                : extractExternalEventId(row.source());
        if (StringUtils.hasText(externalEventId) && !externalEventId.equals(safeText(bet.getExternalEventId(), ""))) {
            bet.setExternalEventId(externalEventId);
            changed = true;
        }

        if (StringUtils.hasText(normalizedScore)) {
            String score = normalizedScore.trim();
            if (!score.equals(safeText(bet.getLastObservedScore(), ""))) {
                bet.setLastObservedScore(score);
                changed = true;
            }
        }

        if (StringUtils.hasText(row.matchPhase())) {
            String phase = row.matchPhase().trim();
            String currentPhase = safeText(bet.getLastObservedPhase(), "");
            boolean preservePriorPhase = !StringUtils.hasText(normalizedScore)
                    && isPhaseDegradation(currentPhase, phase);
            if (!preservePriorPhase && !phase.equals(currentPhase)) {
                bet.setLastObservedPhase(phase);
                changed = true;
            }
        }

        if (!inferredSource.equals(safeText(bet.getLastScoreSource(), ""))) {
            bet.setLastScoreSource(inferredSource);
            changed = true;
        }
        Double currentConfidence = bet.getLastScoreConfidence();
        if (currentConfidence == null || Math.abs(currentConfidence - sourceConfidence) > EPS) {
            bet.setLastScoreConfidence(sourceConfidence);
            changed = true;
        }

        if (bet.isLastObservationDisplayed() != row.displayed()) {
            bet.setLastObservationDisplayed(row.displayed());
            changed = true;
        }

        if (bet.isLastObservationResulted() != row.resulted()) {
            bet.setLastObservationResulted(row.resulted());
            changed = true;
        }

        if (bet.isLastMatchCompleted() != row.matchCompleted()) {
            bet.setLastMatchCompleted(row.matchCompleted());
            changed = true;
        }

        String sourceFeedCode = StringUtils.hasText(row.sourceFeedCode()) ? row.sourceFeedCode().trim() : null;
        if (!safeText(sourceFeedCode, "").equals(safeText(bet.getLastSourceFeedCode(), ""))) {
            bet.setLastSourceFeedCode(sourceFeedCode);
            changed = true;
        }

        String sourceFeedEventId = StringUtils.hasText(row.sourceFeedEventId()) ? row.sourceFeedEventId().trim() : null;
        if (!safeText(sourceFeedEventId, "").equals(safeText(bet.getLastSourceFeedEventId(), ""))) {
            bet.setLastSourceFeedEventId(sourceFeedEventId);
            changed = true;
        }

        String scoreDetail = StringUtils.hasText(row.scoreDetail()) ? row.scoreDetail().trim() : null;
        if (!safeText(scoreDetail, "").equals(safeText(bet.getLastScoreDetail(), ""))) {
            bet.setLastScoreDetail(scoreDetail);
            changed = true;
        }

        boolean trackedAfterClose = isTrackedAfterCloseObservation(row);
        if (bet.isTrackedAfterClose() != trackedAfterClose) {
            bet.setTrackedAfterClose(trackedAfterClose);
            changed = true;
        }

        if (observedAt != null && (changed || bet.getLastObservedAt() == null)) {
            bet.setLastObservedAt(observedAt);
            changed = true;
        }
        return changed;
    }

    private boolean shouldReplaceStartTimeIso(String currentStartIso, String candidateStartIso) {
        if (!StringUtils.hasText(candidateStartIso)) {
            return false;
        }
        if (!StringUtils.hasText(currentStartIso)) {
            return true;
        }
        String current = currentStartIso.trim();
        String candidate = candidateStartIso.trim();
        if (candidate.equals(current)) {
            return false;
        }

        Optional<LocalDateTime> currentParsed = parseStartDateTime(current);
        Optional<LocalDateTime> candidateParsed = parseStartDateTime(candidate);
        if (currentParsed.isPresent() && candidateParsed.isPresent()) {
            return candidateParsed.get().isBefore(currentParsed.get());
        }
        if (currentParsed.isEmpty() && candidateParsed.isPresent()) {
            return true;
        }
        if (currentParsed.isPresent()) {
            return false;
        }
        return startBucket(candidate).compareTo(startBucket(current)) < 0;
    }

    private boolean shouldBypassSettlementWindowForCurrentRow(PaperTradeBet bet,
                                                              LiveOddsRecommendationDto row,
                                                              String normalizedScore,
                                                              LocalDateTime now) {
        if (bet == null || row == null || now == null) {
            return false;
        }
        boolean hasScoreContext = StringUtils.hasText(normalizedScore)
                || StringUtils.hasText(bet.getLastObservedScore());
        boolean hasLiveContext = row.live()
                || isLateLikePhase(row.matchPhase())
                || isFinishedPhase(row.matchPhase())
                || hasExplicitCompletionSignal(row)
                || isLateLikePhase(bet.getLastObservedPhase())
                || isFinishedPhase(bet.getLastObservedPhase());
        if (!(hasScoreContext && hasLiveContext)) {
            return false;
        }
        Optional<LocalDateTime> rowStart = parseStartDateTime(row.startTimeIso());
        if (rowStart.isPresent() && now.isBefore(rowStart.get().minusMinutes(5))) {
            return false;
        }
        Optional<LocalDateTime> betStart = parseStartDateTime(bet.getStartTimeIso());
        if (betStart.isPresent() && now.isBefore(betStart.get().minusMinutes(5))) {
            return false;
        }
        return true;
    }

    private boolean shouldBypassSettlementWindowForLastScore(PaperTradeBet bet, LocalDateTime now) {
        if (bet == null || now == null || !StringUtils.hasText(bet.getLastObservedScore())) {
            return false;
        }
        boolean hasLiveContext = isLateLikePhase(bet.getLastObservedPhase())
                || isFinishedPhase(bet.getLastObservedPhase());
        if (!hasLiveContext) {
            return false;
        }
        Optional<LocalDateTime> betStart = parseStartDateTime(bet.getStartTimeIso());
        return betStart.isEmpty() || !now.isBefore(betStart.get().minusMinutes(5));
    }

    private Optional<Long> determineWinnerFromScore(String rawScore,
                                                    Long player1Id,
                                                    Long player2Id,
                                                    String phaseRaw,
                                                    boolean allowLenientInference) {
        if (!StringUtils.hasText(rawScore) || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        List<ScorePair> parsed = parseScorePairs(rawScore);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        int targetSets = clamp(scoreSettlementTargetSets, 3, 7);
        int minMarginSets = clamp(scoreSettlementMinMarginSets, 1, 3);
        boolean finishedPhase = isFinishedPhase(phaseRaw);
        boolean latePhase = isLateLikePhase(phaseRaw);
        int setPairIndex = findPrimarySetScorePairIndex(parsed, targetSets);
        if (setPairIndex >= 0) {
            Optional<Long> strictSetWinner = winnerFromSetScorePair(parsed.get(setPairIndex), targetSets, player1Id, player2Id);
            if (strictSetWinner.isPresent()) {
                return strictSetWinner;
            }
        }

        if (!allowLenientInference) {
            return Optional.empty();
        }

        if (!latePhase && !finishedPhase) {
            return Optional.empty();
        }

        if (setPairIndex >= 0) {
            ScorePair setPair = parsed.get(setPairIndex);
            boolean tiedInFinalSet = setPair.left() == (targetSets - 1) && setPair.right() == (targetSets - 1);
            if ((finishedPhase || latePhase) && tiedInFinalSet) {
                Optional<ScorePair> pointScore = findPointScorePair(parsed, setPairIndex);
                if (pointScore.isPresent()) {
                    Optional<Long> inferred = winnerFromTiedFinalSetPoints(
                            pointScore.get(),
                            player1Id,
                            player2Id
                    );
                    if (inferred.isPresent()) {
                        return inferred;
                    }
                }
            }
            return Optional.empty();
        }

        Optional<ScorePair> pointOnly = findPointScorePair(parsed, -1);
        if (pointOnly.isEmpty()) {
            return Optional.empty();
        }
        if (!finishedPhase && parsed.size() > 1) {
            return Optional.empty();
        }
        return winnerFromPointScorePair(pointOnly.get(), minMarginSets, player1Id, player2Id);
    }

    private int findPrimarySetScorePairIndex(List<ScorePair> parsed, int targetSets) {
        if (parsed == null || parsed.isEmpty()) {
            return -1;
        }
        int maxTotalSets = Math.max(1, (targetSets * 2) - 1);
        for (int i = 0; i < parsed.size(); i++) {
            ScorePair pair = parsed.get(i);
            int top = Math.max(pair.left(), pair.right());
            int total = pair.left() + pair.right();
            if (top <= targetSets && total <= maxTotalSets) {
                return i;
            }
        }
        return -1;
    }

    private Optional<ScorePair> findPointScorePair(List<ScorePair> parsed, int setPairIndex) {
        if (parsed == null || parsed.isEmpty()) {
            return Optional.empty();
        }
        for (int i = parsed.size() - 1; i >= 0; i--) {
            if (i == setPairIndex) {
                continue;
            }
            return Optional.of(parsed.get(i));
        }
        return Optional.empty();
    }

    private Optional<Long> winnerFromSetScorePair(ScorePair score,
                                                  int targetSets,
                                                  Long player1Id,
                                                  Long player2Id) {
        if (score == null || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        int p1 = score.left();
        int p2 = score.right();
        if (p1 == p2) {
            return Optional.empty();
        }
        int top = Math.max(p1, p2);
        if (top < targetSets) {
            return Optional.empty();
        }
        return Optional.of(p1 > p2 ? player1Id : player2Id);
    }

    private Optional<Long> winnerFromPointScorePair(ScorePair score,
                                                    int minMarginSets,
                                                    Long player1Id,
                                                    Long player2Id) {
        if (score == null || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        int p1 = score.left();
        int p2 = score.right();
        if (p1 == p2) {
            return Optional.empty();
        }
        int top = Math.max(p1, p2);
        int margin = Math.abs(p1 - p2);
        if (top < 11 || margin < Math.max(2, minMarginSets)) {
            return Optional.empty();
        }
        return Optional.of(p1 > p2 ? player1Id : player2Id);
    }

    private Optional<Long> winnerFromTiedFinalSetPoints(ScorePair score,
                                                        Long player1Id,
                                                        Long player2Id) {
        if (score == null || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        int p1 = score.left();
        int p2 = score.right();
        if (p1 == p2) {
            return Optional.empty();
        }
        int top = Math.max(p1, p2);
        int margin = Math.abs(p1 - p2);
        int pointFloor = clamp(nearFinishFallbackPointFloor, 7, 15);
        int minLead = clamp(nearFinishFallbackMinPointLead, 2, 6);
        if (top < pointFloor || margin < minLead) {
            return Optional.empty();
        }
        return Optional.of(p1 > p2 ? player1Id : player2Id);
    }

    private Optional<Long> determineWinnerFromNearFinishFallback(String rawScore,
                                                                 Long player1Id,
                                                                 Long player2Id) {
        if (!nearFinishFallbackEnabled || !StringUtils.hasText(rawScore) || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        List<ScorePair> pairs = parseScorePairs(rawScore);
        if (pairs.size() < 2) {
            return Optional.empty();
        }

        int targetSets = clamp(scoreSettlementTargetSets, 3, 7);
        int maxTotalSets = Math.max(1, (targetSets * 2) - 1);

        ScorePair setScore = null;
        for (ScorePair pair : pairs) {
            int top = Math.max(pair.left(), pair.right());
            int total = pair.left() + pair.right();
            if (top <= targetSets && total <= maxTotalSets) {
                setScore = pair;
                break;
            }
        }
        if (setScore == null) {
            return Optional.empty();
        }
        int setTop = Math.max(setScore.left(), setScore.right());
        int setLow = Math.min(setScore.left(), setScore.right());
        if (setTop != (targetSets - 1) || setLow != (targetSets - 1)) {
            return Optional.empty();
        }

        ScorePair last = pairs.get(pairs.size() - 1);
        int pointTop = Math.max(last.left(), last.right());
        int pointMargin = Math.abs(last.left() - last.right());
        int pointFloor = clamp(nearFinishFallbackPointFloor, 7, 15);
        int minLead = clamp(nearFinishFallbackMinPointLead, 2, 6);
        if (pointTop < pointFloor || pointMargin < minLead) {
            return Optional.empty();
        }
        return Optional.of(last.left() > last.right() ? player1Id : player2Id);
    }

    private List<ScorePair> parseScorePairs(String rawScore) {
        List<ScorePair> pairs = new ArrayList<>();
        if (!StringUtils.hasText(rawScore)) {
            return pairs;
        }
        Matcher matcher = SCORE_PAIR_PATTERN.matcher(rawScore);
        while (matcher.find()) {
            try {
                int left = Integer.parseInt(matcher.group(1));
                int right = Integer.parseInt(matcher.group(2));
                if (left >= 0 && right >= 0) {
                    pairs.add(new ScorePair(left, right));
                }
            } catch (Exception ignore) {
                // continue scanning
            }
        }
        return pairs;
    }

    private boolean canSettleFromLastObservation(PaperTradeBet bet, LocalDateTime now) {
        if (bet == null || bet.getLastObservedAt() == null || now == null) {
            return false;
        }
        int configuredDelay = clamp(settlementDelayMinutes, 0, 720);
        int effectiveDelay = configuredDelay;
        if (isFinishedPhase(bet.getLastObservedPhase())) {
            effectiveDelay = Math.min(configuredDelay, 2);
        }
        LocalDateTime gate = bet.getLastObservedAt().plusMinutes(effectiveDelay);
        return !now.isBefore(gate);
    }

    private boolean shouldHoldOpenWithTrackedObservation(TrackedMatchObservation observation, LocalDateTime now) {
        if (observation == null || now == null || observation.getObservedAt() == null) {
            return false;
        }
        if (!observation.isTrackedAfterClose()) {
            return false;
        }
        boolean hasLiveContext = observation.isLive()
                || isLateLikePhase(observation.getMatchPhase())
                || isFinishedPhase(observation.getMatchPhase());
        boolean hasScoreContext = StringUtils.hasText(observation.getLiveScore())
                || StringUtils.hasText(observation.getMatchPhase());
        if (!(hasLiveContext || hasScoreContext)) {
            return false;
        }
        int graceMinutes = clamp(trackedAfterCloseGraceMinutes, 5, 240);
        return !now.isAfter(observation.getObservedAt().plusMinutes(graceMinutes));
    }

    private boolean shouldVoidMissingBoardBet(PaperTradeBet bet, LocalDate targetDate, LocalDateTime now) {
        if (bet == null || now == null) {
            return false;
        }
        if (!isSettlementWindowOpen(bet, targetDate)) {
            return false;
        }
        int minMissingSyncs = clamp(unmatchedMissingSyncs, 1, 60);
        if (bet.getMissingBoardCount() < minMissingSyncs) {
            return false;
        }
        boolean hasScoreContext = StringUtils.hasText(bet.getLastObservedScore());
        if (hasScoreContext) {
            int scoredMinMissing = Math.max(minMissingSyncs + 2, minMissingSyncs * 2);
            if (bet.getMissingBoardCount() < scoredMinMissing) {
                return false;
            }
        }
        int timeoutMinutes = clamp(unmatchedRefundMinutes, 15, 1440);
        if (hasScoreContext) {
            int scoreBackfillWindow = clamp(lastScoreBackfillMinutes, 15, 720);
            int scoreGrace = (isLateLikePhase(bet.getLastObservedPhase()) || isFinishedPhase(bet.getLastObservedPhase()))
                    ? 240
                    : 120;
            timeoutMinutes = Math.max(timeoutMinutes, scoreBackfillWindow + scoreGrace);
        }
        Optional<LocalDateTime> startOpt = parseStartDateTime(bet.getStartTimeIso());
        LocalDateTime fallbackStart = bet.getPlacedAt() == null ? now : bet.getPlacedAt();
        LocalDateTime base = startOpt.orElse(fallbackStart);
        LocalDateTime timeoutAt = base.plusMinutes(timeoutMinutes);
        return !now.isBefore(timeoutAt);
    }

    private boolean isOverdueForLastScoreBackfill(PaperTradeBet bet, LocalDateTime now) {
        if (bet == null || now == null || !StringUtils.hasText(bet.getLastObservedScore())) {
            return false;
        }
        int overdueMinutes = clamp(lastScoreBackfillMinutes, 15, 720);
        Optional<LocalDateTime> startOpt = parseStartDateTime(bet.getStartTimeIso());
        LocalDateTime anchor = startOpt.orElse(bet.getPlacedAt());
        if (anchor == null) {
            return false;
        }
        return !now.isBefore(anchor.plusMinutes(overdueMinutes));
    }

    private boolean shouldAllowNearFinishFallback(PaperTradeBet bet, LocalDateTime now) {
        if (!nearFinishFallbackEnabled || bet == null || now == null) {
            return false;
        }
        int minMissing = clamp(nearFinishFallbackMissingSyncs, 2, 20);
        if (bet.getMissingBoardCount() < minMissing) {
            return false;
        }
        int waitMinutes = clamp(nearFinishFallbackMinutes, 5, 720);
        LocalDateTime anchor = bet.getLastObservedAt();
        if (anchor == null) {
            anchor = parseStartDateTime(bet.getStartTimeIso()).orElse(bet.getPlacedAt());
        }
        if (anchor == null) {
            return false;
        }
        return !now.isBefore(anchor.plusMinutes(waitMinutes));
    }

    private boolean shouldAllowStaleOnBoardFallback(PaperTradeBet bet,
                                                    LiveOddsRecommendationDto row,
                                                    String normalizedScore,
                                                    LocalDateTime now) {
        if (bet == null || row == null || now == null) {
            return false;
        }
        boolean rowSignalsLiveContext = row.live()
                || isLateLikePhase(row.matchPhase())
                || isFinishedPhase(row.matchPhase())
                || StringUtils.hasText(normalizedScore);
        boolean priorSignalsLiveContext = StringUtils.hasText(bet.getLastObservedScore())
                && (isLateLikePhase(bet.getLastObservedPhase()) || isFinishedPhase(bet.getLastObservedPhase()));
        if (!(rowSignalsLiveContext || priorSignalsLiveContext)) {
            return false;
        }
        int minObservations = clamp(staleOnBoardMinObservations, 2, 30);
        if (bet.getMissingBoardCount() < minObservations) {
            return false;
        }
        LocalDateTime anchor = bet.getLastObservedAt();
        if (anchor == null) {
            return false;
        }
        int waitMinutes = clamp(staleOnBoardSettleMinutes, 2, 180);
        return !now.isBefore(anchor.plusMinutes(waitMinutes));
    }

    private boolean isPhaseDegradation(String beforeRaw, String afterRaw) {
        return phaseSignalRank(afterRaw) < phaseSignalRank(beforeRaw);
    }

    private int phaseSignalRank(String phaseRaw) {
        if (!StringUtils.hasText(phaseRaw)) {
            return 0;
        }
        String phase = phaseRaw.trim().toUpperCase(Locale.ROOT);
        if (phase.contains("FINISH")
                || phase.contains("FINAL")
                || phase.contains("SETTLED")
                || phase.contains("RESULT")
                || phase.contains("COMPLETE")
                || phase.contains("END")) {
            return 5;
        }
        if (phase.contains("LIVE_LATE")) {
            return 4;
        }
        if (phase.contains("LIVE_MID")) {
            return 3;
        }
        if (phase.contains("LIVE_EARLY") || "LIVE".equals(phase) || phase.contains("INPLAY") || phase.contains("IN_PLAY")) {
            return 2;
        }
        if (phase.contains("UPCOMING")) {
            return 1;
        }
        return 1;
    }

    private boolean shouldTrackStaleObservation(LiveOddsRecommendationDto row) {
        if (row == null) {
            return false;
        }
        if (hasExplicitCompletionSignal(row)) {
            return true;
        }
        if (StringUtils.hasText(row.liveScore())) {
            return true;
        }
        return row.live() || isLateLikePhase(row.matchPhase());
    }

    private Optional<Long> determineWinnerFromTargetedCompletionSignal(PaperTradeBet bet,
                                                                       LiveOddsRecommendationDto row,
                                                                       String currentScore,
                                                                       String scoreBeforeUpdate) {
        if (bet == null || row == null || !isTargetedCompletedScoreRow(row)) {
            return Optional.empty();
        }
        Optional<Long> fromCurrent = determineWinnerFromScore(
                currentScore,
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                "FINISHED",
                true
        );
        if (fromCurrent.isPresent()) {
            return fromCurrent;
        }
        String fallbackScore = StringUtils.hasText(scoreBeforeUpdate)
                ? scoreBeforeUpdate
                : bet.getLastObservedScore();
        return determineWinnerFromScore(
                fallbackScore,
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                "FINISHED",
                true
        );
    }

    private boolean isTargetedCompletedScoreRow(LiveOddsRecommendationDto row) {
        return row != null
                && OBSERVATION_SOURCE_SCORE_FEED.equals(inferObservationSourceKind(row))
                && hasExplicitCompletionSignal(row);
    }

    private boolean hasExplicitCompletionSignal(LiveOddsRecommendationDto row) {
        return row != null && (row.matchCompleted() || row.resulted());
    }

    private boolean textChanged(String before, String after) {
        String left = StringUtils.hasText(before) ? before.trim() : "";
        String right = StringUtils.hasText(after) ? after.trim() : "";
        return !left.equals(right);
    }

    private String normalizeScoreForBet(PaperTradeBet bet,
                                        String rawScore,
                                        Long rowPlayer1Id,
                                        String rowPlayer1Name,
                                        Long rowPlayer2Id,
                                        String rowPlayer2Name) {
        if (bet == null || !StringUtils.hasText(rawScore)) {
            return rawScore;
        }
        ScoreOrientation orientation = resolveScoreOrientation(
                bet.getPlayer1Id(),
                bet.getPlayer1Name(),
                bet.getPlayer2Id(),
                bet.getPlayer2Name(),
                rowPlayer1Id,
                rowPlayer1Name,
                rowPlayer2Id,
                rowPlayer2Name
        );
        if (orientation == ScoreOrientation.REVERSED) {
            return reverseScorePairs(rawScore);
        }
        return rawScore.trim();
    }

    private ScoreOrientation resolveScoreOrientation(Long betPlayer1Id,
                                                     String betPlayer1Name,
                                                     Long betPlayer2Id,
                                                     String betPlayer2Name,
                                                     Long rowPlayer1Id,
                                                     String rowPlayer1Name,
                                                     Long rowPlayer2Id,
                                                     String rowPlayer2Name) {
        String betLeft = playerToken(betPlayer1Id, betPlayer1Name);
        String betRight = playerToken(betPlayer2Id, betPlayer2Name);
        String rowLeft = playerToken(rowPlayer1Id, rowPlayer1Name);
        String rowRight = playerToken(rowPlayer2Id, rowPlayer2Name);
        if (!StringUtils.hasText(betLeft)
                || !StringUtils.hasText(betRight)
                || !StringUtils.hasText(rowLeft)
                || !StringUtils.hasText(rowRight)) {
            return ScoreOrientation.UNKNOWN;
        }
        if (betLeft.equals(rowLeft) && betRight.equals(rowRight)) {
            return ScoreOrientation.DIRECT;
        }
        if (betLeft.equals(rowRight) && betRight.equals(rowLeft)) {
            return ScoreOrientation.REVERSED;
        }
        return ScoreOrientation.UNKNOWN;
    }

    private String reverseScorePairs(String rawScore) {
        if (!StringUtils.hasText(rawScore)) {
            return rawScore;
        }
        Matcher matcher = SCORE_PAIR_PATTERN.matcher(rawScore);
        StringBuffer swapped = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String replacement = matcher.group(2) + "-" + matcher.group(1);
            matcher.appendReplacement(swapped, Matcher.quoteReplacement(replacement));
        }
        if (!found) {
            return rawScore.trim();
        }
        matcher.appendTail(swapped);
        return swapped.toString().trim();
    }

    private boolean isLateLikePhase(String phaseRaw) {
        if (!StringUtils.hasText(phaseRaw)) {
            return false;
        }
        String phase = phaseRaw.trim().toUpperCase(Locale.ROOT);
        return phase.contains("LIVE_LATE")
                || phase.contains("LIVE_MID")
                || phase.contains("FINISH")
                || phase.contains("FINAL")
                || phase.contains("SETTLED")
                || phase.contains("COMPLETE")
                || phase.contains("RESULT")
                || phase.contains("END");
    }

    private PaperTradingSessionDto buildSessionDto(PaperTradeSession session, int openLimit, int recentLimit) {
        int openTake = clamp(openLimit, 5, 100);
        int recentTake = clamp(recentLimit, 10, 200);

        List<PaperTradeBet> allOpenRows = betRepository.findBySessionIdAndStatusOrderByPlacedAtDesc(
                session.getId(),
                PaperTradeBet.STATUS_OPEN
        );
        List<PaperTradeBet> openRows = allOpenRows;
        if (openRows.size() > openTake) {
            openRows = openRows.subList(0, openTake);
        }

        List<PaperTradeBet> recentRows = betRepository.findBySessionIdOrderByPlacedAtDesc(
                session.getId(),
                PageRequest.of(0, recentTake)
        );

        List<PaperTradeBet> settledRows = betRepository.findBySessionIdAndStatusInOrderBySettledAtAsc(
                session.getId(),
                List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST, PaperTradeBet.STATUS_PUSHED, PaperTradeBet.STATUS_VOIDED)
        );
        PaperTradingSessionDto.DecisionTelemetryDto decisionTelemetry = buildDecisionTelemetry(session.getId());

        List<PaperTradeBetDto> openDtos = openRows.stream().map(this::toDto).toList();
        List<PaperTradeBetDto> recentDtos = recentRows.stream().map(this::toDto).toList();
        List<PaperTradingSessionDto.TriggerInsightDto> triggerInsights = buildTopTriggers(settledRows);
        PaperTradingSessionDto.ExposureMetricsDto exposureMetrics = buildExposureMetrics(session, allOpenRows);

        long openCount = betRepository.countBySessionIdAndStatus(session.getId(), PaperTradeBet.STATUS_OPEN);
        long voidedCount = betRepository.countBySessionIdAndStatus(session.getId(), PaperTradeBet.STATUS_VOIDED);
        double roiPct = session.getTotalStaked() <= EPS
                ? 0.0
                : (session.getRealizedPnl() / session.getTotalStaked()) * 100.0;
        int settledDecisions = session.getWins() + session.getLosses();
        double settledWinRate = settledDecisions == 0
                ? 0.0
                : session.getWins() / (double) settledDecisions;

        return new PaperTradingSessionDto(
                session.getId(),
                session.getLabel(),
                session.getStatus(),
                round2(session.getStartingBankroll()),
                round2(session.getCurrentBankroll()),
                round2(session.getPeakBankroll()),
                round2(session.getRealizedPnl()),
                round2(roiPct),
                round2(session.getTotalStaked()),
                round2(session.getTotalReturned()),
                session.getTotalBets(),
                (int) openCount,
                session.getWins(),
                session.getLosses(),
                session.getPushes(),
                (int) voidedCount,
                session.getSimulationRowsScanned(),
                session.getSimulationBetsPlaced(),
                session.getSimulationBetsSettled(),
                session.getSimulationBetsVoided(),
                settledWinRate,
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getLastSyncAt(),
                new PaperTradingSessionDto.AdaptiveMetricsDto(
                        session.getAdaptiveSampleSize(),
                        round4(session.getAdaptiveEdgeShift() * 100.0),
                        round4(session.getAdaptiveSelectionScoreShift()),
                        round4(session.getAdaptiveStakeMultiplier()),
                        round4(session.getAdaptiveCalibrationError() * 100.0),
                        round4(session.getAdaptiveRoiSignal() * 100.0),
                        session.getAdaptiveUpdatedAt()
                ),
                decisionTelemetry,
                exposureMetrics,
                openDtos,
                recentDtos,
                triggerInsights,
                buildEquityCurve(session, settledRows)
        );
    }

    private PaperTradingSessionDto.DecisionTelemetryDto buildDecisionTelemetry(Long sessionId) {
        if (sessionId == null) {
            return new PaperTradingSessionDto.DecisionTelemetryDto(0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, List.of());
        }
        List<PaperTradeDecisionSample> rows = decisionSampleRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (rows == null || rows.isEmpty()) {
            return new PaperTradingSessionDto.DecisionTelemetryDto(0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, List.of());
        }

        long consideredCount = rows.size();
        long placedCount = rows.stream()
                .filter(sample -> "PLACED".equalsIgnoreCase(sample.getDecisionStatus()))
                .count();
        long skippedCount = rows.stream()
                .filter(sample -> "SKIPPED".equalsIgnoreCase(sample.getDecisionStatus()))
                .count();
        long fallbackPlacedCount = rows.stream()
                .filter(PaperTradeDecisionSample::isFallbackPick)
                .filter(sample -> "PLACED".equalsIgnoreCase(sample.getDecisionStatus()))
                .count();
        double placementRatePct = consideredCount == 0 ? 0.0 : round4((placedCount * 100.0) / consideredCount);
        double avgSelectionScore = averageNonNull(rows, PaperTradeDecisionSample::getSelectionScore);
        double avgSignalQualityPct = averageNonNull(rows, PaperTradeDecisionSample::getSignalQuality) * 100.0;
        double avgPlacedEdgePct = averageNonNull(
                rows.stream().filter(sample -> "PLACED".equalsIgnoreCase(sample.getDecisionStatus())).toList(),
                PaperTradeDecisionSample::getSuggestedEdge
        ) * 100.0;
        double avgSkippedEdgePct = averageNonNull(
                rows.stream().filter(sample -> "SKIPPED".equalsIgnoreCase(sample.getDecisionStatus())).toList(),
                PaperTradeDecisionSample::getSuggestedEdge
        ) * 100.0;

        Map<String, Integer> skipReasons = new HashMap<>();
        for (PaperTradeDecisionSample row : rows) {
            if (row == null || !"SKIPPED".equalsIgnoreCase(row.getDecisionStatus())) {
                continue;
            }
            String reason = safeText(row.getDecisionReason(), "UNKNOWN");
            skipReasons.merge(reason, 1, Integer::sum);
        }
        List<PaperTradingSessionDto.DecisionReasonDto> topSkipReasons = skipReasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(entry -> new PaperTradingSessionDto.DecisionReasonDto(entry.getKey(), entry.getValue()))
                .toList();

        return new PaperTradingSessionDto.DecisionTelemetryDto(
                consideredCount,
                placedCount,
                skippedCount,
                fallbackPlacedCount,
                placementRatePct,
                round4(avgSelectionScore),
                round4(avgSignalQualityPct),
                round4(avgPlacedEdgePct),
                round4(avgSkippedEdgePct),
                topSkipReasons
        );
    }

    private PaperTradingSessionDto.ExposureMetricsDto buildExposureMetrics(PaperTradeSession session,
                                                                           List<PaperTradeBet> openRows) {
        List<PaperTradeBet> open = openRows == null ? List.of() : openRows;
        ExposureProfile exposureProfile = ExposureProfile.fromOpenBets(open);
        double capitalBase = Math.max(
                valueOrZero(session == null ? null : session.getCurrentBankroll()),
                round2(valueOrZero(session == null ? null : session.getCurrentBankroll()) + exposureProfile.openStake())
        );
        capitalBase = Math.max(100.0, capitalBase);

        double openExposureCap = round2(capitalBase * clamp(maxOpenExposurePct, 0.10, 0.95));
        double openExposure = round2(exposureProfile.openStake());
        double openExposureUsagePct = openExposureCap <= EPS ? 0.0 : clamp(openExposure / openExposureCap, 0.0, 2.0);
        double openExposureRemaining = round2(Math.max(0.0, openExposureCap - openExposure));
        int maxOpenBets = clamp(maxConcurrentOpenBets, 1, 60);
        double concurrentUsagePct = maxOpenBets <= 0 ? 0.0 : clamp(exposureProfile.openBets() / (double) maxOpenBets, 0.0, 2.0);

        double playerCap = round2(capitalBase * clamp(maxExposurePerPlayerPct, 0.03, 0.60));
        double triggerCap = round2(capitalBase * clamp(maxExposurePerTriggerPct, 0.05, 0.75));

        Map<Long, Double> playerStake = new HashMap<>();
        Map<Long, String> playerNames = new HashMap<>();
        Map<String, Double> triggerStake = new HashMap<>();

        for (PaperTradeBet bet : open) {
            if (bet == null || !PaperTradeBet.STATUS_OPEN.equalsIgnoreCase(bet.getStatus())) {
                continue;
            }
            double stake = Math.max(0.0, bet.getStake());
            if (bet.getSidePlayerId() != null) {
                playerStake.merge(bet.getSidePlayerId(), stake, Double::sum);
                if (StringUtils.hasText(bet.getSideName())) {
                    playerNames.putIfAbsent(bet.getSidePlayerId(), bet.getSideName().trim());
                }
            }
            String trigger = normalizeTriggerStatic(bet.getTopTrigger());
            if (StringUtils.hasText(trigger)) {
                triggerStake.merge(trigger, stake, Double::sum);
            }
        }

        long mostExposedPlayerId = playerStake.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1L);
        double mostExposedPlayerStake = round2(mostExposedPlayerId < 0 ? 0.0 : playerStake.getOrDefault(mostExposedPlayerId, 0.0));
        String mostExposedPlayerName = mostExposedPlayerId < 0
                ? null
                : playerNames.getOrDefault(mostExposedPlayerId, "Player " + mostExposedPlayerId);
        double mostExposedPlayerUsagePct = playerCap <= EPS ? 0.0 : clamp(mostExposedPlayerStake / playerCap, 0.0, 2.0);
        int playerNearCapCount = (int) playerStake.values().stream()
                .mapToDouble(Double::doubleValue)
                .filter(stake -> playerCap > EPS && (stake / playerCap) >= 0.80)
                .count();

        String mostExposedTrigger = triggerStake.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        double mostExposedTriggerStake = round2(StringUtils.hasText(mostExposedTrigger)
                ? triggerStake.getOrDefault(mostExposedTrigger, 0.0)
                : 0.0);
        double mostExposedTriggerUsagePct = triggerCap <= EPS ? 0.0 : clamp(mostExposedTriggerStake / triggerCap, 0.0, 2.0);
        int triggerNearCapCount = (int) triggerStake.values().stream()
                .mapToDouble(Double::doubleValue)
                .filter(stake -> triggerCap > EPS && (stake / triggerCap) >= 0.80)
                .count();

        return new PaperTradingSessionDto.ExposureMetricsDto(
                openExposure,
                openExposureCap,
                round4(openExposureUsagePct),
                openExposureRemaining,
                maxOpenBets,
                round4(concurrentUsagePct),
                mostExposedPlayerName,
                mostExposedPlayerStake,
                playerCap,
                round4(mostExposedPlayerUsagePct),
                playerNearCapCount,
                mostExposedTrigger,
                mostExposedTriggerStake,
                triggerCap,
                round4(mostExposedTriggerUsagePct),
                triggerNearCapCount
        );
    }

    private AdaptiveProfile buildAdaptiveProfile(PaperTradeSession session) {
        if (!adaptiveEnabled || session == null || session.getId() == null) {
            return AdaptiveProfile.neutral();
        }

        int historyTake = clamp(adaptiveHistoryWindow, 20, 500);
        List<AdaptiveDecisionSample> recentDecisions = loadAdaptiveDecisionSamples(historyTake);
        int decisions = recentDecisions.size();
        int minDecisions = clamp(adaptiveMinSettledDecisions, 4, 80);
        if (decisions < minDecisions) {
            return new AdaptiveProfile(
                    decisions,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    clamp(minEdgeForBet, 0.0, 0.20),
                    Map.of()
            );
        }

        int triggerMinDecisions = clamp(adaptiveTriggerMinDecisions, 3, 60);
        double weightedWins = 0.0;
        double modelProbSum = 0.0;
        double edgeSum = 0.0;
        double stakeSum = 0.0;
        double pnlSum = 0.0;
        double weightSum = 0.0;
        double halfLifeDays = Math.max(2.0, adaptiveLearningHalfLifeDays);
        LocalDateTime now = LocalDateTime.now();
        Map<String, TriggerAggregate> triggerAggregates = new HashMap<>();
        for (AdaptiveDecisionSample sample : recentDecisions) {
            double w = adaptiveRecencyWeight(sample.settledAt(), now, halfLifeDays);
            if (w <= 0.0) {
                continue;
            }
            weightSum += w;
            if (PaperTradeBet.STATUS_WON.equals(sample.status())) {
                weightedWins += w;
            }
            modelProbSum += clamp(sample.modelProbability(), 0.01, 0.99) * w;
            edgeSum += clamp(sample.edge(), -0.25, 0.35) * w;
            stakeSum += Math.max(0.0, sample.stake()) * w;
            pnlSum += sample.profitLoss() * w;

            String triggerKey = normalizeTrigger(sample.topTrigger());
            TriggerAggregate aggregate = triggerAggregates.getOrDefault(triggerKey, TriggerAggregate.empty());
            triggerAggregates.put(triggerKey, aggregate.add(sample, w));
        }

        if (weightSum <= EPS) {
            return AdaptiveProfile.neutral();
        }

        double observedWinRate = weightedWins / weightSum;
        double avgModelProbability = modelProbSum / weightSum;
        double calibrationError = avgModelProbability - observedWinRate;
        double roiSignal = stakeSum <= EPS ? 0.0 : pnlSum / stakeSum;
        double avgSettledEdge = edgeSum / weightSum;

        // Use effective weighted sample support instead of history window position so
        // adaptation responds to statistical significance, not just queue length.
        double reliabilitySupportTarget = Math.max(4.0, minDecisions * 2.5);
        double reliability = clamp(weightSum / (weightSum + reliabilitySupportTarget), 0.0, 1.0);
        double maxEdgeShift = clamp(adaptiveMaxEdgeShift, 0.002, 0.05);
        double maxScoreShift = clamp(adaptiveMaxSelectionScoreShift, 0.2, 3.0);
        double maxStakeDelta = clamp(adaptiveMaxStakeMultiplierDelta, 0.02, 0.4);

        double edgeShiftRaw = (calibrationError * 0.06) + ((-roiSignal) * 0.04);
        double edgeShift = clamp(edgeShiftRaw * reliability, -(maxEdgeShift * 0.5), maxEdgeShift);
        double modelGapShift = clamp(edgeShift * 0.9, -(maxEdgeShift * 0.5), maxEdgeShift);
        double selectionScoreShift = clamp(edgeShift * 35.0, -(maxScoreShift * 0.5), maxScoreShift);
        double probabilityShiftRaw = ((-calibrationError) * 0.35) + (roiSignal * 0.08);
        double modelProbabilityShift = clamp(probabilityShiftRaw * reliability, -0.035, 0.035);

        double normalizedShift = edgeShift / maxEdgeShift;
        double stakeMultiplier = 1.0 - (normalizedShift * (maxStakeDelta * 0.65));
        stakeMultiplier = clamp(stakeMultiplier, 1.0 - maxStakeDelta, 1.0 + (maxStakeDelta * 0.4));

        double confidenceWidthTightening = clamp(Math.max(0.0, edgeShift) * 1.8, 0.0, 0.10);
        double selectionPenalty = clamp(Math.max(0.0, edgeShift) * 24.0, 0.0, 1.2);

        Map<String, TriggerAdaptiveSignal> triggerSignals = new HashMap<>();
        for (Map.Entry<String, TriggerAggregate> entry : triggerAggregates.entrySet()) {
            TriggerAggregate aggregate = entry.getValue();
            if (aggregate.decisions() < triggerMinDecisions || aggregate.weightSum() <= EPS || aggregate.stakeSum() <= EPS) {
                continue;
            }
            double triggerSupportTarget = Math.max(3.0, triggerMinDecisions * 3.0);
            double triggerReliability = clamp(
                    aggregate.weightSum() / (aggregate.weightSum() + triggerSupportTarget),
                    0.0,
                    1.0
            ) * reliability;
            double triggerWinRate = aggregate.winsWeight() / aggregate.weightSum();
            double triggerModelProb = aggregate.modelProbabilitySum() / aggregate.weightSum();
            double triggerCalibrationError = triggerModelProb - triggerWinRate;
            double triggerRoi = aggregate.pnlSum() / aggregate.stakeSum();
            double triggerProbabilityShift = clamp(
                    ((-triggerCalibrationError) * 0.45) + (triggerRoi * 0.12),
                    -0.025,
                    0.025
            ) * triggerReliability;
            double triggerModelGapShift = clamp(
                    (triggerCalibrationError * 0.28) + ((-triggerRoi) * 0.10),
                    -0.010,
                    0.015
            ) * triggerReliability;
            double triggerSelectionPenalty = clamp(
                    (triggerCalibrationError * 8.0) + ((-triggerRoi) * 4.0),
                    -0.6,
                    1.2
            ) * triggerReliability;
            double triggerEdgeShift = clamp(
                    (triggerCalibrationError * 0.22) + ((-triggerRoi) * 0.10),
                    -0.008,
                    0.012
            ) * triggerReliability;

            triggerSignals.put(entry.getKey(), new TriggerAdaptiveSignal(
                    aggregate.decisions(),
                    round4(triggerProbabilityShift),
                    round4(triggerModelGapShift),
                    round4(triggerSelectionPenalty),
                    round4(triggerEdgeShift)
            ));
        }

        return new AdaptiveProfile(
                decisions,
                round4(reliability),
                round4(edgeShift),
                round4(modelGapShift),
                round4(selectionScoreShift),
                round4(modelProbabilityShift),
                round4(stakeMultiplier),
                round4(confidenceWidthTightening),
                round4(selectionPenalty),
                round4(calibrationError),
                round4(roiSignal),
                round4(avgSettledEdge),
                triggerSignals
        );
    }

    private List<PaperTradingSessionDto.TriggerInsightDto> buildTopTriggers(List<PaperTradeBet> settledRows) {
        record TriggerAggregate(int count,
                                int wins,
                                int losses,
                                double pnl,
                                double edgeSum,
                                double modelProbSum,
                                double impliedProbSum,
                                double confidenceWidthSum,
                                int confidenceCount,
                                double stakeSum) {
            TriggerAggregate add(PaperTradeBet bet) {
                int addWins = PaperTradeBet.STATUS_WON.equals(bet.getStatus()) ? 1 : 0;
                int addLosses = PaperTradeBet.STATUS_LOST.equals(bet.getStatus()) ? 1 : 0;
                double addPnl = bet.getProfitLoss() == null ? 0.0 : bet.getProfitLoss();
                double width = 0.0;
                int widthCount = 0;
                if (bet.getConfidenceLow() != null && bet.getConfidenceHigh() != null) {
                    width = Math.max(0.0, bet.getConfidenceHigh() - bet.getConfidenceLow());
                    widthCount = 1;
                }
                return new TriggerAggregate(
                        count + 1,
                        wins + addWins,
                        losses + addLosses,
                        pnl + addPnl,
                        edgeSum + bet.getEdge(),
                        modelProbSum + bet.getModelProbability(),
                        impliedProbSum + bet.getImpliedProbability(),
                        confidenceWidthSum + width,
                        confidenceCount + widthCount,
                        stakeSum + bet.getStake()
                );
            }
        }

        Map<String, TriggerAggregate> aggregateByTrigger = new LinkedHashMap<>();
        for (PaperTradeBet bet : settledRows) {
            String trigger = StringUtils.hasText(bet.getTopTrigger()) ? bet.getTopTrigger().trim() : "Unknown Trigger";
            TriggerAggregate aggregate = aggregateByTrigger.get(trigger);
            if (aggregate == null) {
                aggregate = new TriggerAggregate(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0);
            }
            aggregateByTrigger.put(trigger, aggregate.add(bet));
        }

        List<PaperTradingSessionDto.TriggerInsightDto> out = new ArrayList<>();
        for (Map.Entry<String, TriggerAggregate> entry : aggregateByTrigger.entrySet()) {
            TriggerAggregate agg = entry.getValue();
            int decisions = agg.wins + agg.losses;
            double winRate = decisions == 0 ? 0.0 : agg.wins / (double) decisions;
            double avgEdgePct = agg.count == 0 ? 0.0 : (agg.edgeSum / agg.count) * 100.0;
            double avgModelProb = agg.count == 0 ? 0.0 : (agg.modelProbSum / agg.count);
            double avgImpliedProb = agg.count == 0 ? 0.0 : (agg.impliedProbSum / agg.count);
            double avgConfidenceWidthPct = agg.confidenceCount == 0 ? 0.0 : (agg.confidenceWidthSum / agg.confidenceCount) * 100.0;
            double calibrationDeltaPct = decisions == 0
                    ? 0.0
                    : ((agg.wins / (double) decisions) - avgModelProb) * 100.0;
            double roiPct = agg.stakeSum <= EPS ? 0.0 : (agg.pnl / agg.stakeSum) * 100.0;
            out.add(new PaperTradingSessionDto.TriggerInsightDto(
                    entry.getKey(),
                    agg.count,
                    agg.wins,
                    agg.losses,
                    winRate,
                    round2(agg.pnl),
                    round4(avgEdgePct),
                    round4(avgModelProb),
                    round4(avgImpliedProb),
                    round4(avgConfidenceWidthPct),
                    round4(calibrationDeltaPct),
                    round4(roiPct)
            ));
        }
        out.sort(Comparator
                .comparingInt(PaperTradingSessionDto.TriggerInsightDto::count).reversed()
                .thenComparing((a, b) -> Double.compare(Math.abs(b.pnl()), Math.abs(a.pnl()))));
        if (out.size() > 8) {
            return out.subList(0, 8);
        }
        return out;
    }

    private List<PaperTradingSessionDto.TriggerInsightDto> buildTopTriggersFromLearning(List<PaperTradeLearningSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        record TriggerAggregate(int count,
                                int wins,
                                int losses,
                                double pnl,
                                double edgeSum,
                                double modelProbSum,
                                double impliedProbSum,
                                double confidenceWidthSum,
                                int confidenceCount,
                                double stakeSum) {
            TriggerAggregate add(PaperTradeLearningSample sample) {
                int addWins = PaperTradeBet.STATUS_WON.equals(sample.getStatus()) ? 1 : 0;
                int addLosses = PaperTradeBet.STATUS_LOST.equals(sample.getStatus()) ? 1 : 0;
                return new TriggerAggregate(
                        count + 1,
                        wins + addWins,
                        losses + addLosses,
                        pnl + sample.getProfitLoss(),
                        edgeSum + sample.getEdge(),
                        modelProbSum + sample.getModelProbability(),
                        impliedProbSum + sample.getImpliedProbability(),
                        confidenceWidthSum + Math.max(0.0, sample.getConfidenceWidth()),
                        confidenceCount + 1,
                        stakeSum + sample.getStake()
                );
            }
        }

        Map<String, TriggerAggregate> aggregateByTrigger = new LinkedHashMap<>();
        for (PaperTradeLearningSample sample : samples) {
            String trigger = StringUtils.hasText(sample.getTopTrigger()) ? sample.getTopTrigger().trim() : "Unknown Trigger";
            TriggerAggregate aggregate = aggregateByTrigger.get(trigger);
            if (aggregate == null) {
                aggregate = new TriggerAggregate(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0);
            }
            aggregateByTrigger.put(trigger, aggregate.add(sample));
        }

        List<PaperTradingSessionDto.TriggerInsightDto> out = new ArrayList<>();
        for (Map.Entry<String, TriggerAggregate> entry : aggregateByTrigger.entrySet()) {
            TriggerAggregate agg = entry.getValue();
            int decisions = agg.wins + agg.losses;
            double winRate = decisions == 0 ? 0.0 : agg.wins / (double) decisions;
            double avgEdgePct = agg.count == 0 ? 0.0 : (agg.edgeSum / agg.count) * 100.0;
            double avgModelProb = agg.count == 0 ? 0.0 : (agg.modelProbSum / agg.count);
            double avgImpliedProb = agg.count == 0 ? 0.0 : (agg.impliedProbSum / agg.count);
            double avgConfidenceWidthPct = agg.confidenceCount == 0 ? 0.0 : (agg.confidenceWidthSum / agg.confidenceCount) * 100.0;
            double calibrationDeltaPct = decisions == 0
                    ? 0.0
                    : ((agg.wins / (double) decisions) - avgModelProb) * 100.0;
            double roiPct = agg.stakeSum <= EPS ? 0.0 : (agg.pnl / agg.stakeSum) * 100.0;
            out.add(new PaperTradingSessionDto.TriggerInsightDto(
                    entry.getKey(),
                    agg.count,
                    agg.wins,
                    agg.losses,
                    winRate,
                    round2(agg.pnl),
                    round4(avgEdgePct),
                    round4(avgModelProb),
                    round4(avgImpliedProb),
                    round4(avgConfidenceWidthPct),
                    round4(calibrationDeltaPct),
                    round4(roiPct)
            ));
        }
        out.sort(Comparator
                .comparingInt(PaperTradingSessionDto.TriggerInsightDto::count).reversed()
                .thenComparing((a, b) -> Double.compare(Math.abs(b.pnl()), Math.abs(a.pnl()))));
        if (out.size() > 8) {
            return out.subList(0, 8);
        }
        return out;
    }

    private List<PaperTradingSessionDto.EquityPointDto> buildEquityCurve(PaperTradeSession session,
                                                                          List<PaperTradeBet> settledRows) {
        List<PaperTradingSessionDto.EquityPointDto> curve = new ArrayList<>();
        LocalDateTime startAt = session.getCreatedAt() == null ? LocalDateTime.now() : session.getCreatedAt();
        double cumulative = 0.0;
        curve.add(new PaperTradingSessionDto.EquityPointDto(startAt, session.getStartingBankroll(), cumulative));
        for (PaperTradeBet bet : settledRows) {
            if (bet.getProfitLoss() == null) {
                continue;
            }
            cumulative = round2(cumulative + bet.getProfitLoss());
            LocalDateTime at = bet.getSettledAt() == null ? bet.getPlacedAt() : bet.getSettledAt();
            curve.add(new PaperTradingSessionDto.EquityPointDto(
                    at,
                    round2(session.getStartingBankroll() + cumulative),
                    cumulative
            ));
        }
        if (curve.size() > 250) {
            return curve.subList(curve.size() - 250, curve.size());
        }
        return curve;
    }

    private PaperTradeBetDto toDto(PaperTradeBet bet) {
        String trackingState = deriveTrackingState(bet, LocalDateTime.now());
        return new PaperTradeBetDto(
                bet.getId(),
                bet.getStatus(),
                bet.getSource(),
                bet.getStrategy(),
                bet.getModelVersion(),
                bet.getEventName(),
                bet.getCompetitionName(),
                bet.isLiveAtPlacement(),
                bet.getStartTimeIso(),
                bet.getExternalEventId(),
                bet.getPlayer1Name(),
                bet.getPlayer2Name(),
                bet.getSideName(),
                bet.getAmericanOdds(),
                bet.getDecimalOdds(),
                bet.getStake(),
                bet.getPotentialPayout(),
                bet.getProfitLoss(),
                bet.getModelProbability(),
                bet.getImpliedProbability(),
                bet.getEdge(),
                bet.getConfidenceLow(),
                bet.getConfidenceHigh(),
                bet.getTopTrigger(),
                bet.getTopTriggerContribution(),
                bet.getGrade(),
                bet.getRationale(),
                bet.getLastObservedScore(),
                bet.getLastObservedPhase(),
                bet.getLastScoreSource(),
                bet.getLastScoreConfidence(),
                bet.isLastObservationDisplayed(),
                bet.isLastObservationResulted(),
                bet.isLastMatchCompleted(),
                bet.getLastSourceFeedCode(),
                bet.getLastSourceFeedEventId(),
                bet.getLastScoreDetail(),
                bet.isTrackedAfterClose(),
                trackingState,
                bet.getSettlementReason(),
                bet.getSettlementSource(),
                bet.getLastObservedAt(),
                bet.getPlacedAt(),
                bet.getSettledAt(),
                bet.getEventKey(),
                bet.getDedupeKey(),
                bet.getResultMatchId(),
                bet.getWinnerPlayerId()
        );
    }

    private String deriveTrackingState(PaperTradeBet bet, LocalDateTime now) {
        if (bet == null) {
            return "UNKNOWN";
        }
        String status = safeText(bet.getStatus(), "").trim().toUpperCase(Locale.ROOT);
        if (!PaperTradeBet.STATUS_OPEN.equals(status)) {
            if (PaperTradeBet.STATUS_VOIDED.equals(status)) {
                return "VOIDED";
            }
            if (PaperTradeBet.STATUS_PUSHED.equals(status)) {
                return "PUSHED";
            }
            return "SETTLED";
        }
        if (bet.isTrackedAfterClose()) {
            Optional<TrackedMatchObservation> latestTrackedObservation = preferredTrackedObservationForBet(bet);
            if (latestTrackedObservation.isPresent()
                    && shouldHoldOpenWithTrackedObservation(latestTrackedObservation.get(), now)) {
                return "MARKET_CLOSED_SCORE_TRACKED";
            }
            return "MARKET_CLOSED_SCORE_STALE";
        }
        if (StringUtils.hasText(bet.getLastObservedScore())) {
            return "OPEN_SCORE_VISIBLE";
        }
        if (hasMeaningfulVisiblePhase(bet.getLastObservedPhase())) {
            return "OPEN_SCORE_VISIBLE";
        }
        return "OPEN_PENDING_SCORE";
    }

    private boolean hasMeaningfulVisiblePhase(String phase) {
        if (!StringUtils.hasText(phase)) {
            return false;
        }
        String normalized = phase.trim().toUpperCase(Locale.ROOT);
        return !normalized.isBlank() && !"UPCOMING".equals(normalized);
    }

    private TrackedMatchObservationDto toObservationDto(TrackedMatchObservation observation) {
        return new TrackedMatchObservationDto(
                observation.getId(),
                observation.getSessionId(),
                observation.getBetId(),
                observation.getEventKey(),
                observation.getDedupeKey(),
                observation.getExternalEventId(),
                observation.getSource(),
                observation.getSourceKind(),
                round4(observation.getSourceConfidence()),
                observation.isDisplayed(),
                observation.isResulted(),
                observation.isMatchCompleted(),
                observation.getSourceFeedCode(),
                observation.getSourceFeedEventId(),
                observation.isLive(),
                observation.isTrackedAfterClose(),
                observation.getEventName(),
                observation.getCompetitionName(),
                observation.getStartTimeIso(),
                observation.getPlayer1Id(),
                observation.getPlayer1Name(),
                observation.getPlayer2Id(),
                observation.getPlayer2Name(),
                observation.getLiveScore(),
                observation.getMatchPhase(),
                observation.getScoreDetail(),
                observation.getObservedAt()
        );
    }

    private Match selectBestSettlementCandidate(Long sessionId,
                                                PaperTradeBet bet,
                                                LocalDate targetDate,
                                                List<Match> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        LocalDate placedDate = bet.getPlacedAt() == null ? targetDate : bet.getPlacedAt().toLocalDate();
        long lagLimit = clamp(maxSettlementLagDays, 3, 120);

        Match best = null;
        long bestScore = Long.MAX_VALUE;
        for (Match candidate : candidates) {
            if (candidate.getId() == null || candidate.getDate() == null) {
                continue;
            }
            if (candidate.getDate().isBefore(placedDate)) {
                continue;
            }
            long absDays = Math.abs(ChronoUnit.DAYS.between(targetDate, candidate.getDate()));
            if (absDays > lagLimit) {
                continue;
            }

            Optional<PaperTradeBet> existingByMatch = betRepository.findFirstBySessionIdAndResultMatchIdOrderByIdAsc(
                    sessionId,
                    candidate.getId()
            );
            if (existingByMatch.isPresent()) {
                PaperTradeBet existing = existingByMatch.get();
                if (!Objects.equals(existing.getEventKey(), bet.getEventKey())) {
                    continue;
                }
            }

            long beforePenalty = candidate.getDate().isBefore(targetDate) ? 4 : 0;
            long score = (absDays * 10) + beforePenalty;
            if (best == null || score < bestScore
                    || (score == bestScore && (candidate.getDate().isBefore(best.getDate())
                    || (Objects.equals(candidate.getDate(), best.getDate()) && candidate.getId() > best.getId())))) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private Match resolveDatabaseSettlementCandidate(Long sessionId, PaperTradeBet bet, LocalDate targetDate) {
        if (bet == null || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return null;
        }
        LocalDate placedDate = bet.getPlacedAt() == null
                ? targetDate
                : bet.getPlacedAt().toLocalDate();
        Match identityMatch = resolveFeedIdentitySettlementCandidate(bet, placedDate);
        if (identityMatch != null) {
            return identityMatch;
        }
        LocalDate fromDate = placedDate.isAfter(targetDate) ? placedDate : targetDate;
        List<Match> candidates = matchRepository.findCompletedMatchesByPlayersSince(
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                fromDate
        );
        return selectBestSettlementCandidate(sessionId, bet, targetDate, candidates);
    }

    private Match resolveOfficialResultSettlementCandidate(Long sessionId,
                                                           PaperTradeBet bet,
                                                           LocalDate targetDate,
                                                           LocalDateTime now,
                                                           boolean trackedAfterCloseContext,
                                                           OfficialResultRefreshContext refreshContext) {
        if (!officialResultConfirmationEnabled
                || bet == null
                || bet.getPlayer1Id() == null
                || bet.getPlayer2Id() == null
                || targetDate == null
                || refreshContext == null) {
            return null;
        }
        if (!isWithinOfficialResultWindow(targetDate, now)) {
            return null;
        }
        Match existing = findOfficialResultSettlementCandidate(sessionId, bet, targetDate);
        if (existing != null) {
            return existing;
        }
        if (!shouldAttemptOfficialResultRefresh(trackedAfterCloseContext, refreshContext)) {
            return null;
        }
        refreshOfficialResultArchive(refreshContext);
        return findOfficialResultSettlementCandidate(sessionId, bet, targetDate);
    }

    private Match findOfficialResultSettlementCandidate(Long sessionId,
                                                        PaperTradeBet bet,
                                                        LocalDate targetDate) {
        if (bet == null || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return null;
        }
        LocalDate placedDate = bet.getPlacedAt() == null
                ? targetDate
                : bet.getPlacedAt().toLocalDate();
        Match identityMatch = resolveFeedIdentitySettlementCandidate(bet, placedDate);
        if (identityMatch != null) {
            return identityMatch;
        }
        LocalDate fromDate = placedDate.isAfter(targetDate) ? placedDate : targetDate;
        List<Match> candidates = matchRepository.findCompletedMatchesByPlayersSince(
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                fromDate
        );
        return selectBestSettlementCandidate(sessionId, bet, targetDate, candidates);
    }

    private boolean isWithinOfficialResultWindow(LocalDate targetDate, LocalDateTime now) {
        if (targetDate == null || now == null) {
            return false;
        }
        int maxAgeDays = clamp(officialResultMaxAgeDays, 0, 14);
        LocalDate floor = now.toLocalDate().minusDays(maxAgeDays);
        return !targetDate.isBefore(floor);
    }

    private boolean shouldAttemptOfficialResultRefresh(boolean trackedAfterCloseContext,
                                                       OfficialResultRefreshContext refreshContext) {
        if (!officialResultRefreshEnabled || refreshContext.attempted()) {
            return false;
        }
        return !officialResultRefreshTrackedAfterCloseOnly || trackedAfterCloseContext;
    }

    private void refreshOfficialResultArchive(OfficialResultRefreshContext refreshContext) {
        refreshContext.markAttempted();
        int pages = clamp(officialResultRefreshPages, 1, 5);
        try {
            int saved = ttSeriesScraper.refreshRecentOfficialResults(pages);
            refreshContext.recordResult(saved);
            log.info("[paper] official-result refresh complete: pages={}, saved={}", pages, saved);
        } catch (Exception ex) {
            refreshContext.recordFailure();
            log.warn("[paper] official-result refresh failed: {}", ex.getMessage());
        }
    }

    private Match resolveFeedIdentitySettlementCandidate(PaperTradeBet bet, LocalDate placedDate) {
        if (bet == null || !StringUtils.hasText(bet.getLastSourceFeedEventId())) {
            return null;
        }
        String feedEventId = bet.getLastSourceFeedEventId().trim();
        if (!StringUtils.hasText(feedEventId)) {
            return null;
        }
        List<Match> candidates = matchRepository.findMatchesByFeedEventIdentity(feedEventId, PageRequest.of(0, 10));
        for (Match candidate : candidates) {
            if (candidate == null || candidate.getDate() == null) {
                continue;
            }
            if (candidate.getDate().isBefore(placedDate)) {
                continue;
            }
            if (!samePlayerSet(bet, candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private boolean matchesFeedIdentity(PaperTradeBet bet, Match candidate) {
        if (bet == null || candidate == null || !StringUtils.hasText(bet.getLastSourceFeedEventId())) {
            return false;
        }
        String feedEventId = bet.getLastSourceFeedEventId().trim();
        return feedEventId.equalsIgnoreCase(safeText(candidate.getSourceFeedEventId(), ""))
                || feedEventId.equalsIgnoreCase(safeText(candidate.getExternalId(), ""));
    }

    private boolean samePlayerSet(PaperTradeBet bet, Match candidate) {
        if (bet == null || candidate == null
                || candidate.getPlayer1() == null || candidate.getPlayer2() == null
                || candidate.getPlayer1().getId() == null || candidate.getPlayer2().getId() == null
                || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return true;
        }
        Long candidateP1 = candidate.getPlayer1().getId();
        Long candidateP2 = candidate.getPlayer2().getId();
        return (Objects.equals(candidateP1, bet.getPlayer1Id()) && Objects.equals(candidateP2, bet.getPlayer2Id()))
                || (Objects.equals(candidateP1, bet.getPlayer2Id()) && Objects.equals(candidateP2, bet.getPlayer1Id()));
    }

    private String scoreLabel(Match match) {
        if (match == null) {
            return "N/A";
        }
        if (StringUtils.hasText(match.getResult())) {
            return match.getResult().trim();
        }
        if (match.getPlayer1SetsWon() != null && match.getPlayer2SetsWon() != null) {
            return match.getPlayer1SetsWon() + ":" + match.getPlayer2SetsWon();
        }
        return "N/A";
    }

    private String winnerName(Match match, String p1, String p2) {
        if (match == null || match.getWinnerPlayerId() == null) {
            return "N/A";
        }
        if (match.getPlayer1() != null && match.getPlayer1().getId() != null
                && match.getWinnerPlayerId().equals(match.getPlayer1().getId())) {
            return p1;
        }
        if (match.getPlayer2() != null && match.getPlayer2().getId() != null
                && match.getWinnerPlayerId().equals(match.getPlayer2().getId())) {
            return p2;
        }
        return "N/A";
    }

    private String loserName(Match match, String p1, String p2, String winner) {
        if (match == null || !StringUtils.hasText(winner) || "N/A".equalsIgnoreCase(winner)) {
            return "N/A";
        }
        if (winner.equals(p1)) {
            return p2;
        }
        if (winner.equals(p2)) {
            return p1;
        }
        return "N/A";
    }

    private LocalDate settlementTargetDate(PaperTradeBet bet) {
        if (bet == null) {
            return LocalDate.now();
        }
        Optional<LocalDateTime> parsedStart = parseStartDateTime(bet.getStartTimeIso());
        if (parsedStart.isPresent()) {
            return parsedStart.get().toLocalDate();
        }
        if (StringUtils.hasText(bet.getStartTimeIso())) {
            String raw = bet.getStartTimeIso().trim();
            if (raw.length() >= 10) {
                try {
                    return LocalDate.parse(raw.substring(0, 10));
                } catch (Exception ignore) {
                    // fall through to placedAt
                }
            }
        }
        if (bet.getPlacedAt() != null) {
            return bet.getPlacedAt().toLocalDate();
        }
        return LocalDate.now();
    }

    private static final class OfficialResultRefreshContext {
        private boolean attempted;
        private boolean succeeded;
        private int saved;

        boolean attempted() {
            return attempted;
        }

        void markAttempted() {
            this.attempted = true;
        }

        void recordResult(int saved) {
            this.succeeded = true;
            this.saved = Math.max(0, saved);
        }

        void recordFailure() {
            this.succeeded = false;
        }

        @SuppressWarnings("unused")
        boolean succeeded() {
            return succeeded;
        }

        @SuppressWarnings("unused")
        int saved() {
            return saved;
        }
    }

    private boolean isSettlementWindowOpen(PaperTradeBet bet, LocalDate targetDate) {
        if (bet == null) {
            return false;
        }
        Optional<LocalDateTime> start = parseStartDateTime(bet.getStartTimeIso());
        if (start.isPresent()) {
            int delay = clamp(settlementDelayMinutes, 0, 720);
            LocalDateTime gate = start.get().plusMinutes(delay);
            return !LocalDateTime.now().isBefore(gate);
        }
        return !LocalDate.now().isBefore(targetDate.plusDays(1));
    }

    private boolean isEligible(LiveOddsRecommendationDto row, AdaptiveProfile adaptiveProfile) {
        return eligibilityRejectionReason(row, adaptiveProfile) == null;
    }

    private String eligibilityRejectionReason(LiveOddsRecommendationDto row, AdaptiveProfile adaptiveProfile) {
        if (row == null) {
            return "ROW_NULL";
        }
        if (!isEventTimingEligible(row)) {
            return "EVENT_NOT_UPCOMING";
        }
        if (row.live() && !allowLive) {
            return "LIVE_DISABLED";
        }
        if (!row.live() && !allowPrematch) {
            return "PREMATCH_DISABLED";
        }
        if (row.player1Id() == null || row.player2Id() == null) {
            return "MISSING_PLAYER_IDS";
        }
        if (row.player1Id().equals(row.player2Id())) {
            return "DUPLICATE_PLAYER_IDS";
        }
        if (row.suggestedEdge() == null) {
            return "MISSING_SUGGESTED_EDGE";
        }
        double edgeThreshold = row.live()
                ? clamp(minEdgeLive, 0.005, 0.20)
                : clamp(minEdgePrematch, 0.005, 0.20);
        edgeThreshold = Math.max(edgeThreshold, clamp(minEdgeForBet, 0.005, 0.25));
        edgeThreshold = clamp(
                edgeThreshold
                        + adaptiveProfile.edgeShift()
                        + adaptiveProfile.signalFor(normalizeTrigger(row.topTrigger())).edgeThresholdShift(),
                0.005,
                0.30
        );
        if (row.suggestedEdge() < edgeThreshold) {
            return "EDGE_BELOW_THRESHOLD";
        }

        if (row.suggestedFairAmericanOdds() != null && row.suggestedFairAmericanOdds() > maxLongshotAmericanOdds) {
            return "FAIR_ODDS_TOO_LONG";
        }

        if (row.confidenceLow() != null && row.confidenceHigh() != null) {
            double width = row.confidenceHigh() - row.confidenceLow();
            double softMaxWidth = clamp(maxConfidenceWidth, 0.10, 0.80);
            softMaxWidth = clamp(softMaxWidth - adaptiveProfile.confidenceWidthTightening(), 0.08, 0.80);
            double relaxedWidth = row.live() ? softMaxWidth + 0.35 : softMaxWidth + 0.25;
            // Wide intervals are tolerated only when edge clears threshold by a meaningful margin.
            if (width > relaxedWidth && row.suggestedEdge() < (edgeThreshold * 2.0)) {
                return "CONFIDENCE_TOO_WIDE";
            }
        }

        if (requireRecommendation && !row.recommended()) {
            return "RECOMMENDATION_REQUIRED";
        }
        if (row.suggestedSide() == null) {
            return "MISSING_SUGGESTED_SIDE";
        }
        return null;
    }

    private boolean isEventTimingEligible(LiveOddsRecommendationDto row) {
        if (row == null) {
            return false;
        }
        if (!onlyUpcoming) {
            return true;
        }
        if (row.live()) {
            return false;
        }
        if (isFinishedPhase(row.matchPhase())) {
            return false;
        }

        Optional<LocalDateTime> startOpt = parseStartDateTime(row.startTimeIso());
        if (startOpt.isEmpty()) {
            return true;
        }
        int graceMinutes = clamp(startTimeGraceMinutes, 0, 240);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(graceMinutes);
        return !startOpt.get().isBefore(cutoff);
    }

    private boolean isFinishedPhase(String phaseRaw) {
        if (!StringUtils.hasText(phaseRaw)) {
            return false;
        }
        String phase = phaseRaw.trim().toUpperCase(Locale.ROOT);
        return phase.contains("FINISH")
                || phase.contains("FINAL")
                || phase.contains("ENDED")
                || phase.contains("CLOSED")
                || phase.contains("SETTLED")
                || phase.contains("RESULT")
                || phase.contains("COMPLETE");
    }

    private Optional<LocalDateTime> parseStartDateTime(String startTimeIso) {
        if (!StringUtils.hasText(startTimeIso)) {
            return Optional.empty();
        }
        String v = startTimeIso.trim();
        try {
            return Optional.of(OffsetDateTime.parse(v).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (Exception ignore) {
            // continue
        }
        try {
            return Optional.of(java.time.Instant.parse(v).atZone(ZoneId.systemDefault()).toLocalDateTime());
        } catch (Exception ignore) {
            // continue
        }
        String localLike = (v.contains(" ") && !v.contains("T"))
                ? v.replace(' ', 'T')
                : v;
        try {
            return Optional.of(LocalDateTime.parse(localLike));
        } catch (Exception ignore) {
            // continue
        }
        try {
            if (v.length() >= 10) {
                LocalDate d = LocalDate.parse(v.substring(0, 10));
                return Optional.of(d.plusDays(1).atStartOfDay().minusSeconds(1));
            }
        } catch (Exception ignore) {
            // continue
        }
        return Optional.empty();
    }

    private BetCandidate toCandidate(LiveOddsRecommendationDto row, AdaptiveProfile adaptiveProfile) {
        return resolveCandidate(row, adaptiveProfile).candidate();
    }

    private CandidateResolution resolveCandidate(LiveOddsRecommendationDto row, AdaptiveProfile adaptiveProfile) {
        String side = row.suggestedSide();
        if (!StringUtils.hasText(side)) {
            return new CandidateResolution(null, "MISSING_SUGGESTED_SIDE");
        }
        boolean pickPlayer1 = side.trim().equalsIgnoreCase(row.player1Name());
        boolean pickPlayer2 = side.trim().equalsIgnoreCase(row.player2Name());

        if (!pickPlayer1 && !pickPlayer2) {
            if (row.edgePlayer1() == null || row.edgePlayer2() == null) {
                return new CandidateResolution(null, "UNRESOLVED_SUGGESTED_SIDE");
            }
            pickPlayer1 = row.edgePlayer1() >= row.edgePlayer2();
            pickPlayer2 = !pickPlayer1;
        }

        Long sidePlayerId = pickPlayer1 ? row.player1Id() : row.player2Id();
        if (sidePlayerId == null) {
            return new CandidateResolution(null, "MISSING_SIDE_PLAYER_ID");
        }

        double rawModelProbability = pickPlayer1
                ? valueOrZero(row.modelProbabilityPlayer1())
                : valueOrZero(row.modelProbabilityPlayer2());
        double impliedProbability = pickPlayer1
                ? row.impliedProbabilityPlayer1()
                : row.impliedProbabilityPlayer2();
        double decimalOdds = pickPlayer1
                ? row.decimalOddsPlayer1()
                : row.decimalOddsPlayer2();
        int americanOdds = pickPlayer1
                ? row.americanOddsPlayer1()
                : row.americanOddsPlayer2();
        String trigger = normalizeTrigger(row.topTrigger());
        TriggerAdaptiveSignal triggerSignal = adaptiveProfile.signalFor(trigger);
        double modelProbabilityShift = adaptiveProfile.modelProbabilityShift() + triggerSignal.probabilityShift();
        double modelProbability = clamp(rawModelProbability + modelProbabilityShift, 0.01, 0.99);
        double adaptiveEdge = modelProbability - impliedProbability;
        double signalQuality = candidateSignalQuality(row, triggerSignal);

        return new CandidateResolution(new BetCandidate(
                sidePlayerId,
                pickPlayer1 ? row.player1Name() : row.player2Name(),
                modelProbability,
                impliedProbability,
                adaptiveEdge,
                decimalOdds,
                americanOdds,
                modelProbabilityShift,
                signalQuality,
                trigger,
                triggerSignal
        ), null);
    }

    private boolean isCandidateSafe(LiveOddsRecommendationDto row, BetCandidate candidate, AdaptiveProfile adaptiveProfile) {
        return candidateSafetyRejectionReason(row, candidate, adaptiveProfile) == null;
    }

    private String candidateSafetyRejectionReason(LiveOddsRecommendationDto row,
                                                  BetCandidate candidate,
                                                  AdaptiveProfile adaptiveProfile) {
        if (candidate.decimalOdds() <= 1.0) {
            return "INVALID_DECIMAL_ODDS";
        }
        double ciWidth = confidenceWidth(row);
        double signalQuality = candidate.signalQuality();
        int maxPositiveOdds = clamp(maxPositiveAmericanOdds, 120, 420);
        if (candidate.americanOdds() > maxPositiveOdds) {
            return "AMERICAN_ODDS_TOO_HIGH";
        }
        if (candidate.americanOdds() > maxLongshotAmericanOdds) {
            return "AMERICAN_ODDS_TOO_LONG";
        }
        if (candidate.impliedProbability() < clamp(minImpliedProbability, 0.05, 0.45)) {
            return "IMPLIED_PROBABILITY_TOO_LOW";
        }
        double edgeThreshold = row.live()
                ? clamp(minEdgeLive, 0.005, 0.20)
                : clamp(minEdgePrematch, 0.005, 0.20);
        edgeThreshold = Math.max(edgeThreshold, clamp(minEdgeForBet, 0.005, 0.25));
        edgeThreshold = clamp(
                edgeThreshold + adaptiveProfile.edgeShift() + candidate.triggerSignal().edgeThresholdShift(),
                0.005,
                0.30
        );
        if (candidate.edge() < edgeThreshold) {
            return "MODEL_EDGE_BELOW_THRESHOLD";
        }
        double probabilityEdge = candidate.modelProbability() - candidate.impliedProbability();
        double requiredGap = row.live()
                ? clamp(minModelImpliedGapLive, 0.005, 0.20)
                : clamp(minModelImpliedGapPrematch, 0.005, 0.20);
        requiredGap = Math.max(requiredGap, clamp(minModelImpliedGap, 0.005, 0.20));
        requiredGap = clamp(
                requiredGap + adaptiveProfile.modelGapShift() + candidate.triggerSignal().modelGapShift(),
                0.005,
                0.25
        );
        if (probabilityEdge < requiredGap) {
            return "MODEL_GAP_BELOW_THRESHOLD";
        }
        double expectedRoi = (candidate.modelProbability() * candidate.decimalOdds()) - 1.0;
        double minExpectedReturn = clamp(minExpectedRoi, 0.0, 0.20);
        minExpectedReturn += clamp(Math.max(0.0, 0.68 - signalQuality) * 0.07, 0.0, 0.03);
        if (candidate.americanOdds() > 0) {
            minExpectedReturn += clamp(Math.max(0.0, candidate.americanOdds() - 100) / 2200.0, 0.0, 0.05);
            minExpectedReturn += clamp(Math.max(0.0, ciWidth - 0.28) * 0.12, 0.0, 0.04);
        }
        if (expectedRoi < minExpectedReturn) {
            return "EXPECTED_ROI_BELOW_THRESHOLD";
        }
        if (candidate.americanOdds() > 0
                && ciWidth > 0.42
                && signalQuality < 0.72
                && candidate.edge() < Math.max(edgeThreshold * 2.5, 0.07)) {
            return "PLUS_MONEY_CONFIDENCE_TOO_WIDE";
        }
        if (signalQuality < 0.55
                && candidate.edge() < Math.max(edgeThreshold * 2.0, 0.05)) {
            return "LOW_QUALITY_SIGNAL";
        }

        String phase = StringUtils.hasText(row.matchPhase()) ? row.matchPhase().trim().toUpperCase(Locale.ROOT) : "";
        if ("LIVE_LATE".equals(phase) && candidate.americanOdds() > 130) {
            return "LIVE_LATE_LONGSHOT_BLOCKED";
        }
        if ("LIVE_LATE".equals(phase) && signalQuality < 0.64) {
            return "LIVE_LATE_LOW_QUALITY";
        }
        return null;
    }

    private double scoreCandidate(LiveOddsRecommendationDto row, BetCandidate candidate, AdaptiveProfile adaptiveProfile) {
        double edge = clamp(candidate.edge(), -0.20, 0.30);
        double probabilityEdge = clamp(candidate.modelProbability() - candidate.impliedProbability(), -0.25, 0.35);
        double ciWidth = confidenceWidth(row);
        double signalQuality = candidate.signalQuality();

        double score = edge * 130.0;
        score += probabilityEdge * 80.0;
        score += (0.30 - ciWidth) * 7.0;
        score += (candidate.impliedProbability() - 0.35) * 8.0;
        score += row.recommended() ? 1.0 : 0.0;
        score += row.live() ? 0.25 : 0.9;
        score += (signalQuality - 0.72) * 12.0;
        score += clamp(valueOrZero(row.topTriggerContribution()) * 2.2, -0.8, 0.8);
        score += gradeScore(row.grade());

        if (candidate.americanOdds() > 0) {
            score -= Math.max(0.0, (candidate.americanOdds() - 110) / 28.0);
            score -= Math.max(0.0, 0.74 - signalQuality) * 2.4;
        }

        if (ciWidth > 0.40) {
            score -= 2.0;
        }
        if (ciWidth > 0.32) {
            score -= (ciWidth - 0.32) * 9.0;
        }
        if (candidate.triggerSignal().sampleSize() > 0 && candidate.triggerSignal().sampleSize() < 5) {
            score -= 0.5;
        }

        String phase = StringUtils.hasText(row.matchPhase()) ? row.matchPhase().trim().toUpperCase(Locale.ROOT) : "";
        if ("LIVE_LATE".equals(phase)) {
            score -= 1.0;
        } else if ("LIVE_MID".equals(phase)) {
            score -= 0.5;
        }
        score += clamp(candidate.modelProbabilityShiftApplied() * 40.0, -0.8, 0.8);
        score += clamp((edge - adaptiveProfile.avgSettledEdge()) * 12.0, -1.0, 1.0);
        score -= adaptiveProfile.selectionPenalty();
        score -= candidate.triggerSignal().selectionPenalty();
        return score;
    }

    private Comparator<RankedCandidate> rankComparator() {
        return Comparator
                .comparingDouble(RankedCandidate::selectionScore).reversed()
                .thenComparing((a, b) -> Double.compare(
                        Math.abs(valueOrZero(b.row().suggestedEdge())),
                        Math.abs(valueOrZero(a.row().suggestedEdge()))
                ));
    }

    private double computeStake(double bankroll,
                                double edge,
                                double modelProbability,
                                double decimalOdds,
                                Double confidenceLow,
                                Double confidenceHigh,
                                double signalQuality,
                                boolean live,
                                String matchPhase,
                                int marketAmericanOdds,
                                AdaptiveProfile adaptiveProfile) {
        double available = Math.max(0.0, bankroll);
        if (available <= minStake) {
            return 0.0;
        }

        double b = Math.max(EPS, decimalOdds - 1.0);
        double p = clamp(modelProbability, 0.01, 0.99);
        double q = 1.0 - p;
        double kelly = ((b * p) - q) / b;
        double fractionalKelly = clamp(kelly, 0.0, 0.25) * 0.35;

        double ciWidth;
        if (confidenceLow == null || confidenceHigh == null) {
            ciWidth = 0.24;
        } else {
            ciWidth = Math.max(0.03, Math.min(0.60, confidenceHigh - confidenceLow));
        }
        double confidenceFactor = clamp(1.1 - (ciWidth * 1.4), 0.45, 1.15);
        double edgeBoost = clamp(edge, 0.0, 0.20) * 0.22;

        double pct = clamp(baseStakePct, 0.005, 0.08);
        pct += fractionalKelly;
        pct += edgeBoost;
        pct *= confidenceFactor;
        pct *= clamp(0.50 + (signalQuality * 0.60), 0.55, 1.12);

        if (live) {
            String phase = StringUtils.hasText(matchPhase) ? matchPhase.trim().toUpperCase(Locale.ROOT) : "LIVE";
            double liveFactor = switch (phase) {
                case "LIVE_EARLY" -> 0.95;
                case "LIVE_MID" -> 0.85;
                case "LIVE_LATE" -> 0.70;
                default -> 0.82;
            };
            pct *= liveFactor;
        }
        if (marketAmericanOdds > 0) {
            double longshotFactor = clamp(1.0 - (marketAmericanOdds / 900.0), 0.45, 1.0);
            pct *= longshotFactor;
            pct *= clamp(0.55 + (signalQuality * 0.45), 0.55, 1.0);
        }

        pct *= clamp(adaptiveProfile.stakeMultiplier(), 0.65, 1.2);

        pct = clamp(pct, 0.005, clamp(maxStakePct, 0.01, 0.20));

        double stake = available * pct;
        stake = Math.min(stake, available);
        stake = clamp(stake, minStake, maxStake);
        stake = Math.min(stake, available);
        return round2(stake);
    }

    private double applyExposureCaps(double proposedStake,
                                     BetCandidate candidate,
                                     ExposureProfile exposureProfile,
                                     double capitalBase) {
        double stake = Math.max(0.0, proposedStake);
        if (stake <= EPS || candidate == null || exposureProfile == null) {
            return round2(stake);
        }
        double capital = Math.max(100.0, capitalBase);
        double maxOpenStake = capital * clamp(maxOpenExposurePct, 0.10, 0.95);
        double openRemaining = Math.max(0.0, maxOpenStake - exposureProfile.openStake());
        stake = Math.min(stake, openRemaining);

        if (candidate.sidePlayerId() != null) {
            double playerCap = capital * clamp(maxExposurePerPlayerPct, 0.03, 0.60);
            double playerRemaining = Math.max(0.0, playerCap - exposureProfile.playerStake(candidate.sidePlayerId()));
            stake = Math.min(stake, playerRemaining);
        }

        String triggerKey = candidate.triggerKey();
        if (StringUtils.hasText(triggerKey)) {
            double triggerCap = capital * clamp(maxExposurePerTriggerPct, 0.05, 0.75);
            double triggerRemaining = Math.max(0.0, triggerCap - exposureProfile.triggerStake(triggerKey));
            stake = Math.min(stake, triggerRemaining);
        }

        return round2(Math.max(0.0, stake));
    }

    private String buildEventKey(LiveOddsRecommendationDto row) {
        String startBucket = StringUtils.hasText(row.startTimeIso())
                ? row.startTimeIso().trim()
                : LocalDate.now().toString();
        return normalizeKey(row.competitionName()) + "|"
                + normalizeKey(row.eventName()) + "|"
                + normalizeKey(row.player1Name()) + "|"
                + normalizeKey(row.player2Name()) + "|"
                + normalizeKey(startBucket);
    }

    private PaperTradeSession getOrCreateActiveSession() {
        return sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE)
                .orElseGet(() -> createSession(null, null));
    }

    private PaperTradeSession createSession(Double startingBankroll, String label) {
        double start = startingBankroll == null
                ? clamp(defaultStartingBankroll, 100.0, 1_000_000.0)
                : clamp(startingBankroll, 100.0, 1_000_000.0);

        PaperTradeSession session = new PaperTradeSession();
        session.setStatus(PaperTradeSession.STATUS_ACTIVE);
        session.setLabel(StringUtils.hasText(label) ? label.trim() : "Paper Session " + LocalDate.now());
        session.setStartingBankroll(round2(start));
        session.setCurrentBankroll(round2(start));
        session.setPeakBankroll(round2(start));
        session.setRealizedPnl(0.0);
        session.setTotalStaked(0.0);
        session.setTotalReturned(0.0);
        session.setTotalBets(0);
        session.setWins(0);
        session.setLosses(0);
        session.setPushes(0);
        session.setSimulationRowsScanned(0);
        session.setSimulationBetsPlaced(0);
        session.setSimulationBetsSettled(0);
        session.setSimulationBetsVoided(0);
        session.setAdaptiveSampleSize(0);
        session.setAdaptiveEdgeShift(0.0);
        session.setAdaptiveSelectionScoreShift(0.0);
        session.setAdaptiveStakeMultiplier(1.0);
        session.setAdaptiveCalibrationError(0.0);
        session.setAdaptiveRoiSignal(0.0);
        session.setAdaptiveUpdatedAt(null);
        session.setLastSyncAt(null);
        return sessionRepository.save(session);
    }

    private void persistDecisionSample(Long sessionId,
                                       String strategy,
                                       String modelVersion,
                                       LiveOddsRecommendationDto row,
                                       BetCandidate candidate,
                                       String eventKey,
                                       String dedupeKey,
                                       Double selectionScore,
                                       Double proposedStake,
                                       Double cappedStake,
                                       boolean fallbackPick,
                                       String decisionStatus,
                                       String decisionReason) {
        if (sessionId == null || row == null) {
            return;
        }
        PaperTradeDecisionSample sample = new PaperTradeDecisionSample();
        sample.setSessionId(sessionId);
        sample.setSource(safeText(row.source(), "UNKNOWN"));
        sample.setStrategy(safeText(strategy, "CONSERVATIVE"));
        sample.setModelVersion(safeText(modelVersion, "ENSEMBLE"));
        sample.setEventKey(StringUtils.hasText(eventKey) ? eventKey.trim() : resolveDecisionEventKey(row));
        sample.setDedupeKey(StringUtils.hasText(dedupeKey) ? dedupeKey.trim() : resolveDecisionDedupeKey(row, eventKey, candidate));
        sample.setEventName(safeText(row.eventName(), "Unknown Event"));
        sample.setCompetitionName(safeText(row.competitionName(), "Table Tennis"));
        sample.setLive(row.live());
        sample.setPlayer1Id(row.player1Id());
        sample.setPlayer1Name(row.player1Name());
        sample.setPlayer2Id(row.player2Id());
        sample.setPlayer2Name(row.player2Name());
        sample.setSidePlayerId(candidate == null ? null : candidate.sidePlayerId());
        sample.setSideName(candidate == null ? row.suggestedSide() : candidate.sideName());
        sample.setTopTrigger(row.topTrigger());
        sample.setRecommended(row.recommended());
        sample.setFallbackPick(fallbackPick);
        sample.setSuggestedEdge(valueOrZero(row.suggestedEdge()));
        sample.setModelProbability(candidate == null ? null : candidate.modelProbability());
        sample.setImpliedProbability(candidate == null ? null : candidate.impliedProbability());
        sample.setSelectionScore(selectionScore == null ? null : round4(selectionScore));
        sample.setSignalQuality(candidate == null ? null : round4(candidate.signalQuality()));
        sample.setConfidenceWidth(round4(confidenceWidth(row)));
        sample.setAmericanOdds(candidate == null ? null : candidate.americanOdds());
        sample.setProposedStake(proposedStake == null ? null : round2(proposedStake));
        sample.setCappedStake(cappedStake == null ? null : round2(cappedStake));
        sample.setDecisionStatus(safeText(decisionStatus, "SKIPPED"));
        sample.setDecisionReason(safeText(decisionReason, "UNKNOWN"));
        decisionSampleRepository.save(sample);
    }

    private void applyAdaptiveSnapshot(PaperTradeSession session, AdaptiveProfile profile, LocalDateTime updatedAt) {
        if (session == null || profile == null) {
            return;
        }
        session.setAdaptiveSampleSize(profile.sampleSize());
        session.setAdaptiveEdgeShift(round4(profile.edgeShift()));
        session.setAdaptiveSelectionScoreShift(round4(profile.selectionScoreShift()));
        session.setAdaptiveStakeMultiplier(round4(profile.stakeMultiplier()));
        session.setAdaptiveCalibrationError(round4(profile.calibrationError()));
        session.setAdaptiveRoiSignal(round4(profile.roiSignal()));
        session.setAdaptiveUpdatedAt(updatedAt == null ? LocalDateTime.now() : updatedAt);
    }

    private void persistLearningSample(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null || !StringUtils.hasText(bet.getStatus())) {
            return;
        }
        String status = bet.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!(PaperTradeBet.STATUS_WON.equals(status)
                || PaperTradeBet.STATUS_LOST.equals(status)
                || PaperTradeBet.STATUS_PUSHED.equals(status)
                || PaperTradeBet.STATUS_VOIDED.equals(status))) {
            return;
        }
        if (learningSampleRepository.existsByBetId(bet.getId())) {
            return;
        }
        PaperTradeLearningSample sample = new PaperTradeLearningSample();
        sample.setBetId(bet.getId());
        sample.setSessionId(bet.getSessionId() == null ? -1L : bet.getSessionId());
        sample.setStatus(status);
        sample.setSource(safeText(bet.getSource(), "UNKNOWN"));
        sample.setStrategy(safeText(bet.getStrategy(), "CONSERVATIVE"));
        sample.setModelVersion(safeText(bet.getModelVersion(), "ENSEMBLE"));
        sample.setTopTrigger(safeText(bet.getTopTrigger(), "Unknown Trigger"));
        sample.setLiveAtPlacement(bet.isLiveAtPlacement());
        sample.setModelProbability(clamp(bet.getModelProbability(), 0.01, 0.99));
        sample.setImpliedProbability(clamp(bet.getImpliedProbability(), 0.01, 0.99));
        sample.setEdge(clamp(bet.getEdge(), -0.30, 0.40));
        sample.setStake(Math.max(0.0, bet.getStake()));
        sample.setProfitLoss(bet.getProfitLoss() == null ? 0.0 : bet.getProfitLoss());
        sample.setConfidenceWidth((bet.getConfidenceLow() != null && bet.getConfidenceHigh() != null)
                ? Math.max(0.0, bet.getConfidenceHigh() - bet.getConfidenceLow())
                : 0.0);
        sample.setLastObservedPhase(bet.getLastObservedPhase());
        sample.setPlacedAt(bet.getPlacedAt());
        sample.setSettledAt(bet.getSettledAt() == null ? LocalDateTime.now() : bet.getSettledAt());
        learningSampleRepository.save(sample);
    }

    private List<AdaptiveDecisionSample> loadAdaptiveDecisionSamples(int historyTake) {
        int take = clamp(historyTake, 20, 500);
        List<AdaptiveDecisionSample> out = new ArrayList<>(take);
        Set<Long> seenBetIds = new HashSet<>();

        List<PaperTradeLearningSample> learningRows = learningSampleRepository.findByStatusInOrderBySettledAtDesc(
                List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST),
                PageRequest.of(0, take)
        );
        if (learningRows.isEmpty()) {
            backfillLearningSamples(Math.max(500, take * 6));
            learningRows = learningSampleRepository.findByStatusInOrderBySettledAtDesc(
                    List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST),
                    PageRequest.of(0, take)
            );
        }
        for (PaperTradeLearningSample row : learningRows) {
            if (row == null) {
                continue;
            }
            Long betId = row.getBetId();
            if (betId != null && !seenBetIds.add(betId)) {
                continue;
            }
            out.add(new AdaptiveDecisionSample(
                    betId,
                    normalizeTrigger(row.getTopTrigger()),
                    row.getStatus(),
                    row.getModelProbability(),
                    row.getImpliedProbability(),
                    row.getEdge(),
                    row.getStake(),
                    row.getProfitLoss(),
                    row.getConfidenceWidth(),
                    row.getSettledAt()
            ));
            if (out.size() >= take) {
                return out;
            }
        }

        List<PaperTradeBet> fallbackRows = betRepository.findByStatusInOrderBySettledAtDesc(
                List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST),
                PageRequest.of(0, take * 2)
        );
        for (PaperTradeBet bet : fallbackRows) {
            if (bet == null || bet.getId() == null || !seenBetIds.add(bet.getId())) {
                continue;
            }
            persistLearningSample(bet);
            out.add(new AdaptiveDecisionSample(
                    bet.getId(),
                    normalizeTrigger(bet.getTopTrigger()),
                    bet.getStatus(),
                    clamp(bet.getModelProbability(), 0.01, 0.99),
                    clamp(bet.getImpliedProbability(), 0.01, 0.99),
                    clamp(bet.getEdge(), -0.30, 0.40),
                    Math.max(0.0, bet.getStake()),
                    bet.getProfitLoss() == null ? 0.0 : bet.getProfitLoss(),
                    (bet.getConfidenceLow() != null && bet.getConfidenceHigh() != null)
                            ? Math.max(0.0, bet.getConfidenceHigh() - bet.getConfidenceLow())
                            : 0.0,
                    bet.getSettledAt()
            ));
            if (out.size() >= take) {
                break;
            }
        }
        return out;
    }

    private int backfillLearningSamples(int limit) {
        int maxRows = clamp(limit, 20, 50000);
        int pageSize = 200;
        int scanned = 0;
        int inserted = 0;
        int page = 0;

        while (scanned < maxRows) {
            List<PaperTradeBet> rows = betRepository.findByStatusInOrderBySettledAtDesc(
                    List.of(
                            PaperTradeBet.STATUS_WON,
                            PaperTradeBet.STATUS_LOST,
                            PaperTradeBet.STATUS_PUSHED,
                            PaperTradeBet.STATUS_VOIDED
                    ),
                    PageRequest.of(page, pageSize)
            );
            if (rows.isEmpty()) {
                break;
            }

            for (PaperTradeBet bet : rows) {
                if (bet == null || bet.getId() == null) {
                    continue;
                }
                scanned++;
                if (scanned > maxRows) {
                    break;
                }
                if (learningSampleRepository.existsByBetId(bet.getId())) {
                    continue;
                }
                persistLearningSample(bet);
                inserted++;
            }

            if (rows.size() < pageSize || scanned >= maxRows) {
                break;
            }
            page++;
        }
        return inserted;
    }

    private double adaptiveRecencyWeight(LocalDateTime settledAt, LocalDateTime now, double halfLifeDays) {
        if (settledAt == null || now == null) {
            return 1.0;
        }
        long days = Math.max(0L, ChronoUnit.DAYS.between(settledAt.toLocalDate(), now.toLocalDate()));
        double halfLife = Math.max(2.0, halfLifeDays);
        return Math.pow(0.5, days / halfLife);
    }

    private String normalizeTrigger(String trigger) {
        return normalizeTriggerStatic(trigger);
    }

    private static String normalizeTriggerStatic(String trigger) {
        if (!StringUtils.hasText(trigger)) {
            return "unknown trigger";
        }
        return trigger.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStrategy(String strategyRaw) {
        if (!StringUtils.hasText(strategyRaw)) {
            return OddsValueEngineService.STRATEGY_CONSERVATIVE;
        }
        String upper = strategyRaw.trim().toUpperCase(Locale.ROOT);
        if (OddsValueEngineService.STRATEGY_AGGRESSIVE.equals(upper)) {
            return OddsValueEngineService.STRATEGY_AGGRESSIVE;
        }
        return OddsValueEngineService.STRATEGY_CONSERVATIVE;
    }

    private String safeText(String value, String fallback) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return fallback;
    }

    private String normalizeKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "na";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private String toPairKey(Long player1Id,
                             String player1Name,
                             Long player2Id,
                             String player2Name) {
        String token1 = playerToken(player1Id, player1Name);
        String token2 = playerToken(player2Id, player2Name);
        if (!StringUtils.hasText(token1) || !StringUtils.hasText(token2)) {
            return null;
        }
        String left = token1.compareTo(token2) <= 0 ? token1 : token2;
        String right = token1.compareTo(token2) <= 0 ? token2 : token1;
        return left + "|" + right;
    }

    private String toPairStartKey(Long player1Id,
                                  String player1Name,
                                  Long player2Id,
                                  String player2Name,
                                  String startTimeIso) {
        String pairKey = toPairKey(player1Id, player1Name, player2Id, player2Name);
        if (!StringUtils.hasText(pairKey)) {
            return null;
        }
        return pairKey + "|" + startBucket(startTimeIso);
    }

    private String playerToken(Long playerId, String playerName) {
        if (playerId != null) {
            return "id-" + playerId;
        }
        if (StringUtils.hasText(playerName)) {
            String normalized = normalizePersonToken(playerName);
            if (StringUtils.hasText(normalized) && !"na".equals(normalized)) {
                return "nm-" + normalized;
            }
        }
        return null;
    }

    private String normalizePersonToken(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return "na";
        }
        String lookup = NameUtils.normalizeForLookup(rawName);
        if (!StringUtils.hasText(lookup)) {
            lookup = rawName;
        }
        String ascii = java.text.Normalizer.normalize(lookup, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('ł', 'l')
                .replace('Ł', 'l');
        ascii = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!StringUtils.hasText(ascii)) {
            return normalizeKey(rawName);
        }
        String[] parts = ascii.split(" ");
        Arrays.sort(parts);
        String normalized = String.join("-", parts)
                .replaceAll("^-+|-+$", "");
        return StringUtils.hasText(normalized) ? normalized : normalizeKey(rawName);
    }

    private String startBucket(String startTimeIso) {
        Optional<LocalDateTime> parsed = parseStartDateTime(startTimeIso);
        if (parsed.isPresent()) {
            return parsed.get().withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        }
        if (!StringUtils.hasText(startTimeIso)) {
            return "na";
        }
        String raw = startTimeIso.trim();
        if (raw.length() >= 16) {
            raw = raw.substring(0, 16);
        }
        return normalizeKey(raw);
    }

    private static int clamp(int value, int lo, int hi) {
        if (value < lo) return lo;
        return Math.min(value, hi);
    }

    private static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private String resolveDecisionEventKey(LiveOddsRecommendationDto row) {
        if (row == null) {
            return null;
        }
        if (StringUtils.hasText(row.matchupKey())) {
            return row.matchupKey().trim();
        }
        return buildEventKey(row);
    }

    private String resolveDecisionDedupeKey(LiveOddsRecommendationDto row,
                                            String eventKey,
                                            BetCandidate candidate) {
        if (row == null) {
            return null;
        }
        if (StringUtils.hasText(row.suggestedDedupeKey())) {
            return row.suggestedDedupeKey().trim();
        }
        String resolvedEventKey = StringUtils.hasText(eventKey) ? eventKey.trim() : resolveDecisionEventKey(row);
        String sideName = candidate == null ? row.suggestedSide() : candidate.sideName();
        if (!StringUtils.hasText(resolvedEventKey) || !StringUtils.hasText(sideName)) {
            return resolvedEventKey;
        }
        return resolvedEventKey + "|" + normalizeKey(sideName);
    }

    private static double averageNonNull(List<PaperTradeDecisionSample> rows,
                                         java.util.function.Function<PaperTradeDecisionSample, Double> extractor) {
        if (rows == null || rows.isEmpty() || extractor == null) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (PaperTradeDecisionSample row : rows) {
            if (row == null) {
                continue;
            }
            Double value = extractor.apply(row);
            if (value == null) {
                continue;
            }
            sum += value;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private double confidenceWidth(LiveOddsRecommendationDto row) {
        if (row == null || row.confidenceLow() == null || row.confidenceHigh() == null) {
            return 0.30;
        }
        return clamp(row.confidenceHigh() - row.confidenceLow(), 0.03, 0.95);
    }

    private double candidateSignalQuality(LiveOddsRecommendationDto row, TriggerAdaptiveSignal triggerSignal) {
        double ciWidth = confidenceWidth(row);
        double ciQuality = 1.0 - clamp((ciWidth - 0.14) / 0.44, 0.0, 0.75);
        double sourceQuality = row != null && row.sourceConfidence() != null
                ? clamp(0.65 + (row.sourceConfidence() * 0.35), 0.65, 1.0)
                : 0.82;
        double triggerSampleQuality = triggerSampleQuality(triggerSignal == null ? 0 : triggerSignal.sampleSize());
        double contributionQuality = StringUtils.hasText(row == null ? null : row.topTrigger())
                ? clamp(0.72 + (Math.min(Math.abs(valueOrZero(row.topTriggerContribution())), 0.35) * 0.7), 0.72, 1.0)
                : 0.80;
        double gradeQuality = gradeQuality(row == null ? null : row.grade());

        double quality = (ciQuality * 0.34)
                + (sourceQuality * 0.16)
                + (triggerSampleQuality * 0.24)
                + (contributionQuality * 0.12)
                + (gradeQuality * 0.14);
        if (row != null && row.live()) {
            quality *= 0.98;
        }
        return clamp(quality, 0.45, 1.02);
    }

    private double triggerSampleQuality(int sampleSize) {
        if (sampleSize <= 0) {
            return 0.74;
        }
        double normalized = Math.sqrt(Math.min(sampleSize, 25)) / 5.0;
        return clamp(0.72 + (normalized * 0.28), 0.72, 1.0);
    }

    private double gradeQuality(String gradeRaw) {
        if (!StringUtils.hasText(gradeRaw)) {
            return 0.88;
        }
        return switch (gradeRaw.trim().toUpperCase(Locale.ROOT)) {
            case "A" -> 1.00;
            case "B" -> 0.94;
            case "C" -> 0.86;
            case "D" -> 0.78;
            case "F" -> 0.68;
            default -> 0.88;
        };
    }

    private double gradeScore(String gradeRaw) {
        if (!StringUtils.hasText(gradeRaw)) {
            return 0.0;
        }
        return switch (gradeRaw.trim().toUpperCase(Locale.ROOT)) {
            case "A" -> 0.9;
            case "B" -> 0.35;
            case "C" -> -0.35;
            case "D" -> -0.85;
            case "F" -> -1.25;
            default -> 0.0;
        };
    }

    private record BetCandidate(Long sidePlayerId,
                                String sideName,
                                double modelProbability,
                                double impliedProbability,
                                double edge,
                                double decimalOdds,
                                int americanOdds,
                                double modelProbabilityShiftApplied,
                                double signalQuality,
                                String triggerKey,
                                TriggerAdaptiveSignal triggerSignal) {
    }

    private record CandidateResolution(BetCandidate candidate,
                                       String rejectionReason) {
    }

    private record ExposureProfile(int openBets,
                                   double openStake,
                                   Map<Long, Double> playerStake,
                                   Map<String, Double> triggerStake) {
        static ExposureProfile fromOpenBets(List<PaperTradeBet> bets) {
            if (bets == null || bets.isEmpty()) {
                return new ExposureProfile(0, 0.0, Map.of(), Map.of());
            }
            int openCount = 0;
            double openStake = 0.0;
            Map<Long, Double> byPlayer = new HashMap<>();
            Map<String, Double> byTrigger = new HashMap<>();
            for (PaperTradeBet bet : bets) {
                if (bet == null || !PaperTradeBet.STATUS_OPEN.equalsIgnoreCase(bet.getStatus())) {
                    continue;
                }
                double stake = Math.max(0.0, bet.getStake());
                openCount++;
                openStake += stake;
                if (bet.getSidePlayerId() != null) {
                    byPlayer.merge(bet.getSidePlayerId(), stake, Double::sum);
                }
                String trigger = normalizeTriggerStatic(bet.getTopTrigger());
                if (StringUtils.hasText(trigger)) {
                    byTrigger.merge(trigger, stake, Double::sum);
                }
            }
            return new ExposureProfile(openCount, round2(openStake), byPlayer, byTrigger);
        }

        ExposureProfile addPlacement(Long sidePlayerId, String triggerKey, double stake) {
            double normalizedStake = Math.max(0.0, stake);
            Map<Long, Double> nextByPlayer = new HashMap<>(playerStake);
            Map<String, Double> nextByTrigger = new HashMap<>(triggerStake);
            if (sidePlayerId != null) {
                nextByPlayer.merge(sidePlayerId, normalizedStake, Double::sum);
            }
            String trigger = normalizeTriggerStatic(triggerKey);
            if (StringUtils.hasText(trigger)) {
                nextByTrigger.merge(trigger, normalizedStake, Double::sum);
            }
            return new ExposureProfile(openBets + 1, round2(openStake + normalizedStake), nextByPlayer, nextByTrigger);
        }

        double playerStake(Long sidePlayerId) {
            if (sidePlayerId == null || playerStake == null || playerStake.isEmpty()) {
                return 0.0;
            }
            return Math.max(0.0, playerStake.getOrDefault(sidePlayerId, 0.0));
        }

        double triggerStake(String triggerKey) {
            String normalized = normalizeTriggerStatic(triggerKey);
            if (!StringUtils.hasText(normalized) || triggerStake == null || triggerStake.isEmpty()) {
                return 0.0;
            }
            return Math.max(0.0, triggerStake.getOrDefault(normalized, 0.0));
        }
    }

    private record RankedCandidate(LiveOddsRecommendationDto row,
                                   BetCandidate candidate,
                                   String eventKey,
                                   String dedupeKey,
                                   double selectionScore,
                                   boolean fallbackPick) {
    }

    private record ScorePair(int left, int right) {
    }

    private enum ScoreOrientation {
        DIRECT,
        REVERSED,
        UNKNOWN
    }

    private record RowLookup(Map<String, LiveOddsRecommendationDto> byDedupe,
                             Map<String, LiveOddsRecommendationDto> byEvent,
                             Map<String, LiveOddsRecommendationDto> byExternalEventId,
                             Map<String, LiveOddsRecommendationDto> byPairStart,
                             Map<String, LiveOddsRecommendationDto> byPair,
                             List<LiveOddsRecommendationDto> allRows) {
    }

    private record SettlementStats(int settled, int voided) {
    }

    private record AdaptiveProfile(int sampleSize,
                                   double reliability,
                                   double edgeShift,
                                   double modelGapShift,
                                   double selectionScoreShift,
                                   double modelProbabilityShift,
                                   double stakeMultiplier,
                                   double confidenceWidthTightening,
                                   double selectionPenalty,
                                   double calibrationError,
                                   double roiSignal,
                                   double avgSettledEdge,
                                   Map<String, TriggerAdaptiveSignal> triggerSignals) {
        static AdaptiveProfile neutral() {
            return new AdaptiveProfile(0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, Map.of());
        }

        TriggerAdaptiveSignal signalFor(String triggerKey) {
            if (triggerSignals == null || triggerSignals.isEmpty()) {
                return TriggerAdaptiveSignal.neutral();
            }
            if (!StringUtils.hasText(triggerKey)) {
                return TriggerAdaptiveSignal.neutral();
            }
            return triggerSignals.getOrDefault(triggerKey.trim().toLowerCase(Locale.ROOT), TriggerAdaptiveSignal.neutral());
        }
    }

    private record TriggerAdaptiveSignal(int sampleSize,
                                         double probabilityShift,
                                         double modelGapShift,
                                         double selectionPenalty,
                                         double edgeThresholdShift) {
        static TriggerAdaptiveSignal neutral() {
            return new TriggerAdaptiveSignal(0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private record AdaptiveDecisionSample(Long betId,
                                          String topTrigger,
                                          String status,
                                          double modelProbability,
                                          double impliedProbability,
                                          double edge,
                                          double stake,
                                          double profitLoss,
                                          double confidenceWidth,
                                          LocalDateTime settledAt) {
    }

    private record TriggerAggregate(int decisions,
                                    double winsWeight,
                                    double modelProbabilitySum,
                                    double pnlSum,
                                    double stakeSum,
                                    double weightSum) {
        static TriggerAggregate empty() {
            return new TriggerAggregate(0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        TriggerAggregate add(AdaptiveDecisionSample sample, double weight) {
            double win = PaperTradeBet.STATUS_WON.equals(sample.status()) ? weight : 0.0;
            return new TriggerAggregate(
                    decisions + 1,
                    winsWeight + win,
                    modelProbabilitySum + (sample.modelProbability() * weight),
                    pnlSum + (sample.profitLoss() * weight),
                    stakeSum + (sample.stake() * weight),
                    weightSum + weight
            );
        }
    }
}
