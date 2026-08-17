package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.CompletedMatchLogDto;
import com.ttl.tabletennis.dto.LiveStudioIntegrityDto;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.LiveScoreSnapshotDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.dto.PaperTradeBetDto;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.dto.PaperTradingSyncResultDto;
import com.ttl.tabletennis.dto.TrackedMatchObservationDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.scrape.TtSeriesScraper;
import com.ttl.tabletennis.util.CorrelationContext;
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

import com.ttl.tabletennis.service.papertrade.AdaptiveDecisionSample;
import com.ttl.tabletennis.service.papertrade.AdaptiveProfile;
import com.ttl.tabletennis.service.papertrade.ExposureProfile;
import com.ttl.tabletennis.service.papertrade.RowLookup;
import com.ttl.tabletennis.service.papertrade.ScorePair;
import com.ttl.tabletennis.service.papertrade.TriggerAdaptiveSignal;
import com.ttl.tabletennis.service.papertrade.TriggerAggregate;
import com.ttl.tabletennis.service.papertrade.LearningSampleQuality;

import static com.ttl.tabletennis.service.papertrade.BetIdentityLockManager.lockBetIdentityIfEligible;
import static com.ttl.tabletennis.service.papertrade.BetIdentityLockManager.markIdentityDriftAttempt;
import static com.ttl.tabletennis.service.papertrade.BetIdentityLockManager.observationMatchesLockedIdentity;
import static com.ttl.tabletennis.service.papertrade.BetIdentityLockManager.rowMatchesLockedIdentity;
import static com.ttl.tabletennis.service.papertrade.BetLockedIdentity.effectiveExternalEventId;
import static com.ttl.tabletennis.service.papertrade.BetLockedIdentity.effectiveLockedStartTimeIso;
import static com.ttl.tabletennis.service.papertrade.BetLockedIdentity.effectiveSourceFeedEventId;
import static com.ttl.tabletennis.service.papertrade.ObservationClassifier.OBSERVATION_SOURCE_MARKET_BOARD;
import static com.ttl.tabletennis.service.papertrade.ObservationClassifier.OBSERVATION_SOURCE_SCORE_FEED;
import static com.ttl.tabletennis.service.papertrade.ObservationClassifier.hasExplicitCompletionSignal;
import static com.ttl.tabletennis.service.papertrade.ObservationClassifier.inferObservationSourceKind;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.EPS;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.isFinishedPhase;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.isLateLikePhase;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.normalizeKey;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.normalizeTrigger;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.parseStartDateTime;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.startBucket;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.safeText;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.valueOrZero;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingService.class);
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);
    /**
     * Syncs take the shared side while a reset takes the exclusive side. This
     * prevents a scheduled sync from observing the clear-history transaction
     * between its delete and replacement-session insert and creating a second
     * default ACTIVE session.
     */
    private final ReentrantReadWriteLock sessionOperationLock = new ReentrantReadWriteLock(true);
    // EPS / clamp / round2 / round4 / valueOrZero / safeText / normalizeTrigger
    // moved to PaperTradingHelpers (import-static at the top of this file)
    // as part of the §4 decomposition (2026-05-19).
    // SCORE_PAIR_PATTERN moved to ScoreNormalizer / ScorePair as needed.
    // SOURCE_EVENT_ID_PATTERN moved to MatchKeyBuilder.
    // OBSERVATION_SOURCE_* constants moved to ObservationClassifier (import-static above).
    private static final String SETTLEMENT_SOURCE_DECISIVE_LIVE_SCORE = "DECISIVE_LIVE_SCORE";
    private static final String SETTLEMENT_SOURCE_OFFICIAL_RESULT = "OFFICIAL_RESULT";
    private static final String SETTLEMENT_SOURCE_DATABASE_RESULT = "DATABASE_RESULT";
    private static final String SETTLEMENT_SOURCE_HEURISTIC_FALLBACK = "HEURISTIC_FALLBACK";
    private static final String SETTLEMENT_SOURCE_TIMEOUT_VOID = "TIMEOUT_VOID";
    private static final ZoneId OFFICIAL_RESULT_SLOT_ZONE = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter SLOT_TOKEN_FORMATTER = DateTimeFormatter.ofPattern("HHmm");

    private final OddsValueEngineService oddsValueEngineService;
    private final SettlementFacade settlementFacade;
    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradeBetRepository betRepository;
    private final OddsSnapshotRepository oddsSnapshotRepository;
    private final PaperTradeDecisionSampleRepository decisionSampleRepository;
    private final PaperTradeLearningSampleRepository learningSampleRepository;
    private final MatchRepository matchRepository;
    private final TrackedMatchObservationRepository trackedMatchObservationRepository;
    private final TtSeriesScraper ttSeriesScraper;
    private final PaperTradingShadowService paperTradingShadowService;
    private final com.ttl.tabletennis.prediction.staking.ClosingLineLookupService closingLineLookupService;
    private final com.ttl.tabletennis.service.papertrade.MatchTimelineQueryService matchTimelineQueryService;
    private final com.ttl.tabletennis.service.papertrade.CompletedMatchLogQueryService completedMatchLogQueryService;
    private final com.ttl.tabletennis.service.papertrade.ClvMetricsBuilder clvMetricsBuilder;
    private final com.ttl.tabletennis.service.papertrade.DecisionTelemetryBuilder decisionTelemetryBuilder;
    private final com.ttl.tabletennis.service.papertrade.IntegrityService integrityService;
    private final com.ttl.tabletennis.service.papertrade.SessionLifecycleService sessionLifecycleService;
    private final com.ttl.tabletennis.service.papertrade.SessionSnapshotService sessionSnapshotService;
    private final com.ttl.tabletennis.service.papertrade.SessionResetService sessionResetService;
    private final com.ttl.tabletennis.service.papertrade.ScoreWinnerResolver scoreWinnerResolver;
    private final com.ttl.tabletennis.service.papertrade.SessionLedgerReconciler sessionLedgerReconciler;
    private final com.ttl.tabletennis.prediction.staking.StakingPolicy stakingPolicy;
    private final com.ttl.tabletennis.service.papertrade.ProvisionalScoreOutcomeTracker provisionalScoreOutcomeTracker;
    private final com.ttl.tabletennis.service.papertrade.ModelCallLedgerService modelCallLedgerService;
    private final com.ttl.tabletennis.config.FeatureFlagCatalog featureFlagCatalog;

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

    @Value("${ttl.paper.fixedStake.enabled:true}")
    private boolean fixedStakeEnabled;

    @Value("${ttl.paper.fixedStake.amount:1.0}")
    private double fixedStakeAmount;

    @Value("${ttl.paper.stakingUnitPct:0.025}")
    private double stakingUnitPct;

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

    @Value("${ttl.paper.allowLive:false}")
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

    /**
     * #123 — Phase-aware score-grace minutes used by
     * {@link #shouldVoidMissingBoardBet}. Mirrors the defaults from
     * {@link com.ttl.tabletennis.settlement.SettlementPolicy.Heuristic#phaseAfterDarkMinutes}
     * but lives on the legacy void path because v3 VoidDecision is deferred
     * to legacy ({@link com.ttl.tabletennis.service.SettlementFacade}
     * L139-151). Without these overrides every actual void in production
     * waits the legacy 240-min hardcode regardless of match phase, which is
     * exactly what session 65 demonstrated (6 voids at 240+ min when they
     * could have voided at 90 min in LIVE_LATE).
     */
    @Value("${ttl.paper.voidTimeout.lateLikeScoreGraceMin:90}")
    private int voidTimeoutLateLikeScoreGraceMin;

    @Value("${ttl.paper.voidTimeout.midScoreGraceMin:150}")
    private int voidTimeoutMidScoreGraceMin;

    @Value("${ttl.paper.voidTimeout.earlyScoreGraceMin:200}")
    private int voidTimeoutEarlyScoreGraceMin;

    @Value("${ttl.paper.voidTimeout.prematchScoreGraceMin:240}")
    private int voidTimeoutPrematchScoreGraceMin;

    /**
     * #130 — Hard void cap. Ambiguous bets (no decisive last-state, no
     * official result yet) are HELD past the normal phase-aware timeout so
     * the tt-series official-results recovery has time to settle them W/L
     * (results post per-tournament-block, 1-3h after the block finishes).
     * Only after this cap from match start do we give up and void. Default
     * 6h covers even the night-tournament posting lag.
     */
    @Value("${ttl.paper.voidTimeout.hardCapMinutes:360}")
    private int voidHardCapMinutes;


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

    @Value("${ttl.paper.exploration.enabled:true}")
    private boolean explorationEnabled;

    @Value("${ttl.paper.exploration.minEdge:0.015}")
    private double explorationMinEdge;

    @Value("${ttl.paper.exploration.minModelImpliedGap:0.020}")
    private double explorationMinModelImpliedGap;

    @Value("${ttl.paper.exploration.minExpectedRoi:0.015}")
    private double explorationMinExpectedRoi;

    @Value("${ttl.paper.exploration.maxNewBetsPerSync:1}")
    private int explorationMaxNewBetsPerSync;

    @Value("${ttl.paper.accuracyGuard.minModelProbability:0.60}")
    private double accuracyGuardMinModelProbability;

    @Value("${ttl.paper.accuracyGuard.enabled:true}")
    private boolean accuracyGuardEnabled;

    @Value("${ttl.paper.accuracyGuard.maxModelMarketGap:0.10}")
    private double accuracyGuardMaxModelMarketGap;

    @Value("${ttl.paper.accuracyGuard.maxNoVigModelMarketGap:0.10}")
    private double accuracyGuardMaxNoVigModelMarketGap;

    @Value("${ttl.paper.accuracyGuard.maxPositiveNoVigModelMarketGap:0.04}")
    private double accuracyGuardMaxPositiveNoVigModelMarketGap;

    @Value("${ttl.paper.accuracyGuard.minRatingAgreement:0.65}")
    private double accuracyGuardMinRatingAgreement;

    @Value("${ttl.paper.accuracyGuard.minSignalQuality:0.62}")
    private double accuracyGuardMinSignalQuality;

    @Value("${ttl.paper.accuracyGuard.allowPositiveOdds:false}")
    private boolean accuracyGuardAllowPositiveOdds;

    @Value("${ttl.paper.adaptive.enabled:true}")
    private boolean adaptiveEnabled;

    @Value("${ttl.paper.adaptive.applyEnabled:false}")
    private boolean adaptiveApplyEnabled;

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

    /**
     * #119 — Stale-observation threshold for the time-based
     * {@code trackedAfterClose} fallback. When a bet's last observation is
     * older than this and the phase is LIVE_LATE-like, we treat the match
     * as closed so {@code StaleLiveRecoveryService} can pick it up.
     * Default 20 minutes — game-5 deuce rarely runs that long.
     */
    @Value("${ttl.paper.trackedAfterClose.staleMinutes:20}")
    private long trackedAfterCloseStaleMinutes;

    public PaperTradingService(OddsValueEngineService oddsValueEngineService,
                               SettlementFacade settlementFacade,
                               PaperTradeSessionRepository sessionRepository,
                               PaperTradeBetRepository betRepository,
                               OddsSnapshotRepository oddsSnapshotRepository,
                               PaperTradeDecisionSampleRepository decisionSampleRepository,
                               PaperTradeLearningSampleRepository learningSampleRepository,
                               MatchRepository matchRepository,
                               TrackedMatchObservationRepository trackedMatchObservationRepository,
                               TtSeriesScraper ttSeriesScraper,
                               PaperTradingShadowService paperTradingShadowService,
                               com.ttl.tabletennis.prediction.staking.ClosingLineLookupService closingLineLookupService,
                               com.ttl.tabletennis.service.papertrade.MatchTimelineQueryService matchTimelineQueryService,
                               com.ttl.tabletennis.service.papertrade.CompletedMatchLogQueryService completedMatchLogQueryService,
                               com.ttl.tabletennis.service.papertrade.ClvMetricsBuilder clvMetricsBuilder,
                               com.ttl.tabletennis.service.papertrade.DecisionTelemetryBuilder decisionTelemetryBuilder,
                               com.ttl.tabletennis.service.papertrade.IntegrityService integrityService,
                               com.ttl.tabletennis.service.papertrade.SessionLifecycleService sessionLifecycleService,
                               com.ttl.tabletennis.service.papertrade.SessionSnapshotService sessionSnapshotService,
                               com.ttl.tabletennis.service.papertrade.SessionResetService sessionResetService,
                               com.ttl.tabletennis.service.papertrade.ScoreWinnerResolver scoreWinnerResolver,
                               com.ttl.tabletennis.service.papertrade.SessionLedgerReconciler sessionLedgerReconciler,
                               com.ttl.tabletennis.prediction.staking.StakingPolicy stakingPolicy,
                               com.ttl.tabletennis.service.papertrade.ProvisionalScoreOutcomeTracker provisionalScoreOutcomeTracker,
                               com.ttl.tabletennis.service.papertrade.ModelCallLedgerService modelCallLedgerService,
                               com.ttl.tabletennis.config.FeatureFlagCatalog featureFlagCatalog) {
        this.oddsValueEngineService = oddsValueEngineService;
        this.settlementFacade = settlementFacade;
        this.sessionRepository = sessionRepository;
        this.betRepository = betRepository;
        this.oddsSnapshotRepository = oddsSnapshotRepository;
        this.decisionSampleRepository = decisionSampleRepository;
        this.learningSampleRepository = learningSampleRepository;
        this.matchRepository = matchRepository;
        this.trackedMatchObservationRepository = trackedMatchObservationRepository;
        this.ttSeriesScraper = ttSeriesScraper;
        this.paperTradingShadowService = paperTradingShadowService;
        this.closingLineLookupService = closingLineLookupService;
        this.matchTimelineQueryService = matchTimelineQueryService;
        this.completedMatchLogQueryService = completedMatchLogQueryService;
        this.clvMetricsBuilder = clvMetricsBuilder;
        this.decisionTelemetryBuilder = decisionTelemetryBuilder;
        this.integrityService = integrityService;
        this.sessionLifecycleService = sessionLifecycleService;
        this.sessionSnapshotService = sessionSnapshotService;
        this.sessionResetService = sessionResetService;
        this.scoreWinnerResolver = scoreWinnerResolver;
        this.sessionLedgerReconciler = sessionLedgerReconciler;
        this.stakingPolicy = stakingPolicy;
        this.provisionalScoreOutcomeTracker = provisionalScoreOutcomeTracker;
        this.modelCallLedgerService = modelCallLedgerService;
        this.featureFlagCatalog = featureFlagCatalog;
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

    public PaperTradingSyncResultDto syncLiveSession(String strategyRaw,
                                                     String modelVersionRaw,
                                                     Integer limit) {
        // Do not wrap the live scrape/model phase in one database transaction.
        // A sync can spend tens of seconds waiting on remote score/market data;
        // holding an H2/Hikari connection for that entire interval starves the
        // live UI and async ingestion listeners. The in-process guard serializes
        // syncs, repositories/extracted services own their short transactions,
        // and ledger reconciliation repairs cached session totals at both ends.
        Lock operation = sessionOperationLock.readLock();
        operation.lock();
        try {
            String requestedStrategy = normalizeStrategy(strategyRaw);
            String requestedModelVersion = StringUtils.hasText(modelVersionRaw)
                    ? modelVersionRaw.trim()
                    : "ENSEMBLE";
            if (!syncInProgress.compareAndSet(false, true)) {
                log.info("[paper] sync request coalesced because another sync is already in progress");
                return new PaperTradingSyncResultDto(
                        requestedStrategy,
                        requestedModelVersion,
                        0,
                        0,
                        0,
                        0,
                        0,
                        LocalDateTime.now(),
                        getSessionSnapshot(),
                        "ALREADY_RUNNING",
                        "A scheduled or manual sync is already in progress; this request was safely coalesced."
                );
            }
            try {
            try (CorrelationContext.Scope ignored = CorrelationContext.openIfAbsent(null)) {
            PaperTradeSession session = sessionLifecycleService.getOrCreateActiveSession();
            sessionLedgerReconciler.reconcile(session);
            String strategy = requestedStrategy;
            String modelVersion = requestedModelVersion;
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
        AdaptiveProfile observedAdaptiveProfile = buildAdaptiveProfile(session);
        AdaptiveProfile adaptiveProfile = adaptiveApplyEnabled
                ? observedAdaptiveProfile
                : AdaptiveProfile.neutral();

        int placed = 0;
        int skipped = 0;
        int maxPlacements = clamp(maxNewBetsPerSync, 1, 30);
        List<RankedCandidate> rankedCandidates = new ArrayList<>();
        List<RankedCandidate> fallbackCandidates = new ArrayList<>();
        double minScore = clamp(minSelectionScore + adaptiveProfile.selectionScoreShift(), -5.0, 30.0);
        ExposureProfile exposureProfile = ExposureProfile.fromOpenBets(existingOpenBets);
        List<com.ttl.tabletennis.prediction.staking.OpenPosition> policyOpenPositions =
                new ArrayList<>(toPolicyOpenPositions(existingOpenBets, session));
        List<com.ttl.tabletennis.prediction.staking.SettledStake> policySettledHistory =
                toPolicySettledHistory(session);
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
                    : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.buildEventKey(row);
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
            boolean explorationCandidate = isExplorationCandidate(row, candidate, adaptiveProfile);
            RankedCandidate ranked = new RankedCandidate(
                    row, candidate, eventKey, dedupeKey, selectionScore, explorationCandidate);
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
        int explorationPlaced = 0;

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
            if (ranked.fallbackPick()
                    && explorationPlaced >= clamp(explorationMaxNewBetsPerSync, 0, 5)) {
                persistDecisionSample(
                        session.getId(), strategy, modelVersion,
                        ranked.row(), ranked.candidate(), ranked.eventKey(), ranked.dedupeKey(),
                        ranked.selectionScore(), null, null, true,
                        "SKIPPED", "EXPLORATION_SAMPLE_LIMIT");
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
            com.ttl.tabletennis.prediction.staking.StakingDecision policyDecision = stakingDecision(
                    session,
                    ranked,
                    policyOpenPositions,
                    policySettledHistory
            );
            boolean stakePolicyPrimary = "on".equalsIgnoreCase(featureFlagCatalog.stateOf(
                    com.ttl.tabletennis.config.FeatureFlagCatalog.STAKE_POLICY_V3_FLAG
            ));
            boolean explorationEdgeOverride = stakePolicyPrimary
                    && ranked.fallbackPick()
                    && explorationEnabled
                    && !policyDecision.isBet()
                    && policyDecision.reasonCodes().stream().allMatch(reason ->
                    com.ttl.tabletennis.prediction.staking.StakingPolicy.REASON_EDGE_BELOW_THRESHOLD.equals(reason)
                            || com.ttl.tabletennis.prediction.staking.StakingPolicy.REASON_KELLY_CAP.equals(reason));
            if (stakePolicyPrimary && !policyDecision.isBet() && !explorationEdgeOverride) {
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
                        0.0,
                        ranked.fallbackPick(),
                        "SKIPPED",
                        "STAKING_POLICY_" + String.join("+", policyDecision.reasonCodes())
                );
                skipped++;
                continue;
            }
            double policyStake = ranked.fallbackPick() && explorationEnabled
                    ? Math.min(proposedStake, minStake)
                    : stakePolicyPrimary
                    ? policyDecision.stakeUnits() * stakingUnitSize(session)
                    : proposedStake;
            double stake = applyExposureCaps(
                    Math.min(proposedStake, policyStake),
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
                    : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.extractExternalEventId(row.source()));
            bet.setLiveAtPlacement(row.live());
            bet.setPlacementPhase(StringUtils.hasText(row.matchPhase())
                    ? row.matchPhase().trim()
                    : (row.live() ? "LIVE" : "PREMATCH"));
            bet.setPlayer1Id(row.player1Id());
            bet.setPlayer2Id(row.player2Id());
            bet.setSidePlayerId(candidate.sidePlayerId());
            bet.setPlayer1Name(safeText(row.player1Name(), "Player 1"));
            bet.setPlayer2Name(safeText(row.player2Name(), "Player 2"));
            bet.setSideName(safeText(candidate.sideName(), "Player"));
            String placementScore = com.ttl.tabletennis.service.papertrade.ScoreNormalizer.normalizeScoreForBet(
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
            LocalDateTime placedNow = LocalDateTime.now();
            bet.setLastObservedAt(placedNow);
            lockBetIdentityIfEligible(bet, placedNow);
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
            bet.setFeatureContributions(serializeFeatureContributions(row));
            bet.setGrade(row.grade());
            String rationale = safeText(row.rationale(), "Model value pick");
            String fallbackTag = ranked.fallbackPick() ? " | fallbackPick=true" : "";
            String exposureTag = stake + 0.009 < proposedStake
                    ? String.format(Locale.ROOT, " | exposureCapApplied=%.2f->%.2f", proposedStake, stake)
                    : "";
            String rationaleMetadata = String.format(
                    Locale.ROOT,
                    " | selectionScore=%.2f | modelShift=%+.3f%s%s%s",
                    ranked.selectionScore(),
                    candidate.modelProbabilityShiftApplied(),
                    fallbackTag,
                    exposureTag,
                    candidate.triggerSignal().sampleSize() > 0
                            ? " | triggerSamples=" + candidate.triggerSignal().sampleSize()
                            : ""
            );
            bet.setRationale(fitRationale(rationale, rationaleMetadata));
            bet.setPlacedAt(placedNow);
            saveBet(bet);
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
            policyOpenPositions.add(toPolicyOpenPosition(bet, session, stake));
            placed++;
            if (ranked.fallbackPick()) explorationPlaced++;
        }

        List<LiveOddsRecommendationDto> settlementRows = mergeSettlementRows(rows, scoreSnapshots);
        recordVisibleBoardObservations(
                session.getId(),
                settlementRows,
                existingOpenBets,
                placedEventKeys,
                LocalDateTime.now()
        );

        SettlementStats settlementStats = settlementFacade.settleOpenBets(
                session,
                settlementRows
        );
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
        postSyncProfile.applyTo(session, LocalDateTime.now());
        sessionLedgerReconciler.reconcile(session);
        saveSession(session);

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
            } finally {
                syncInProgress.set(false);
            }
        } finally {
            operation.unlock();
        }
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
        return integrityService.getLiveStudioOpenBets(
                getOrCreateActiveSession(),
                bet -> deriveTrackingState(bet, LocalDateTime.now())
        );
    }

    @Transactional
    public List<PaperTradeBetDto> getLiveStudioSettledTape(int limit) {
        return integrityService.getLiveStudioSettledTape(
                getOrCreateActiveSession(),
                limit,
                bet -> deriveTrackingState(bet, LocalDateTime.now())
        );
    }

    @Transactional
    public LiveStudioIntegrityDto getLiveStudioIntegrity() {
        return integrityService.getLiveStudioIntegrity(getOrCreateActiveSession());
    }

    /**
     * Thin delegate to {@link com.ttl.tabletennis.service.papertrade.MatchTimelineQueryService}.
     * Kept here so existing callers in controllers and tests don't change shape
     * during the §4 PaperTradingService decomposition (see
     * docs/ttlelite-series-3.0/runbooks/paper-trading-service-decomposition.md).
     */
    public List<TrackedMatchObservationDto> getMatchTimeline(String eventKey) {
        return matchTimelineQueryService.getMatchTimeline(eventKey);
    }

    @Transactional(readOnly = true)
    /** Thin delegate to {@link com.ttl.tabletennis.service.papertrade.CompletedMatchLogQueryService}
     *  — moved during the §4 PaperTradingService decomposition (2026-05-19). */
    public List<CompletedMatchLogDto> recentCompletedMatchesLog(int days, int limit) {
        return completedMatchLogQueryService.recentCompletedMatchesLog(days, limit);
    }

    @Transactional(readOnly = true)
    public ModelCallScorecardDto getModelCallScorecard(int limit) {
        return modelCallLedgerService.scorecard(limit);
    }

    @Transactional(readOnly = true)
    public com.ttl.tabletennis.dto.LiveRunAnalyticsDto getLiveRunAnalytics(int limit) {
        return modelCallLedgerService.analytics(limit);
    }

    @Transactional(readOnly = true)
    public com.ttl.tabletennis.dto.ModelCallMonitorDto getModelCallMonitor(int limit) {
        return modelCallLedgerService.monitor(limit);
    }

    @Transactional(readOnly = true)
    public com.ttl.tabletennis.dto.ModelCallTrackingDto getModelCallTracking(long callId) {
        return modelCallLedgerService.tracking(callId);
    }

    @Transactional
    public com.ttl.tabletennis.dto.ModelCallTrackingDto approveModelCall(
            long callId,
            com.ttl.tabletennis.dto.ModelCallApprovalRequest request) {
        return modelCallLedgerService.approve(callId, request);
    }

    public PaperTradingSessionDto resetSession(Double startingBankroll, String label) {
        return resetSession(startingBankroll, label, false);
    }

    public PaperTradingSessionDto resetSession(Double startingBankroll, String label, boolean clearHistory) {
        Lock operation = sessionOperationLock.writeLock();
        operation.lock();
        try {
            return sessionResetService.resetSession(
                    startingBankroll,
                    label,
                    clearHistory,
                    20,
                    40,
                    exposureCaps(),
                    this::buildAdaptiveProfile,
                    bet -> deriveTrackingState(bet, LocalDateTime.now())
            );
        } finally {
            operation.unlock();
        }
    }

    public SettlementStats settleOpenBetsLegacy(PaperTradeSession session, List<LiveOddsRecommendationDto> rows) {
        List<PaperTradeBet> openBets = betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(
                session.getId(),
                PaperTradeBet.STATUS_OPEN
        );
        RowLookup rowLookup = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.build(rows);
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
            LiveOddsRecommendationDto currentRow = findCurrentRowForBet(bet, rowLookup, now);
            String currentScore = com.ttl.tabletennis.service.papertrade.ScoreNormalizer.normalizeScoreForBet(
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
                    Optional<Long> winnerFromCurrent = scoreWinnerResolver.determineWinnerFromScore(
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
                        saveBet(bet);
                        settled++;
                        continue;
                    }
                    if (finishedPhase) {
                        Optional<Long> winnerFromCurrentLenient = scoreWinnerResolver.determineWinnerFromScore(
                                currentScore,
                                bet.getPlayer1Id(),
                                bet.getPlayer2Id(),
                                currentRow.matchPhase(),
                                true
                        );
                        if (winnerFromCurrentLenient.isPresent()) {
                            applySettlement(session, bet, winnerFromCurrentLenient.get(), null, "SETTLED_FROM_FINISHED_LIVE_SCORE_LENIENT");
                            saveBet(bet);
                            settled++;
                            continue;
                        }

                        String fallbackScore = StringUtils.hasText(scoreBeforeUpdate) ? scoreBeforeUpdate : bet.getLastObservedScore();
                        String fallbackPhase = StringUtils.hasText(currentRow.matchPhase())
                                ? currentRow.matchPhase()
                                : (StringUtils.hasText(phaseBeforeUpdate) ? phaseBeforeUpdate : bet.getLastObservedPhase());
                        Optional<Long> winnerFromLastFinished = scoreWinnerResolver.determineWinnerFromScore(
                                fallbackScore,
                                bet.getPlayer1Id(),
                                bet.getPlayer2Id(),
                                fallbackPhase,
                                true
                        );
                        if (winnerFromLastFinished.isPresent()) {
                            applySettlement(session, bet, winnerFromLastFinished.get(), null, "SETTLED_FROM_FINISHED_PHASE_LAST_SCORE");
                            saveBet(bet);
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
                        saveBet(bet);
                        settled++;
                        continue;
                    }
                    String staleScore = StringUtils.hasText(currentScore)
                            ? currentScore
                            : bet.getLastObservedScore();
                    Optional<Long> winnerFromStaleOnBoard = scoreWinnerResolver.determineWinnerFromNearFinishFallback(
                            staleScore,
                            bet.getPlayer1Id(),
                            bet.getPlayer2Id()
                    );
                    if (winnerFromStaleOnBoard.isPresent()
                            && shouldAllowStaleOnBoardFallback(bet, currentRow, currentScore, now)
                            && canSettleFromLastObservation(bet, now)) {
                        applySettlement(session, bet, winnerFromStaleOnBoard.get(), null, "SETTLED_FROM_STALE_ONBOARD_SCORE");
                        saveBet(bet);
                        settled++;
                        continue;
                    }
                }
                if (changed) {
                    saveBet(bet);
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
                        saveBet(bet);
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

            Optional<Long> winnerFromLastFastPath = scoreWinnerResolver.determineWinnerFromScore(
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
                saveBet(bet);
                settled++;
                continue;
            }

            OfficialLedgerSettlementCandidate officialLedgerCandidate = resolveOfficialLedgerSettlementCandidate(
                    bet,
                    targetDate,
                    officialResultRefreshContext
            );
            if (officialLedgerCandidate != null) {
                recordUnambiguousArchiveSettlementSelection(bet);
                applySettlement(
                        session,
                        bet,
                        officialLedgerCandidate.winnerPlayerId(),
                        officialLedgerCandidate.resultMatchId(),
                        officialLedgerCandidate.settlementReason()
                );
                saveBet(bet);
                settled++;
                continue;
            }

            if (!settlementWindowOpen) {
                if (changed) {
                    saveBet(bet);
                }
                continue;
            }

            Optional<Long> winnerFromLast = scoreWinnerResolver.determineWinnerFromScore(
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
                saveBet(bet);
                settled++;
                continue;
            }

            Optional<Long> winnerFromNearFinishScore = scoreWinnerResolver.determineWinnerFromNearFinishFallback(
                    bet.getLastObservedScore(),
                    bet.getPlayer1Id(),
                    bet.getPlayer2Id()
            );
            if (winnerFromNearFinishScore.isPresent()
                    && shouldAllowNearFinishFallback(bet, now)
                    && canSettleFromLastObservation(bet, now)) {
                applySettlement(session, bet, winnerFromNearFinishScore.get(), null, "SETTLED_FROM_NEAR_FINISH_LAST_SCORE");
                saveBet(bet);
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
                recordUnambiguousArchiveSettlementSelection(bet);
                applySettlement(session, bet, officialResultMatch, settlementReason);
                saveBet(bet);
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
                    recordUnambiguousArchiveSettlementSelection(bet);
                    applySettlement(session, bet, resolvedMatch, settlementReason);
                    saveBet(bet);
                    settled++;
                    continue;
                }
            }

            if (shouldVoidMissingBoardBet(bet, targetDate, now)) {
                // #130 — Before voiding, try to settle from a decisive last
                // live state. TT Elite matches always resolve and the result
                // is always eventually posted, so a void is almost always a
                // failure to capture a knowable W/L. If the last state was
                // decisive (e.g. 2-0 sets, or 2-1 with a commanding game
                // lead) and the match has gone stale (presumed finished),
                // call the winner instead of refunding.
                // Reaching this point means shouldVoidMissingBoardBet is true:
                // the phase-aware timeout has elapsed AND the match has been
                // absent from the board for many consecutive syncs. That is
                // itself definitive proof the match has gone dark / finished —
                // no further lastObservedAt staleness gate is needed (and
                // lastObservedAt is unreliable here: it also tracks tracked-
                // observation re-application, so it can read "fresh" even when
                // the match ended long ago).
                Optional<Long> confidentWinner = scoreWinnerResolver.determineWinnerFromConfidenceState(
                        bet.getLastObservedScore(),
                        bet.getPlayer1Id(),
                        bet.getPlayer2Id(),
                        bet.getLastObservedPhase());
                if (confidentWinner.isPresent()) {
                    applySettlement(session, bet, confidentWinner.get(), null, "SETTLED_FROM_CONFIDENCE_LAST_STATE");
                    saveBet(bet);
                    settled++;
                    continue;
                }

                // Ambiguous but with LIVE CONTEXT (we saw a real in-progress
                // score, just not a decisive/terminal one — e.g. "2-2 (9-8)"):
                // HOLD for the official-results recovery rather than voiding
                // early. The match definitely happened and tt-series will
                // post its result per tournament block (1-3h). Only void once
                // we pass the hard cap, by which point the official result
                // has had ample time to appear and genuinely isn't available.
                //
                // Bets that NEVER went live (no score observed at all —
                // no-show, wrong identity, or a match that didn't happen)
                // keep the original void timing: there's no live evidence the
                // match is real, so we don't tie up stake for 6h on them.
                boolean hadLiveContext = StringUtils.hasText(bet.getLastObservedScore());
                if (hadLiveContext && !passedHardVoidCap(bet, now)) {
                    if (changed) {
                        saveBet(bet);
                    }
                    continue; // hold open; official-results recovery + later sweeps retry
                }
                applySettlement(session, bet, null, null, "VOIDED_MISSING_BOARD_TIMEOUT");
                saveBet(bet);
                settled++;
                voided++;
                continue;
            }

            if (changed) {
                saveBet(bet);
            }
        }
        return new SettlementStats(settled, voided);
    }

    /**
     * Refresh the score timeline for existing open bets without making any
     * settlement decision. Score Truth primary calls this before it builds an
     * evidence bundle, so a targeted completion row can be persisted and acted
     * on in the same sync instead of waiting for stale-live recovery.
     */
    @Transactional
    public int refreshOpenBetScoreEvidence(PaperTradeSession session,
                                           List<LiveOddsRecommendationDto> rows) {
        if (session == null || session.getId() == null || rows == null || rows.isEmpty()) {
            return 0;
        }
        List<PaperTradeBet> openBets = betRepository.findBySessionIdAndStatusOrderByPlacedAtAsc(
                session.getId(),
                PaperTradeBet.STATUS_OPEN
        );
        if (openBets == null || openBets.isEmpty()) {
            return 0;
        }
        RowLookup rowLookup = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.build(rows);
        LocalDateTime observedAt = LocalDateTime.now();
        int refreshed = 0;
        for (PaperTradeBet bet : openBets) {
            if (bet == null || bet.getId() == null || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
                continue;
            }
            LiveOddsRecommendationDto row = findCurrentRowForBet(bet, rowLookup, observedAt);
            if (row == null) {
                continue;
            }
            String normalizedScore = com.ttl.tabletennis.service.papertrade.ScoreNormalizer.normalizeScoreForBet(
                    bet,
                    row.liveScore(),
                    row.player1Id(),
                    row.player1Name(),
                    row.player2Id(),
                    row.player2Name()
            );
            boolean changed = updateLastObservedFromRow(bet, row, normalizedScore, observedAt);
            recordObservation(session.getId(), bet, row, normalizedScore, observedAt);
            if (changed) {
                saveBet(bet);
            }
            refreshed++;
        }
        return refreshed;
    }

    /**
     * #130 — True once a bet has been open past the hard void cap measured
     * from match start (falling back to placement time). Past this, we give
     * up waiting for an official result and void.
     */
    private boolean passedHardVoidCap(PaperTradeBet bet, LocalDateTime now) {
        if (bet == null || now == null) {
            return true;
        }
        int capMinutes = Math.max(120, voidHardCapMinutes);
        Optional<LocalDateTime> startOpt = parseStartDateTime(bet.getStartTimeIso());
        LocalDateTime anchor = startOpt.orElse(bet.getPlacedAt() == null ? now : bet.getPlacedAt());
        return !now.isBefore(anchor.plusMinutes(capMinutes));
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

    /**
     * Archive resolvers only return after their identity, slot, duplicate, and
     * conflicting-winner guards have accepted one result. Persist that decision
     * so downstream learning can distinguish a verified archive label from a
     * legacy row that has no ambiguity evidence at all.
     */
    private void recordUnambiguousArchiveSettlementSelection(PaperTradeBet bet) {
        if (bet != null) {
            bet.setSettlementAmbiguityScore(0.0);
        }
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
        bet.setSettlementObservedAt(now);
        LearningSampleQuality.Assessment settlementQuality = LearningSampleQuality.assess(bet);
        bet.setSettlementConfidence(settlementQuality.confidence());
        captureClosingLineOnBet(bet);
        session.setTotalReturned(round2(session.getTotalReturned() + returned));
        session.setRealizedPnl(round2(session.getRealizedPnl() + pnl));
        session.setCurrentBankroll(round2(session.getCurrentBankroll() + returned));
        session.setPeakBankroll(Math.max(session.getPeakBankroll(), session.getCurrentBankroll()));
        provisionalScoreOutcomeTracker.resolve(bet);
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

    // isTargetedCompletionSettlement moved to IntegrityService — it was used
    // only by the integrity DTO path.

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
                : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.toPairStartKey(
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
                    : (StringUtils.hasText(effectiveExternalEventId(bet))
                    ? effectiveExternalEventId(bet)
                    : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.extractExternalEventId(bet.getSource()));
            if (StringUtils.hasText(eventId)) {
                eventIds.add(eventId);
            }
        }
        return eventIds;
    }

    // extractExternalEventId moved to com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.

    // effectiveExternalEventId / effectiveSourceFeedEventId / effectiveLockedStartTimeIso
    // moved to com.ttl.tabletennis.service.papertrade.BetLockedIdentity.

    // lockBetIdentityIfEligible / rowMatchesLockedIdentity / observationMatchesLockedIdentity
    // + 3 markIdentityDriftAttempt overloads moved to
    // com.ttl.tabletennis.service.papertrade.BetIdentityLockManager (import-static below).

    // inferObservationSourceKind moved to ObservationClassifier (import-static above).

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
        return isTrackedAfterCloseObservation(row, null, null);
    }

    /**
     * #119 — Decide whether an incoming row indicates the match has closed.
     *
     * <p>Original criteria (still in force): a SCORE_FEED observation with
     * any of {@code !displayed}, {@code resulted}, or {@code matchCompleted}.
     *
     * <p>New time-based fallback: when the score feed never emits a terminal
     * observation (a real BETRADAR_UF quirk — Hard Rock's inner
     * {@code matchState} block sometimes goes silent at game-5 deuce instead
     * of pushing a final-state row), we also treat the match as closed when:
     * <ul>
     *   <li>the bet was last observed in a LIVE_LATE-like phase, AND</li>
     *   <li>the bet's {@code lastObservedAt} is older than the
     *       {@code ttl.paper.trackedAfterClose.staleMinutes} threshold
     *       (default 20 min).</li>
     * </ul>
     * Without this fallback, {@code StaleLiveRecoveryService} never wakes
     * (it requires {@code trackedAfterClose=true}), and the bet sits OPEN
     * until the void timeout fires — what production saw in Session 65.
     */
    private boolean isTrackedAfterCloseObservation(LiveOddsRecommendationDto row,
                                                    PaperTradeBet bet,
                                                    LocalDateTime now) {
        if (row != null && OBSERVATION_SOURCE_SCORE_FEED.equals(inferObservationSourceKind(row))
                && (!row.displayed() || row.resulted() || row.matchCompleted())) {
            return true;
        }
        if (bet == null || now == null) {
            return false;
        }
        if (!isLateLikePhase(bet.getLastObservedPhase())) {
            return false;
        }
        LocalDateTime lastObservedAt = bet.getLastObservedAt();
        if (lastObservedAt == null) {
            return false;
        }
        long minutesStale = java.time.Duration.between(lastObservedAt, now).toMinutes();
        return minutesStale >= trackedAfterCloseStaleMinutes;
    }

    private void recordObservation(Long sessionId,
                                   PaperTradeBet bet,
                                   LiveOddsRecommendationDto row,
                                   String normalizedScore,
                                   LocalDateTime observedAt) {
        if (sessionId == null || bet == null || row == null || bet.getId() == null) {
            return;
        }
        if (!rowMatchesLockedIdentity(bet, row)) {
            markIdentityDriftAttempt(bet, row, observedAt, "CONFLICTING_OBSERVATION_RECORD");
            return;
        }
        String eventKey = StringUtils.hasText(bet.getEventKey())
                ? bet.getEventKey().trim()
                : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.buildEventKey(row);
        if (!StringUtils.hasText(eventKey)) {
            return;
        }
        String score = StringUtils.hasText(normalizedScore) ? normalizedScore.trim() : null;
        String phase = StringUtils.hasText(row.matchPhase()) ? row.matchPhase().trim() : null;
        String sourceKind = inferObservationSourceKind(row);
        Optional<TrackedMatchObservation> previous = trackedMatchObservationRepository.findTopByBetIdOrderByObservedAtDescIdDesc(bet.getId());
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
        observation.setExternalEventId(StringUtils.hasText(effectiveExternalEventId(bet))
                ? effectiveExternalEventId(bet)
                : (StringUtils.hasText(row.externalEventId())
                ? row.externalEventId().trim()
                : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.extractExternalEventId(row.source())));
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
        provisionalScoreOutcomeTracker.annotate(observation);
        trackedMatchObservationRepository.save(observation);
    }

    /**
     * Persist visible board transitions even when no paper bet is attached.
     * The bettor-facing "Live timeline" is a match timeline, not merely a
     * wager audit, so live score/phase continuity must remain inspectable for
     * ordinary watched matches as well. Open bets are excluded here because
     * their richer identity-locked path records the same transition below.
     */
    private void recordVisibleBoardObservations(Long sessionId,
                                                List<LiveOddsRecommendationDto> rows,
                                                List<PaperTradeBet> existingOpenBets,
                                                Set<String> placedEventKeys,
                                                LocalDateTime observedAt) {
        if (sessionId == null || rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> wagerEventKeys = existingOpenBets == null
                ? new HashSet<>()
                : existingOpenBets.stream()
                .map(PaperTradeBet::getEventKey)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        if (placedEventKeys != null) {
            wagerEventKeys.addAll(placedEventKeys);
        }
        for (LiveOddsRecommendationDto row : rows) {
            if (row == null) {
                continue;
            }
            String eventKey = resolveDecisionEventKey(row);
            if (!StringUtils.hasText(eventKey) || wagerEventKeys.contains(eventKey)) {
                continue;
            }
            recordVisibleBoardObservation(sessionId, eventKey, row, observedAt);
        }
    }

    private void recordVisibleBoardObservation(Long sessionId,
                                               String eventKey,
                                               LiveOddsRecommendationDto row,
                                               LocalDateTime observedAt) {
        String score = StringUtils.hasText(row.liveScore()) ? row.liveScore().trim() : null;
        String phase = StringUtils.hasText(row.matchPhase()) ? row.matchPhase().trim() : null;
        String sourceKind = inferObservationSourceKind(row);
        Optional<TrackedMatchObservation> previous = trackedMatchObservationRepository
                .findTopByEventKeyOrderByObservedAtDescIdDesc(eventKey);
        if (previous.isPresent()) {
            TrackedMatchObservation last = previous.get();
            boolean unchanged = safeText(score, "").equals(safeText(last.getLiveScore(), ""))
                    && safeText(phase, "").equals(safeText(last.getMatchPhase(), ""))
                    && sourceKind.equals(safeText(last.getSourceKind(), ""))
                    && row.live() == last.isLive()
                    && row.displayed() == last.isDisplayed()
                    && row.resulted() == last.isResulted()
                    && row.matchCompleted() == last.isMatchCompleted()
                    && safeText(row.scoreDetail(), "").equals(safeText(last.getScoreDetail(), ""));
            if (unchanged) {
                return;
            }
        }

        TrackedMatchObservation observation = new TrackedMatchObservation();
        observation.setSessionId(sessionId);
        observation.setEventKey(eventKey);
        observation.setDedupeKey(row.suggestedDedupeKey());
        observation.setExternalEventId(StringUtils.hasText(row.externalEventId())
                ? row.externalEventId().trim()
                : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.extractExternalEventId(row.source()));
        observation.setSource(safeText(row.source(), "UNKNOWN"));
        observation.setSourceKind(sourceKind);
        observation.setSourceConfidence(observationSourceConfidence(row));
        observation.setDisplayed(row.displayed());
        observation.setResulted(row.resulted());
        observation.setMatchCompleted(row.matchCompleted());
        observation.setSourceFeedCode(StringUtils.hasText(row.sourceFeedCode()) ? row.sourceFeedCode().trim() : null);
        observation.setSourceFeedEventId(StringUtils.hasText(row.sourceFeedEventId()) ? row.sourceFeedEventId().trim() : null);
        observation.setLive(row.live());
        observation.setTrackedAfterClose(false);
        observation.setEventName(row.eventName());
        observation.setCompetitionName(row.competitionName());
        observation.setStartTimeIso(row.startTimeIso());
        observation.setPlayer1Id(row.player1Id());
        observation.setPlayer1Name(row.player1Name());
        observation.setPlayer2Id(row.player2Id());
        observation.setPlayer2Name(row.player2Name());
        observation.setLiveScore(score);
        observation.setMatchPhase(phase);
        observation.setScoreDetail(StringUtils.hasText(row.scoreDetail()) ? row.scoreDetail().trim() : null);
        observation.setObservedAt(observedAt == null ? LocalDateTime.now() : observedAt);
        provisionalScoreOutcomeTracker.annotate(observation);
        trackedMatchObservationRepository.save(observation);
    }

    // latestTrackedObservationForBet was removed as part of the §4 cleanup pass
    // (2026-05-19) — confirmed unused; preferredTrackedObservationForBet is
    // the actual call path for tracked observations.

    private Optional<TrackedMatchObservation> preferredTrackedObservationForBet(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null) {
            return Optional.empty();
        }
        List<TrackedMatchObservation> observations = trackedMatchObservationRepository.findByBetIdOrderByObservedAtAsc(bet.getId());
        if (observations == null || observations.isEmpty()) {
            return Optional.empty();
        }
        boolean trackedAfterCloseOnly = bet.isTrackedAfterClose();
        TrackedMatchObservation newestRelevant = null;
        for (int i = observations.size() - 1; i >= 0; i--) {
            TrackedMatchObservation observation = observations.get(i);
            if (trackedAfterCloseOnly && !observation.isTrackedAfterClose()) {
                continue;
            }
            if (newestRelevant == null) {
                newestRelevant = observation;
            }
            if (observationMatchesLockedIdentity(bet, observation)) {
                return Optional.of(observation);
            }
        }
        if (newestRelevant != null) {
            markIdentityDriftAttempt(bet, newestRelevant, "CONFLICTING_TRACKED_OBSERVATION");
        }
        return Optional.empty();
    }

    private boolean applyLatestTrackedObservation(PaperTradeBet bet, TrackedMatchObservation observation) {
        if (bet == null || observation == null) {
            return false;
        }
        if (!observationMatchesLockedIdentity(bet, observation)) {
            markIdentityDriftAttempt(bet, observation, "CONFLICTING_TRACKED_OBSERVATION_APPLY");
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

        changed = lockBetIdentityIfEligible(bet, observation.getObservedAt()) || changed;
        return changed;
    }

    // matchesSettlementSource moved to IntegrityService — it was used only by
    // getLiveStudioIntegrity.

    // buildRowLookup / putPreferredRow / preferSettlementRow / settlementRowRank
    // moved to com.ttl.tabletennis.service.papertrade.RowLookupBuilder.
    // RowLookup record lifted to com.ttl.tabletennis.service.papertrade.RowLookup.

    private LiveOddsRecommendationDto findCurrentRowForBet(PaperTradeBet bet, RowLookup lookup, LocalDateTime observedAt) {
        if (bet == null || lookup == null) {
            return null;
        }
        LiveOddsRecommendationDto best = null;
        String lockedSourceFeedEventId = effectiveSourceFeedEventId(bet);
        if (StringUtils.hasText(lockedSourceFeedEventId)) {
            LiveOddsRecommendationDto bySourceFeedEventId = lookup.bySourceFeedEventId().get(lockedSourceFeedEventId);
            if (bySourceFeedEventId != null) {
                return bySourceFeedEventId;
            }
        }
        String betExternalEventId = StringUtils.hasText(effectiveExternalEventId(bet))
                ? effectiveExternalEventId(bet).trim()
                : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.extractExternalEventId(bet.getSource());
        if (StringUtils.hasText(betExternalEventId)) {
            LiveOddsRecommendationDto byExternalEventId = lookup.byExternalEventId().get(betExternalEventId);
            if (byExternalEventId != null) {
                return byExternalEventId;
            }
        }
        if (StringUtils.hasText(bet.getDedupeKey())) {
            LiveOddsRecommendationDto byDedupe = lookup.byDedupe().get(bet.getDedupeKey().trim());
            if (byDedupe != null) {
                best = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(byDedupe, best) ? byDedupe : best;
            }
            int sideSep = bet.getDedupeKey().lastIndexOf('|');
            if (sideSep > 0) {
                String dedupeEventKey = bet.getDedupeKey().substring(0, sideSep).trim();
                if (StringUtils.hasText(dedupeEventKey)) {
                    LiveOddsRecommendationDto byDedupeEvent = lookup.byEvent().get(dedupeEventKey);
                    if (byDedupeEvent != null) {
                        best = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(byDedupeEvent, best) ? byDedupeEvent : best;
                    }
                }
            }
        }
        if (StringUtils.hasText(bet.getEventKey())) {
            LiveOddsRecommendationDto byEvent = lookup.byEvent().get(bet.getEventKey().trim());
            if (byEvent != null) {
                best = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(byEvent, best) ? byEvent : best;
            }
        }
        String pairStartKey = com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.toPairStartKey(
                bet.getPlayer1Id(),
                bet.getPlayer1Name(),
                bet.getPlayer2Id(),
                bet.getPlayer2Name(),
                bet.getStartTimeIso()
        );
        if (StringUtils.hasText(pairStartKey)) {
            LiveOddsRecommendationDto byPairStart = lookup.byPairStart().get(pairStartKey);
            if (byPairStart != null) {
                best = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(byPairStart, best) ? byPairStart : best;
            }
        }
        String namePairStartKey = com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.toPairStartKey(
                null,
                bet.getPlayer1Name(),
                null,
                bet.getPlayer2Name(),
                bet.getStartTimeIso()
        );
        if (StringUtils.hasText(namePairStartKey)) {
            LiveOddsRecommendationDto byNamePairStart = lookup.byPairStart().get(namePairStartKey);
            if (byNamePairStart != null) {
                best = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(byNamePairStart, best) ? byNamePairStart : best;
            }
        }
        String pairKey = com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.toPairKey(
                bet.getPlayer1Id(),
                bet.getPlayer1Name(),
                bet.getPlayer2Id(),
                bet.getPlayer2Name()
        );
        if (StringUtils.hasText(pairKey)) {
            LiveOddsRecommendationDto byPair = lookup.byPair().get(pairKey);
            if (byPair != null && com.ttl.tabletennis.service.papertrade.BetIdentityMatcher.isCompatibleStartTime(bet.getStartTimeIso(), byPair.startTimeIso())) {
                best = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(byPair, best) ? byPair : best;
            }
        }
        String namePairKey = com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.toPairKey(
                null,
                bet.getPlayer1Name(),
                null,
                bet.getPlayer2Name()
        );
        if (StringUtils.hasText(namePairKey)) {
            LiveOddsRecommendationDto byNamePair = lookup.byPair().get(namePairKey);
            if (byNamePair != null && com.ttl.tabletennis.service.papertrade.BetIdentityMatcher.isCompatibleStartTime(bet.getStartTimeIso(), byNamePair.startTimeIso())) {
                best = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(byNamePair, best) ? byNamePair : best;
            }
        }
        LiveOddsRecommendationDto loose = findLooseRowForBet(bet, lookup);
        if (loose != null) {
            best = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(loose, best) ? loose : best;
        }
        if (bet.isIdentityLocked()) {
            if (best == null) {
                return null;
            }
            if (rowMatchesLockedIdentity(bet, best)) {
                return best;
            }
            markIdentityDriftAttempt(bet, best, observedAt, "CONFLICTING_ROW_MATCH");
            return null;
        }
        return best;
    }

    private LiveOddsRecommendationDto findLooseRowForBet(PaperTradeBet bet, RowLookup lookup) {
        if (bet == null || lookup == null) {
            return null;
        }
        String betA = com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.normalizePersonToken(bet.getPlayer1Name());
        String betB = com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.normalizePersonToken(bet.getPlayer2Name());
        if (!StringUtils.hasText(betA) || !StringUtils.hasText(betB) || "na".equals(betA) || "na".equals(betB)) {
            return null;
        }

        LiveOddsRecommendationDto fallback = null;
        LiveOddsRecommendationDto compatible = null;
        for (LiveOddsRecommendationDto row : lookup.allRows()) {
            if (row == null) {
                continue;
            }
            String rowA = com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.normalizePersonToken(row.player1Name());
            String rowB = com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.normalizePersonToken(row.player2Name());
            boolean strictPair = com.ttl.tabletennis.service.papertrade.BetIdentityMatcher.isSamePair(betA, betB, rowA, rowB);
            boolean loosePair = strictPair || com.ttl.tabletennis.service.papertrade.BetIdentityMatcher.isLoosePairNameMatch(bet, row);
            if (!loosePair) {
                continue;
            }
            if (com.ttl.tabletennis.service.papertrade.BetIdentityMatcher.isCompatibleStartTime(bet.getStartTimeIso(), row.startTimeIso())) {
                compatible = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(row, compatible) ? row : compatible;
                continue;
            }
            fallback = com.ttl.tabletennis.service.papertrade.RowLookupBuilder.preferSettlementRow(row, fallback) ? row : fallback;
        }
        return compatible != null ? compatible : fallback;
    }

    // isLoosePairNameMatch, isSameParticipantLoose, isSamePair, isCompatibleStartTime
    // moved to com.ttl.tabletennis.service.papertrade.BetIdentityMatcher.

    private boolean updateLastObservedFromRow(PaperTradeBet bet,
                                              LiveOddsRecommendationDto row,
                                              String normalizedScore,
                                              LocalDateTime observedAt) {
        if (bet == null || row == null) {
            return false;
        }
        if (!rowMatchesLockedIdentity(bet, row)) {
            markIdentityDriftAttempt(bet, row, observedAt, "CONFLICTING_ROW_UPDATE");
            return false;
        }
        boolean changed = false;
        String inferredSource = inferObservationSourceKind(row);
        double sourceConfidence = observationSourceConfidence(row);
        if (StringUtils.hasText(row.startTimeIso())) {
            String startIso = row.startTimeIso().trim();
            if (com.ttl.tabletennis.service.papertrade.BetIdentityMatcher.shouldReplaceStartTimeIso(bet.getStartTimeIso(), startIso)) {
                bet.setStartTimeIso(startIso);
                changed = true;
            }
        }
        String externalEventId = StringUtils.hasText(row.externalEventId())
                ? row.externalEventId().trim()
                : com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.extractExternalEventId(row.source());
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

        // #119 — pass bet + observedAt so the time-based stale-LIVE_LATE
        // fallback can fire when the score-feed never emits a terminal flag.
        boolean trackedAfterClose = isTrackedAfterCloseObservation(row, bet, observedAt);
        if (bet.isTrackedAfterClose() != trackedAfterClose) {
            bet.setTrackedAfterClose(trackedAfterClose);
            changed = true;
        }

        if (observedAt != null && (changed || bet.getLastObservedAt() == null)) {
            bet.setLastObservedAt(observedAt);
            changed = true;
        }
        changed = lockBetIdentityIfEligible(bet, observedAt) || changed;
        return changed;
    }

    // shouldReplaceStartTimeIso moved to BetIdentityMatcher.

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

    // determineWinnerFromScore + determineWinnerFromNearFinishFallback + 7
    // internal helpers (findPrimarySetScorePairIndex, findPointScorePair,
    // winnerFromSetScorePair, winnerFromPointScorePair,
    // winnerFromTiedFinalSetPoints, winnerFromFinishedPhaseTiedFinalSetPoints,
    // winnerFromCompletedGamePoints) moved to
    // com.ttl.tabletennis.service.papertrade.ScoreWinnerResolver.
    // parseScorePairs + ScorePair record moved to
    // com.ttl.tabletennis.service.papertrade.ScorePair (with a parseAll(rawScore) factory).

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

    /**
     * #123 — Returns the score-grace window (minutes) for a given match
     * phase. The score-grace is added to {@code lastScoreBackfillMinutes}
     * to compute the void timeout for a bet that has a visible last-score
     * but no further board updates. Phase strings come from the score
     * feed (LIVE_EARLY / LIVE_MID / LIVE_LATE / PREMATCH / FINISHED /
     * UPCOMING). Unknown phases fall back to the late-like default to err
     * on the side of faster void recovery.
     */
    private int phaseAwareScoreGraceMinutes(String phase) {
        if (phase == null) {
            return clamp(voidTimeoutLateLikeScoreGraceMin, 15, 720);
        }
        String upper = phase.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "LIVE_LATE", "LIVE_FINAL", "FINISHED" -> clamp(voidTimeoutLateLikeScoreGraceMin, 15, 720);
            case "LIVE_MID" -> clamp(voidTimeoutMidScoreGraceMin, 15, 720);
            case "LIVE_EARLY" -> clamp(voidTimeoutEarlyScoreGraceMin, 15, 720);
            case "PREMATCH", "UPCOMING" -> clamp(voidTimeoutPrematchScoreGraceMin, 15, 720);
            // Default: assume late-like (closer to over than to starting).
            // This is conservative against capital lock-up.
            default -> clamp(voidTimeoutLateLikeScoreGraceMin, 15, 720);
        };
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
            // #123 — Phase-aware score-grace replaces the previous flat
            // 240/120 hardcode. The previous logic kept LIVE_LATE bets stuck
            // for 240+ min even when the match was almost certainly over (a
            // game-5 deuce rarely lasts 90 min, let alone 240). Defaults
            // mirror v3 SettlementPolicy.defaults() phaseAfterDarkMinutes.
            int scoreGrace = phaseAwareScoreGraceMinutes(bet.getLastObservedPhase());
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
        Optional<Long> fromCurrent = scoreWinnerResolver.determineWinnerFromScore(
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
        return scoreWinnerResolver.determineWinnerFromScore(
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

    // hasExplicitCompletionSignal moved to ObservationClassifier (import-static above).

    private boolean textChanged(String before, String after) {
        String left = StringUtils.hasText(before) ? before.trim() : "";
        String right = StringUtils.hasText(after) ? after.trim() : "";
        return !left.equals(right);
    }

    // normalizeScoreForBet / resolveScoreOrientation / reverseScorePairs +
    // ScoreOrientation enum + SCORE_PAIR_PATTERN moved to
    // com.ttl.tabletennis.service.papertrade.ScoreNormalizer.

    // isLateLikePhase moved to PaperTradingHelpers (import-static above).

    private PaperTradingSessionDto buildSessionDto(PaperTradeSession session, int openLimit, int recentLimit) {
        return sessionSnapshotService.buildSessionDto(
                session,
                openLimit,
                recentLimit,
                exposureCaps(),
                bet -> deriveTrackingState(bet, LocalDateTime.now())
        );
    }

    private PaperTradingSessionDto.DecisionTelemetryDto buildDecisionTelemetry(Long sessionId) {
        return decisionTelemetryBuilder.buildDecisionTelemetry(sessionId);
    }

    private PaperTradingSessionDto.ExposureMetricsDto buildExposureMetrics(PaperTradeSession session,
                                                                           List<PaperTradeBet> openRows) {
        return com.ttl.tabletennis.service.papertrade.ExposureMetricsBuilder.buildExposureMetrics(session, openRows, exposureCaps());
    }

    /** Bundle the four {@code @Value}-injected exposure caps into the record
     *  shared by {@link com.ttl.tabletennis.service.papertrade.ExposureMetricsBuilder}
     *  and {@link com.ttl.tabletennis.service.papertrade.SessionSnapshotService}. */
    private com.ttl.tabletennis.service.papertrade.ExposureMetricsBuilder.ExposureCaps exposureCaps() {
        return new com.ttl.tabletennis.service.papertrade.ExposureMetricsBuilder.ExposureCaps(
                maxConcurrentOpenBets,
                maxOpenExposurePct,
                maxExposurePerPlayerPct,
                maxExposurePerTriggerPct);
    }

    private PaperTradingSessionDto.ClvMetricsDto buildClvMetrics(List<PaperTradeBet> recentRows) {
        return clvMetricsBuilder.buildClvMetrics(recentRows);
    }
    // snapshotSide / firstNonBlank / normalizeComparableName moved to
    // com.ttl.tabletennis.service.papertrade.ClvMetricsBuilder as part of the §4
    // decomposition (paper-trading-service-decomposition.md). All three were
    // only used by buildClvMetrics so they moved with their owner.

    private AdaptiveProfile buildAdaptiveProfile(PaperTradeSession session) {
        if (!adaptiveEnabled || session == null || session.getId() == null) {
            return AdaptiveProfile.neutral();
        }
        int historyTake = clamp(adaptiveHistoryWindow, 20, 500);
        List<AdaptiveDecisionSample> recentDecisions = loadAdaptiveDecisionSamples(historyTake);
        return com.ttl.tabletennis.service.papertrade.AdaptiveProfileBuilder.buildAdaptiveProfile(
                recentDecisions,
                adaptiveConfig(),
                LocalDateTime.now()
        );
    }

    /** Snapshot of the {@code @Value}-injected adaptive properties — bundled at the
     *  delegate call site so the pure-function {@link com.ttl.tabletennis.service.papertrade.AdaptiveProfileBuilder}
     *  doesn't need to know about Spring config. */
    private com.ttl.tabletennis.service.papertrade.AdaptiveProfileBuilder.AdaptiveConfig adaptiveConfig() {
        return new com.ttl.tabletennis.service.papertrade.AdaptiveProfileBuilder.AdaptiveConfig(
                adaptiveMinSettledDecisions,
                adaptiveTriggerMinDecisions,
                adaptiveLearningHalfLifeDays,
                adaptiveMaxEdgeShift,
                adaptiveMaxSelectionScoreShift,
                adaptiveMaxStakeMultiplierDelta,
                minEdgeForBet
        );
    }

    /** Thin delegate to {@link com.ttl.tabletennis.service.papertrade.TriggerInsightsBuilder}
     *  — moved during the §4 PaperTradingService decomposition (2026-05-19).
     *  Kept here so the buildSessionDto call site doesn't have to know about
     *  the helper class. */
    private List<PaperTradingSessionDto.TriggerInsightDto> buildTopTriggers(List<PaperTradeBet> settledRows) {
        return com.ttl.tabletennis.service.papertrade.TriggerInsightsBuilder.buildTopTriggers(settledRows);
    }

    // buildTopTriggersFromLearning was removed as part of the §4 cleanup pass
    // (2026-05-19) — 78 LOC of trigger aggregation over learning samples that
    // had no call sites. The live trigger path is buildTopTriggers, which
    // computes the same shape from settled PaperTradeBet rows.

    private List<PaperTradingSessionDto.EquityPointDto> buildEquityCurve(PaperTradeSession session,
                                                                          List<PaperTradeBet> settledRows) {
        return com.ttl.tabletennis.service.papertrade.EquityCurveBuilder.buildEquityCurve(session, settledRows);
    }

    private PaperTradeBetDto toDto(PaperTradeBet bet) {
        return com.ttl.tabletennis.service.papertrade.BetDtoMapper.toDto(
                bet,
                deriveTrackingState(bet, LocalDateTime.now())
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

    // toObservationDto moved to MatchTimelineQueryService as part of the
    // §4 PaperTradingService decomposition (2026-05-19).

    private Match selectBestSettlementCandidate(Long sessionId,
                                                PaperTradeBet bet,
                                                LocalDate targetDate,
                                                List<Match> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        LocalDate placedDate = bet.getPlacedAt() == null ? targetDate : bet.getPlacedAt().toLocalDate();
        long lagLimit = clamp(maxSettlementLagDays, 3, 120);
        LocalDate today = LocalDate.now();

        List<SettlementCandidateRank> ranked = new ArrayList<>();
        for (Match candidate : candidates) {
            if (candidate.getId() == null || candidate.getDate() == null || candidate.getWinnerPlayerId() == null) {
                continue;
            }
            if (candidate.getDate().isBefore(placedDate)) {
                continue;
            }
            if (candidate.getDate().isAfter(today)) {
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
            ranked.add(new SettlementCandidateRank(candidate, score));
        }

        if (ranked.isEmpty()) {
            return null;
        }

        ranked.sort(Comparator
                .comparingLong(SettlementCandidateRank::score)
                .thenComparing((SettlementCandidateRank rank) -> rank.match().getDate(), Comparator.reverseOrder())
                .thenComparing((SettlementCandidateRank rank) -> rank.match().getId(), Comparator.reverseOrder()));

        SettlementCandidateRank best = ranked.get(0);
        if (isAmbiguousSettlementCandidateSelection(bet, targetDate, ranked, best)) {
            log.warn(
                    "[paper] ambiguous settlement candidate skipped: betId={} event='{}' targetDate={} lastScore='{}' bestMatchId={} candidateCount={}",
                    bet == null ? null : bet.getId(),
                    bet == null ? null : bet.getEventName(),
                    targetDate,
                    bet == null ? null : bet.getLastObservedScore(),
                    best.match().getId(),
                    ranked.size()
            );
            return null;
        }

        return best.match();
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
        SlotAnchoredSettlementResolution slotAnchored = resolveSlotAnchoredSettlementCandidate(bet, targetDate);
        if (slotAnchored.blockFallback()) {
            return slotAnchored.match();
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

    private OfficialLedgerSettlementCandidate resolveOfficialLedgerSettlementCandidate(PaperTradeBet bet,
                                                                                       LocalDate targetDate,
                                                                                       OfficialResultRefreshContext refreshContext) {
        if (!officialResultConfirmationEnabled
                || bet == null
                || targetDate == null
                || refreshContext == null
                || !StringUtils.hasText(bet.getPlayer1Name())
                || !StringUtils.hasText(bet.getPlayer2Name())
                || !isWithinOfficialResultWindow(targetDate, LocalDateTime.now())) {
            return null;
        }

        List<TtSeriesScraper.OfficialLedgerMatch> ledgerMatches = loadOfficialLedgerMatchesForBet(bet, targetDate, refreshContext);
        if (ledgerMatches.isEmpty()) {
            return null;
        }

        List<OfficialLedgerSettlementCandidate> candidates = new ArrayList<>();
        for (TtSeriesScraper.OfficialLedgerMatch ledgerMatch : ledgerMatches) {
            if (ledgerMatch == null || !Objects.equals(ledgerMatch.date(), targetDate)) {
                continue;
            }
            Optional<Long> winnerPlayerId = winnerPlayerIdFromOfficialLedgerMatch(bet, ledgerMatch);
            if (winnerPlayerId.isEmpty()) {
                continue;
            }
            candidates.add(new OfficialLedgerSettlementCandidate(
                    winnerPlayerId.get(),
                    null,
                    settlementReasonForOfficialLedgerMatch(ledgerMatch),
                    ledgerMatch.sourceType(),
                    ledgerMatch.sourceUrl(),
                    ledgerMatch.date()
            ));
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Set<Long> uniqueWinners = new HashSet<>();
        for (OfficialLedgerSettlementCandidate candidate : candidates) {
            uniqueWinners.add(candidate.winnerPlayerId());
        }
        if (uniqueWinners.size() > 1) {
            log.warn(
                    "[paper] ambiguous official ledger settlement skipped: betId={} event='{}' targetDate={} candidateCount={} winners={}",
                    bet.getId(),
                    bet.getEventName(),
                    targetDate,
                    candidates.size(),
                    uniqueWinners.size()
            );
            return null;
        }

        candidates.sort(Comparator
                .comparing((OfficialLedgerSettlementCandidate candidate) -> officialLedgerSourcePriority(candidate.sourceType()))
                .thenComparing(OfficialLedgerSettlementCandidate::matchDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OfficialLedgerSettlementCandidate::sourceUrl, Comparator.nullsLast(String::compareToIgnoreCase)));
        return candidates.get(0);
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
        SlotAnchoredSettlementResolution slotAnchored = resolveSlotAnchoredSettlementCandidate(bet, targetDate);
        if (slotAnchored.blockFallback()) {
            return slotAnchored.match();
        }
        LocalDate fromDate = placedDate.isAfter(targetDate) ? placedDate : targetDate;
        List<Match> candidates = matchRepository.findCompletedMatchesByPlayersSince(
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                fromDate
        );
        return selectBestSettlementCandidate(sessionId, bet, targetDate, candidates);
    }

    private SlotAnchoredSettlementResolution resolveSlotAnchoredSettlementCandidate(PaperTradeBet bet,
                                                                                   LocalDate targetDate) {
        if (bet == null
                || bet.getPlayer1Id() == null
                || bet.getPlayer2Id() == null
                || targetDate == null) {
            return SlotAnchoredSettlementResolution.passThrough();
        }

        Set<String> slotTokens = expectedOfficialSlotTokens(bet);
        if (slotTokens.isEmpty()) {
            return SlotAnchoredSettlementResolution.passThrough();
        }

        List<Match> recentMatches = matchRepository.findRecentMatchesByPlayers(
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                PageRequest.of(0, 40)
        );
        if (recentMatches.isEmpty()) {
            return SlotAnchoredSettlementResolution.passThrough();
        }

        List<Match> exactSlotMatches = new ArrayList<>();
        for (Match candidate : recentMatches) {
            if (candidate == null || candidate.getDate() == null) {
                continue;
            }
            if (!Objects.equals(candidate.getDate(), targetDate)) {
                continue;
            }
            Optional<String> candidateSlot = extractOfficialSlotToken(candidate.getExternalId());
            if (candidateSlot.isPresent() && slotTokens.contains(candidateSlot.get())) {
                exactSlotMatches.add(candidate);
            }
        }

        if (exactSlotMatches.isEmpty()) {
            return SlotAnchoredSettlementResolution.passThrough();
        }

        List<Match> completedExact = exactSlotMatches.stream()
                .filter(match -> match.isComplete() && match.getWinnerPlayerId() != null)
                .sorted(Comparator.comparing(Match::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        if (!completedExact.isEmpty()) {
            Long winner = completedExact.get(0).getWinnerPlayerId();
            boolean conflicting = completedExact.stream()
                    .anyMatch(match -> !Objects.equals(match.getWinnerPlayerId(), winner));
            if (!conflicting) {
                return new SlotAnchoredSettlementResolution(completedExact.get(0), true);
            }
            log.warn(
                    "[paper] slot-anchored settlement candidate conflict skipped: betId={} event='{}' targetDate={} slotTokens={}",
                    bet.getId(),
                    bet.getEventName(),
                    targetDate,
                    slotTokens
            );
            return new SlotAnchoredSettlementResolution(null, true);
        }

        return new SlotAnchoredSettlementResolution(null, true);
    }

    private List<TtSeriesScraper.OfficialLedgerMatch> loadOfficialLedgerMatchesForBet(PaperTradeBet bet,
                                                                                      LocalDate targetDate,
                                                                                      OfficialResultRefreshContext refreshContext) {
        if (bet == null || refreshContext == null || targetDate == null) {
            return List.of();
        }
        String cacheKey = officialLedgerCacheKey(bet, targetDate);
        List<TtSeriesScraper.OfficialLedgerMatch> cached = refreshContext.cachedPairMatches(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<TtSeriesScraper.OfficialLedgerMatch> fetched = ttSeriesScraper.lookupOfficialMatchesForPair(
                bet.getPlayer1Name(),
                bet.getPlayer2Name(),
                24
        );
        List<TtSeriesScraper.OfficialLedgerMatch> safe = fetched == null ? List.of() : List.copyOf(fetched);
        refreshContext.cachePairMatches(cacheKey, safe);
        return safe;
    }

    private String officialLedgerCacheKey(PaperTradeBet bet, LocalDate targetDate) {
        String left = NameUtils.normalizeForLookup(bet == null ? null : bet.getPlayer1Name());
        String right = NameUtils.normalizeForLookup(bet == null ? null : bet.getPlayer2Name());
        if (left.compareTo(right) > 0) {
            String swap = left;
            left = right;
            right = swap;
        }
        return left + "|" + right + "|" + targetDate;
    }

    private Optional<Long> winnerPlayerIdFromOfficialLedgerMatch(PaperTradeBet bet,
                                                                 TtSeriesScraper.OfficialLedgerMatch ledgerMatch) {
        if (bet == null || ledgerMatch == null || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return Optional.empty();
        }
        String winnerLookup = NameUtils.normalizeForLookup(ledgerMatch.winnerRaw());
        String betPlayer1Lookup = NameUtils.normalizeForLookup(bet.getPlayer1Name());
        String betPlayer2Lookup = NameUtils.normalizeForLookup(bet.getPlayer2Name());
        if (StringUtils.hasText(winnerLookup)) {
            if (winnerLookup.equals(betPlayer1Lookup)) {
                return Optional.of(bet.getPlayer1Id());
            }
            if (winnerLookup.equals(betPlayer2Lookup)) {
                return Optional.of(bet.getPlayer2Id());
            }
        }

        String normalizedResult = normalizeResultLabel(ledgerMatch.result());
        if (!StringUtils.hasText(normalizedResult)) {
            return Optional.empty();
        }
        String[] sides = normalizedResult.split(":");
        if (sides.length != 2) {
            return Optional.empty();
        }
        try {
            int leftSets = Integer.parseInt(sides[0]);
            int rightSets = Integer.parseInt(sides[1]);
            if (leftSets == rightSets) {
                return Optional.empty();
            }
            String ledgerPlayer1Lookup = NameUtils.normalizeForLookup(ledgerMatch.player1Raw());
            String ledgerPlayer2Lookup = NameUtils.normalizeForLookup(ledgerMatch.player2Raw());
            if (ledgerPlayer1Lookup.equals(betPlayer1Lookup) && ledgerPlayer2Lookup.equals(betPlayer2Lookup)) {
                return Optional.of(leftSets > rightSets ? bet.getPlayer1Id() : bet.getPlayer2Id());
            }
            if (ledgerPlayer1Lookup.equals(betPlayer2Lookup) && ledgerPlayer2Lookup.equals(betPlayer1Lookup)) {
                return Optional.of(leftSets > rightSets ? bet.getPlayer2Id() : bet.getPlayer1Id());
            }
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private String settlementReasonForOfficialLedgerMatch(TtSeriesScraper.OfficialLedgerMatch ledgerMatch) {
        String sourceType = ledgerMatch == null ? "" : safeText(ledgerMatch.sourceType(), "");
        String normalized = sourceType.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("h2h")) {
            return "SETTLED_FROM_OFFICIAL_H2H_LEDGER";
        }
        if (normalized.contains("player")) {
            return "SETTLED_FROM_OFFICIAL_PLAYER_LEDGER";
        }
        return "SETTLED_FROM_OFFICIAL_LEDGER";
    }

    private int officialLedgerSourcePriority(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return 10;
        }
        String normalized = sourceType.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("h2h")) {
            return 0;
        }
        if (normalized.contains("player")) {
            return 1;
        }
        return 5;
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
        String feedEventId = effectiveSourceFeedEventId(bet);
        if (bet == null || !StringUtils.hasText(feedEventId)) {
            return null;
        }
        List<Match> candidates = matchRepository.findMatchesByFeedEventIdentity(feedEventId, PageRequest.of(0, 10));
        List<Match> eligible = new ArrayList<>();
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
            eligible.add(candidate);
        }
        if (eligible.isEmpty()) {
            return null;
        }
        Match first = eligible.get(0);
        for (int i = 1; i < eligible.size(); i++) {
            Match candidate = eligible.get(i);
            if (!Objects.equals(candidate.getDate(), first.getDate())
                    || !Objects.equals(candidate.getWinnerPlayerId(), first.getWinnerPlayerId())
                    || !Objects.equals(normalizeResultLabel(candidate.getResult()), normalizeResultLabel(first.getResult()))) {
                log.warn(
                        "[paper] ambiguous feed-identity settlement candidate skipped: betId={} feedEventId={} firstMatchId={} conflictingMatchId={}",
                        bet.getId(),
                        feedEventId,
                        first.getId(),
                        candidate.getId()
                );
                return null;
            }
        }
        return first;
    }

    private boolean isAmbiguousSettlementCandidateSelection(PaperTradeBet bet,
                                                            LocalDate targetDate,
                                                            List<SettlementCandidateRank> ranked,
                                                            SettlementCandidateRank best) {
        if (best == null || ranked == null || ranked.isEmpty()) {
            return true;
        }
        long bestScore = best.score();
        long tiedBestCount = ranked.stream()
                .filter(rank -> rank.score() == bestScore)
                .count();
        if (tiedBestCount > 1) {
            return true;
        }

        long sameDateCount = ranked.stream()
                .filter(rank -> Objects.equals(rank.match().getDate(), best.match().getDate()))
                .count();
        if (sameDateCount > 1) {
            return true;
        }

        Optional<Long> strongLiveLeader = determineStrongLeaderFromObservation(bet);
        if (strongLiveLeader.isPresent()
                && best.match().getWinnerPlayerId() != null
                && !Objects.equals(strongLiveLeader.get(), best.match().getWinnerPlayerId())) {
            boolean exactTargetDate = Objects.equals(best.match().getDate(), targetDate);
            if (!exactTargetDate || ranked.size() > 1) {
                return true;
            }
        }

        return false;
    }

    private Optional<Long> determineStrongLeaderFromObservation(PaperTradeBet bet) {
        if (bet == null || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return Optional.empty();
        }
        Optional<Long> settledWinner = scoreWinnerResolver.determineWinnerFromScore(
                bet.getLastObservedScore(),
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                bet.getLastObservedPhase(),
                true
        );
        if (settledWinner.isPresent()) {
            return settledWinner;
        }

        if (!StringUtils.hasText(bet.getLastObservedScore())) {
            return Optional.empty();
        }
        List<ScorePair> parsed = com.ttl.tabletennis.service.papertrade.ScorePair.parseAll(bet.getLastObservedScore());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        int targetSets = clamp(scoreSettlementTargetSets, 3, 7);
        int setPairIndex = com.ttl.tabletennis.service.papertrade.ScoreWinnerResolver.findPrimarySetScorePairIndex(parsed, targetSets);
        if (setPairIndex >= 0) {
            com.ttl.tabletennis.service.papertrade.ScorePair setScore = parsed.get(setPairIndex);
            int left = setScore.left();
            int right = setScore.right();
            int top = Math.max(left, right);
            int margin = Math.abs(left - right);
            if (top >= 2 && margin >= 2) {
                return Optional.of(left > right ? bet.getPlayer1Id() : bet.getPlayer2Id());
            }
        }

        Optional<Long> nearFinishLeader = scoreWinnerResolver.determineWinnerFromNearFinishFallback(
                bet.getLastObservedScore(),
                bet.getPlayer1Id(),
                bet.getPlayer2Id()
        );
        if (nearFinishLeader.isPresent()) {
            return nearFinishLeader;
        }

        return Optional.empty();
    }

    private String normalizeResultLabel(String result) {
        return safeText(result, "").trim().replace(" ", "");
    }

    private Set<String> expectedOfficialSlotTokens(PaperTradeBet bet) {
        if (bet == null) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addExpectedOfficialSlotTokens(tokens, bet.getLockedStartTimeIso());
        addExpectedOfficialSlotTokens(tokens, bet.getStartTimeIso());
        return tokens;
    }

    private void addExpectedOfficialSlotTokens(Set<String> tokens, String startTimeIso) {
        if (tokens == null || !StringUtils.hasText(startTimeIso)) {
            return;
        }
        String raw = startTimeIso.trim();
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(raw);
            tokens.add(parsed.atZoneSameInstant(OFFICIAL_RESULT_SLOT_ZONE).format(SLOT_TOKEN_FORMATTER));
            tokens.add(parsed.atZoneSameInstant(ZoneId.of("UTC")).format(SLOT_TOKEN_FORMATTER));
            tokens.add(parsed.atZoneSameInstant(ZoneId.systemDefault()).format(SLOT_TOKEN_FORMATTER));
            return;
        } catch (Exception ignore) {
            // continue
        }
        try {
            java.time.Instant parsed = java.time.Instant.parse(raw);
            tokens.add(parsed.atZone(OFFICIAL_RESULT_SLOT_ZONE).format(SLOT_TOKEN_FORMATTER));
            tokens.add(parsed.atZone(ZoneId.of("UTC")).format(SLOT_TOKEN_FORMATTER));
            tokens.add(parsed.atZone(ZoneId.systemDefault()).format(SLOT_TOKEN_FORMATTER));
            return;
        } catch (Exception ignore) {
            // continue
        }
        parseStartDateTime(raw).ifPresent(parsed -> tokens.add(parsed.format(SLOT_TOKEN_FORMATTER)));
    }

    private Optional<String> extractOfficialSlotToken(String externalId) {
        if (!StringUtils.hasText(externalId)) {
            return Optional.empty();
        }
        String[] tokens = externalId.trim().split("\\|");
        for (String token : tokens) {
            String normalized = safeText(token, "").trim();
            if (normalized.matches("\\d{4}")) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    private record SettlementCandidateRank(Match match, long score) {
    }

    private record SlotAnchoredSettlementResolution(Match match, boolean blockFallback) {
        static SlotAnchoredSettlementResolution passThrough() {
            return new SlotAnchoredSettlementResolution(null, false);
        }
    }

    private boolean matchesFeedIdentity(PaperTradeBet bet, Match candidate) {
        String feedEventId = effectiveSourceFeedEventId(bet);
        if (bet == null || candidate == null || !StringUtils.hasText(feedEventId)) {
            return false;
        }
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

    // scoreLabel / winnerName / loserName moved to CompletedMatchLogQueryService
    // as part of the §4 PaperTradingService decomposition (2026-05-19).

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
        private final Map<String, List<TtSeriesScraper.OfficialLedgerMatch>> pairLookupCache = new HashMap<>();

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

        List<TtSeriesScraper.OfficialLedgerMatch> cachedPairMatches(String key) {
            return pairLookupCache.get(key);
        }

        void cachePairMatches(String key, List<TtSeriesScraper.OfficialLedgerMatch> matches) {
            if (!StringUtils.hasText(key)) {
                return;
            }
            pairLookupCache.put(key, matches == null ? List.of() : List.copyOf(matches));
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

    private record OfficialLedgerSettlementCandidate(Long winnerPlayerId,
                                                     Long resultMatchId,
                                                     String settlementReason,
                                                     String sourceType,
                                                     String sourceUrl,
                                                     LocalDate matchDate) {
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

    // isEligible was removed as part of the §4 cleanup pass (2026-05-19) —
    // a 3-line wrapper around eligibilityRejectionReason that no one called.

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
        if (explorationEnabled) {
            edgeThreshold = Math.min(edgeThreshold, clamp(explorationMinEdge, 0.005, 0.10));
        }
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

        if (requireRecommendation && !row.recommended() && !explorationEnabled) {
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

    // isFinishedPhase moved to PaperTradingHelpers (import-static above).

    // parseStartDateTime moved to PaperTradingHelpers (import-static above).

    // toCandidate was removed as part of the §4 cleanup pass (2026-05-19) —
    // a 3-line wrapper around resolveCandidate.candidate() with no call sites.

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

    // isCandidateSafe was removed as part of the §4 cleanup pass (2026-05-19) —
    // a 3-line wrapper around candidateSafetyRejectionReason with no call sites.

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
        if (accuracyGuardEnabled
                && candidate.modelProbability() < clamp(accuracyGuardMinModelProbability, 0.50, 0.85)) {
            return "MODEL_WIN_PROBABILITY_TOO_LOW";
        }
        if (accuracyGuardEnabled && !accuracyGuardAllowPositiveOdds && candidate.americanOdds() > 0) {
            return "PLUS_MONEY_ACCURACY_GUARD";
        }
        double absoluteModelMarketGap = Math.abs(candidate.modelProbability() - candidate.impliedProbability());
        if (accuracyGuardEnabled
                && absoluteModelMarketGap > clamp(accuracyGuardMaxModelMarketGap, 0.04, 0.25)) {
            return "MODEL_MARKET_DISAGREEMENT_QUARANTINE";
        }
        Double noVigMarketProbability = noVigMarketProbability(row, candidate.sidePlayerId());
        if (accuracyGuardEnabled
                && noVigMarketProbability != null
                && Math.abs(candidate.modelProbability() - noVigMarketProbability)
                > clamp(accuracyGuardMaxNoVigModelMarketGap, 0.03, 0.25)) {
            return "NO_VIG_MARKET_DISAGREEMENT_QUARANTINE";
        }
        if (accuracyGuardEnabled
                && noVigMarketProbability != null
                && candidate.modelProbability() - noVigMarketProbability
                > clamp(accuracyGuardMaxPositiveNoVigModelMarketGap, 0.02, 0.10)) {
            return "POSITIVE_NO_VIG_GAP_RESEARCH_ONLY";
        }
        if (accuracyGuardEnabled
                && row.ratingAgreement() != null
                && Double.isFinite(row.ratingAgreement())
                && row.ratingAgreement() < clamp(accuracyGuardMinRatingAgreement, 0.50, 0.95)) {
            return "RATING_AGREEMENT_ACCURACY_GUARD";
        }
        if (accuracyGuardEnabled && signalQuality < clamp(accuracyGuardMinSignalQuality, 0.50, 0.90)) {
            return "SIGNAL_QUALITY_ACCURACY_GUARD";
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
        if (explorationEnabled) {
            edgeThreshold = Math.min(edgeThreshold, clamp(explorationMinEdge, 0.005, 0.10));
        }
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
        if (explorationEnabled) {
            requiredGap = Math.min(requiredGap, clamp(explorationMinModelImpliedGap, 0.005, 0.10));
        }
        if (probabilityEdge < requiredGap) {
            return "MODEL_GAP_BELOW_THRESHOLD";
        }
        double expectedRoi = (candidate.modelProbability() * candidate.decimalOdds()) - 1.0;
        double minExpectedReturn = clamp(minExpectedRoi, 0.0, 0.20);
        if (explorationEnabled) {
            minExpectedReturn = Math.min(minExpectedReturn, clamp(explorationMinExpectedRoi, 0.0, 0.10));
        }
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

    /**
     * Returns the selected side's market probability after removing the
     * sportsbook overround from the complete two-way line. A one-sided or
     * malformed price is intentionally treated as unavailable so a missing
     * opponent line cannot create artificial confidence.
     */
    static Double noVigMarketProbability(LiveOddsRecommendationDto row, Long sidePlayerId) {
        if (row == null || sidePlayerId == null) {
            return null;
        }
        double player1Implied = row.impliedProbabilityPlayer1();
        double player2Implied = row.impliedProbabilityPlayer2();
        double totalImplied = player1Implied + player2Implied;
        if (!Double.isFinite(player1Implied)
                || !Double.isFinite(player2Implied)
                || player1Implied <= 0.0
                || player2Implied <= 0.0
                || !Double.isFinite(totalImplied)
                || totalImplied <= 0.0) {
            return null;
        }
        if (sidePlayerId.equals(row.player1Id())) {
            return clamp(player1Implied / totalImplied, 0.0, 1.0);
        }
        if (sidePlayerId.equals(row.player2Id())) {
            return clamp(player2Implied / totalImplied, 0.0, 1.0);
        }
        return null;
    }

    /**
     * Distinguishes a production-quality recommendation from a bounded paper
     * exploration sample. The relaxed lane never changes the bettor-facing
     * recommendation flag and is capped independently in the placement loop.
     */
    private boolean isExplorationCandidate(LiveOddsRecommendationDto row,
                                           BetCandidate candidate,
                                           AdaptiveProfile adaptiveProfile) {
        if (!explorationEnabled) return false;
        double productionEdge = row.live()
                ? clamp(minEdgeLive, 0.005, 0.20)
                : clamp(minEdgePrematch, 0.005, 0.20);
        productionEdge = Math.max(productionEdge, clamp(minEdgeForBet, 0.005, 0.25));
        productionEdge = clamp(
                productionEdge + adaptiveProfile.edgeShift() + candidate.triggerSignal().edgeThresholdShift(),
                0.005, 0.30);

        double productionGap = row.live()
                ? clamp(minModelImpliedGapLive, 0.005, 0.20)
                : clamp(minModelImpliedGapPrematch, 0.005, 0.20);
        productionGap = Math.max(productionGap, clamp(minModelImpliedGap, 0.005, 0.20));
        productionGap = clamp(
                productionGap + adaptiveProfile.modelGapShift() + candidate.triggerSignal().modelGapShift(),
                0.005, 0.25);
        double expectedRoi = candidate.modelProbability() * candidate.decimalOdds() - 1.0;
        return !row.recommended()
                || candidate.edge() < productionEdge
                || candidate.modelProbability() - candidate.impliedProbability() < productionGap
                || expectedRoi < clamp(minExpectedRoi, 0.0, 0.20);
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
        if (fixedStakeEnabled) {
            double fixed = round2(Math.max(0.01, fixedStakeAmount));
            return available + EPS < fixed ? 0.0 : fixed;
        }
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

    private com.ttl.tabletennis.prediction.staking.StakingDecision stakingDecision(
            PaperTradeSession session,
            RankedCandidate ranked,
            List<com.ttl.tabletennis.prediction.staking.OpenPosition> openPositions,
            List<com.ttl.tabletennis.prediction.staking.SettledStake> settledHistory) {
        double unitSize = stakingUnitSize(session);
        BetCandidate candidate = ranked.candidate();
        LiveOddsRecommendationDto row = ranked.row();
        LocalDate exposureDate = parseStartDateTime(row.startTimeIso())
                .map(LocalDateTime::toLocalDate)
                .orElse(LocalDate.now());
        return stakingPolicy.decide(new com.ttl.tabletennis.prediction.staking.StakingRequest(
                ranked.eventKey(),
                row.player1Id(),
                row.player2Id(),
                candidate.sidePlayerId(),
                candidate.modelProbability(),
                candidate.decimalOdds(),
                candidate.edge(),
                Math.max(0.01, session.getCurrentBankroll() / unitSize),
                exposureDate,
                openPositions,
                settledHistory
        ));
    }

    private double stakingUnitSize(PaperTradeSession session) {
        double capital = session == null
                ? Math.max(100.0, defaultStartingBankroll)
                : Math.max(100.0, session.getStartingBankroll());
        return Math.max(1.0, capital * clamp(stakingUnitPct, 0.0025, 0.10));
    }

    private List<com.ttl.tabletennis.prediction.staking.OpenPosition> toPolicyOpenPositions(
            List<PaperTradeBet> openBets,
            PaperTradeSession session) {
        if (openBets == null || openBets.isEmpty()) {
            return List.of();
        }
        List<com.ttl.tabletennis.prediction.staking.OpenPosition> out = new ArrayList<>(openBets.size());
        for (PaperTradeBet bet : openBets) {
            if (bet != null) {
                out.add(toPolicyOpenPosition(bet, session, Math.max(0.0, bet.getStake())));
            }
        }
        return out;
    }

    private com.ttl.tabletennis.prediction.staking.OpenPosition toPolicyOpenPosition(
            PaperTradeBet bet,
            PaperTradeSession session,
            double stakeDollars) {
        double unitSize = stakingUnitSize(session);
        LocalDate exposureDate = parseStartDateTime(bet.getStartTimeIso())
                .map(LocalDateTime::toLocalDate)
                .orElseGet(() -> bet.getPlacedAt() == null ? LocalDate.now() : bet.getPlacedAt().toLocalDate());
        return new com.ttl.tabletennis.prediction.staking.OpenPosition(
                safeText(bet.getEventKey(), ""),
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                bet.getSidePlayerId(),
                Math.max(0.0, stakeDollars) / unitSize,
                exposureDate
        );
    }

    private List<com.ttl.tabletennis.prediction.staking.SettledStake> toPolicySettledHistory(
            PaperTradeSession session) {
        if (session == null || session.getId() == null) {
            return List.of();
        }
        List<PaperTradeBet> settled = betRepository.findBySessionIdAndStatusInOrderBySettledAtAsc(
                session.getId(),
                List.of(
                        PaperTradeBet.STATUS_WON,
                        PaperTradeBet.STATUS_LOST,
                        PaperTradeBet.STATUS_PUSHED,
                        PaperTradeBet.STATUS_VOIDED
                )
        );
        if (settled == null || settled.isEmpty()) {
            return List.of();
        }
        double unitSize = stakingUnitSize(session);
        int from = Math.max(0, settled.size() - 100);
        List<com.ttl.tabletennis.prediction.staking.SettledStake> out =
                new ArrayList<>(settled.size() - from);
        for (int i = from; i < settled.size(); i++) {
            PaperTradeBet bet = settled.get(i);
            if (bet == null) {
                continue;
            }
            out.add(new com.ttl.tabletennis.prediction.staking.SettledStake(
                    Math.max(0.0, bet.getStake()) / unitSize,
                    (bet.getProfitLoss() == null ? 0.0 : bet.getProfitLoss()) / unitSize
            ));
        }
        return out;
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

    // buildEventKey moved to MatchKeyBuilder.

    /** Thin delegate — see {@link com.ttl.tabletennis.service.papertrade.SessionLifecycleService}. */
    private PaperTradeSession getOrCreateActiveSession() {
        return sessionLifecycleService.getOrCreateActiveSession();
    }

    /** Thin delegate — see {@link com.ttl.tabletennis.service.papertrade.SessionLifecycleService}. */
    private PaperTradeSession createSession(Double startingBankroll, String label) {
        return sessionLifecycleService.createSession(startingBankroll, label);
    }

    /** Thin delegate — see {@link com.ttl.tabletennis.service.papertrade.SessionLifecycleService}. */
    private PaperTradeSession saveSession(PaperTradeSession session) {
        return sessionLifecycleService.saveSession(session);
    }

    /** Thin delegate — see {@link com.ttl.tabletennis.service.papertrade.SessionLifecycleService}. */
    private List<PaperTradeSession> saveSessions(List<PaperTradeSession> sessions) {
        return sessionLifecycleService.saveSessions(sessions);
    }

    private PaperTradeBet saveBet(PaperTradeBet bet) {
        PaperTradeBet saved = betRepository.save(bet);
        paperTradingShadowService.mirrorBet(saved);
        return saved;
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
        BetCandidate telemetryCandidate = candidate;
        if (telemetryCandidate == null) {
            telemetryCandidate = resolveCandidate(row, AdaptiveProfile.neutral()).candidate();
        }
        double telemetrySignalQuality = telemetryCandidate == null
                ? candidateSignalQuality(row, TriggerAdaptiveSignal.neutral())
                : telemetryCandidate.signalQuality();
        double telemetrySelectionScore = selectionScore != null
                ? selectionScore
                : telemetryCandidate == null
                ? 0.0
                : scoreCandidate(row, telemetryCandidate, AdaptiveProfile.neutral());
        PaperTradeDecisionSample sample = new PaperTradeDecisionSample();
        String effectiveModelVersion = safeText(row.modelVersion(), modelVersion);
        sample.setSessionId(sessionId);
        sample.setSource(safeText(row.source(), "UNKNOWN"));
        sample.setStrategy(safeText(strategy, "CONSERVATIVE"));
        sample.setModelVersion(effectiveModelVersion);
        sample.setEventKey(StringUtils.hasText(eventKey) ? eventKey.trim() : resolveDecisionEventKey(row));
        sample.setDedupeKey(StringUtils.hasText(dedupeKey) ? dedupeKey.trim() : resolveDecisionDedupeKey(row, eventKey, candidate));
        sample.setEventName(safeText(row.eventName(), "Unknown Event"));
        sample.setCompetitionName(safeText(row.competitionName(), "Table Tennis"));
        sample.setLive(row.live());
        sample.setPlayer1Id(row.player1Id());
        sample.setPlayer1Name(row.player1Name());
        sample.setPlayer2Id(row.player2Id());
        sample.setPlayer2Name(row.player2Name());
        sample.setSidePlayerId(telemetryCandidate == null ? null : telemetryCandidate.sidePlayerId());
        sample.setSideName(telemetryCandidate == null ? row.suggestedSide() : telemetryCandidate.sideName());
        sample.setTopTrigger(row.topTrigger());
        sample.setFeatureContributions(serializeFeatureContributions(row));
        sample.setOverallReliability(row.overallReliability());
        sample.setRatingAgreement(row.ratingAgreement());
        sample.setTriggerReliability(row.topTriggerReliability());
        sample.setBaselineStability(row.suggestedSideBaselineStability());
        sample.setRecommended(row.recommended());
        sample.setFallbackPick(fallbackPick);
        sample.setSuggestedEdge(valueOrZero(row.suggestedEdge()));
        sample.setModelProbability(telemetryCandidate == null ? null : telemetryCandidate.modelProbability());
        sample.setImpliedProbability(telemetryCandidate == null ? null : telemetryCandidate.impliedProbability());
        sample.setSelectionScore(round4(telemetrySelectionScore));
        sample.setSignalQuality(round4(telemetrySignalQuality));
        sample.setConfidenceWidth(round4(confidenceWidth(row)));
        sample.setAmericanOdds(telemetryCandidate == null ? null : telemetryCandidate.americanOdds());
        sample.setProposedStake(proposedStake == null ? null : round2(proposedStake));
        sample.setCappedStake(cappedStake == null ? null : round2(cappedStake));
        sample.setDecisionStatus(safeText(decisionStatus, "SKIPPED"));
        sample.setDecisionReason(safeText(decisionReason, "UNKNOWN"));
        sample.setGateResults(buildGateResults(
                row, telemetryCandidate, telemetrySignalQuality, decisionStatus, decisionReason));
        decisionSampleRepository.save(sample);
        modelCallLedgerService.recordCall(
                sessionId,
                strategy,
                effectiveModelVersion,
                row,
                sample.getEventKey(),
                decisionStatus,
                decisionReason,
                sample
        );
    }

    private String buildGateResults(LiveOddsRecommendationDto row,
                                    BetCandidate candidate,
                                    double signalQuality,
                                    String decisionStatus,
                                    String decisionReason) {
        List<String> gates = new ArrayList<>();
        if (candidate == null) {
            gates.add("candidate=NA");
        } else {
            double probabilityFloor = clamp(accuracyGuardMinModelProbability, 0.50, 0.85);
            gates.add(gate("model_probability", candidate.modelProbability() >= probabilityFloor,
                    candidate.modelProbability(), probabilityFloor));
            gates.add("plus_money=" + (accuracyGuardAllowPositiveOdds || candidate.americanOdds() <= 0 ? "PASS" : "FAIL"));

            double rawGap = Math.abs(candidate.modelProbability() - candidate.impliedProbability());
            double rawGapCap = clamp(accuracyGuardMaxModelMarketGap, 0.04, 0.25);
            gates.add(gate("raw_market_gap", rawGap <= rawGapCap, rawGap, rawGapCap));

            Double noVig = noVigMarketProbability(row, candidate.sidePlayerId());
            if (noVig == null) {
                gates.add("no_vig_available=FAIL");
                gates.add("no_vig_abs_gap=NA");
                gates.add("positive_no_vig_gap=NA");
            } else {
                double gap = candidate.modelProbability() - noVig;
                double absCap = clamp(accuracyGuardMaxNoVigModelMarketGap, 0.03, 0.25);
                double positiveCap = clamp(accuracyGuardMaxPositiveNoVigModelMarketGap, 0.02, 0.10);
                gates.add("no_vig_available=PASS");
                gates.add(gate("no_vig_abs_gap", Math.abs(gap) <= absCap, Math.abs(gap), absCap));
                gates.add(gate("positive_no_vig_gap", gap <= positiveCap, gap, positiveCap));
            }

            double agreementFloor = clamp(accuracyGuardMinRatingAgreement, 0.50, 0.95);
            if (row.ratingAgreement() == null || !Double.isFinite(row.ratingAgreement())) {
                gates.add("rating_agreement=FAIL(value=NA)");
            } else {
                gates.add(gate("rating_agreement", row.ratingAgreement() >= agreementFloor,
                        row.ratingAgreement(), agreementFloor));
            }
            double qualityFloor = clamp(accuracyGuardMinSignalQuality, 0.50, 0.90);
            gates.add(gate("signal_quality", signalQuality >= qualityFloor, signalQuality, qualityFloor));
        }
        gates.add("decision=" + safeText(decisionStatus, "SKIPPED").toUpperCase(Locale.ROOT));
        gates.add("reason=" + safeText(decisionReason, "UNKNOWN").toUpperCase(Locale.ROOT));
        return String.join("|", gates);
    }

    private String gate(String name, boolean passed, double value, double threshold) {
        return String.format(Locale.ROOT, "%s=%s(value=%.4f,threshold=%.4f)",
                name, passed ? "PASS" : "FAIL", value, threshold);
    }

    // applyAdaptiveSnapshot promoted to AdaptiveProfile.applyTo(session, now)
    // — see com.ttl.tabletennis.service.papertrade.AdaptiveProfile.

    private boolean persistLearningSample(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null || !StringUtils.hasText(bet.getStatus())) {
            return false;
        }
        String status = bet.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!(PaperTradeBet.STATUS_WON.equals(status)
                || PaperTradeBet.STATUS_LOST.equals(status)
                || PaperTradeBet.STATUS_PUSHED.equals(status)
                || PaperTradeBet.STATUS_VOIDED.equals(status))) {
            return false;
        }
        Optional<PaperTradeLearningSample> existing = learningSampleRepository.findByBetId(bet.getId());
        PaperTradeLearningSample sample = existing.orElseGet(PaperTradeLearningSample::new);
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
        sample.setPlacementPhase(StringUtils.hasText(bet.getPlacementPhase())
                ? bet.getPlacementPhase().trim()
                : (bet.isLiveAtPlacement() ? "LIVE" : "PREMATCH"));
        sample.setEventOccurredAt(parseStartDateTime(bet.getStartTimeIso()).orElse(bet.getPlacedAt()));
        sample.setSettlementSource(bet.getSettlementSource());
        sample.setSettlementReason(bet.getSettlementReason());
        LearningSampleQuality.Assessment quality = LearningSampleQuality.assess(bet);
        sample.setSettlementConfidence(quality.confidence());
        sample.setLearningEligible(quality.learningEligible());
        sample.setLearningExclusionReason(quality.exclusionReason());
        sample.setPriceRegime(LearningSampleQuality.priceRegime(bet.getImpliedProbability()));
        sample.setSideOrientation(Objects.equals(bet.getSidePlayerId(), bet.getPlayer1Id()) ? "P1"
                : Objects.equals(bet.getSidePlayerId(), bet.getPlayer2Id()) ? "P2"
                : "NA");
        sample.setFeatureContributions(bet.getFeatureContributions());
        sample.setPlacedAt(bet.getPlacedAt());
        sample.setSettledAt(bet.getSettledAt() == null ? LocalDateTime.now() : bet.getSettledAt());
        if (bet.getClosingDecimalOdds() != null) {
            sample.setClosingDecimalOdds(bet.getClosingDecimalOdds());
            sample.setClosingObservedAt(bet.getClosingObservedAt());
            sample.setClosingSource(bet.getClosingSource());
            sample.setClosingMarketState(bet.getClosingMarketState());
        }
        if (sample.getClosingDecimalOdds() == null) {
            attachClosingLine(bet, sample);
        }
        learningSampleRepository.save(sample);
        return existing.isEmpty();
    }

    private void attachClosingLine(PaperTradeBet bet, PaperTradeLearningSample sample) {
        if (closingLineLookupService == null) {
            return;
        }
        try {
            closingLineLookupService.findFor(bet).ifPresent(line -> {
                sample.setClosingDecimalOdds(line.decimalOdds());
                sample.setClosingObservedAt(line.observedAt());
                sample.setClosingSource(line.sourceId());
                sample.setClosingMarketState(line.marketState());
            });
        } catch (RuntimeException ex) {
            log.warn("[paper] closing-line lookup failed for bet {}: {}", bet.getId(), ex.getMessage());
        }
    }

    private void captureClosingLineOnBet(PaperTradeBet bet) {
        if (closingLineLookupService == null || bet == null || bet.getClosingDecimalOdds() != null) {
            return;
        }
        try {
            closingLineLookupService.findFor(bet).ifPresent(line -> {
                bet.setClosingDecimalOdds(line.decimalOdds());
                bet.setClosingObservedAt(line.observedAt());
                bet.setClosingSource(line.sourceId());
                bet.setClosingMarketState(line.marketState());
            });
        } catch (RuntimeException ex) {
            log.warn("[paper] closing-line capture failed for bet {}: {}", bet.getId(), ex.getMessage());
        }
    }

    private List<AdaptiveDecisionSample> loadAdaptiveDecisionSamples(int historyTake) {
        int take = clamp(historyTake, 20, 500);
        List<AdaptiveDecisionSample> out = new ArrayList<>(take);
        Set<Long> seenBetIds = new HashSet<>();

        List<PaperTradeLearningSample> learningRows =
                learningSampleRepository.findByLearningEligibleTrueAndStatusInOrderByEventOccurredAtDesc(
                List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST),
                PageRequest.of(0, take)
        );
        if (learningRows.isEmpty()) {
            backfillLearningSamples(Math.max(500, take * 6));
            learningRows = learningSampleRepository.findByLearningEligibleTrueAndStatusInOrderByEventOccurredAtDesc(
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
                    row.getEventOccurredAt() == null ? row.getPlacedAt() : row.getEventOccurredAt(),
                    row.getSettlementConfidence()
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
            LearningSampleQuality.Assessment quality = LearningSampleQuality.assess(bet);
            if (!quality.learningEligible()) {
                continue;
            }
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
                    parseStartDateTime(bet.getStartTimeIso()).orElse(bet.getPlacedAt()),
                    quality.confidence()
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
                if (persistLearningSample(bet)) {
                    inserted++;
                }
            }

            if (rows.size() < pageSize || scanned >= maxRows) {
                break;
            }
            page++;
        }
        return inserted;
    }

    // adaptiveRecencyWeight moved to AdaptiveProfileBuilder.recencyWeight — it
    // was used only by buildAdaptiveProfile.

    // normalizeTrigger moved to PaperTradingHelpers (2026-05-19).

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

    private String serializeFeatureContributions(LiveOddsRecommendationDto row) {
        if (row == null || row.featureContributions() == null || row.featureContributions().isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (com.ttl.tabletennis.dto.MatchupAnalysisDto.FeatureContributionDto contribution
                : row.featureContributions()) {
            if (contribution == null || !StringUtils.hasText(contribution.feature())) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('|');
            }
            out.append(contribution.feature().trim().replace("|", "/"))
                    .append('=')
                    .append(String.format(Locale.ROOT, "%.6f", contribution.contribution()));
            if (out.length() >= 2350) {
                break;
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private String fitRationale(String base, String metadata) {
        final int maxLength = 512;
        String suffix = metadata == null ? "" : metadata;
        if (suffix.length() >= maxLength) {
            return suffix.substring(0, maxLength);
        }
        String prefix = safeText(base, "Model value pick");
        int allowedPrefixLength = maxLength - suffix.length();
        if (prefix.length() > allowedPrefixLength) {
            prefix = prefix.substring(0, allowedPrefixLength);
        }
        return prefix + suffix;
    }

    // safeText moved to PaperTradingHelpers (2026-05-19).

    // normalizeKey moved to PaperTradingHelpers (import-static above).

    // toPairKey / toPairStartKey / playerToken / normalizePersonToken moved to MatchKeyBuilder.

    // startBucket moved to PaperTradingHelpers (import-static above).

    // clamp / round2 / round4 / valueOrZero moved to PaperTradingHelpers (2026-05-19).

    private String resolveDecisionEventKey(LiveOddsRecommendationDto row) {
        if (row == null) {
            return null;
        }
        if (StringUtils.hasText(row.matchupKey())) {
            return row.matchupKey().trim();
        }
        return com.ttl.tabletennis.service.papertrade.MatchKeyBuilder.buildEventKey(row);
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

    // averageNonNull moved to DecisionTelemetryBuilder — it was used only by
    // buildDecisionTelemetry. See paper-trading-service-decomposition.md.

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

    // ExposureProfile moved to com.ttl.tabletennis.service.papertrade.ExposureProfile
    // as part of the §4 decomposition. Both the placement loop and
    // ExposureMetricsBuilder now reference the top-level class.

    private record RankedCandidate(LiveOddsRecommendationDto row,
                                   BetCandidate candidate,
                                   String eventKey,
                                   String dedupeKey,
                                   double selectionScore,
                                   boolean fallbackPick) {
    }

    // ScorePair record moved to com.ttl.tabletennis.service.papertrade.ScorePair.

    // ScoreOrientation enum moved to ScoreNormalizer.

    // RowLookup record moved to com.ttl.tabletennis.service.papertrade.RowLookup.

    record SettlementStats(int settled, int voided) {
        static SettlementStats empty() {
            return new SettlementStats(0, 0);
        }
    }

    // AdaptiveProfile, TriggerAdaptiveSignal, AdaptiveDecisionSample,
    // TriggerAggregate moved to com.ttl.tabletennis.service.papertrade.*
    // as part of the §4 decomposition (slice A: lift the records).
}
