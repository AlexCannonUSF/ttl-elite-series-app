package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.OddsQuote;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.ValueOpportunity;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.LiveScoreSnapshotDto;
import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.dto.OddsRefreshResultDto;
import com.ttl.tabletennis.dto.ValueOpportunityDto;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.prediction.live.TableTennisLiveProbability;
import com.ttl.tabletennis.repository.OddsQuoteRepository;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.ValueOpportunityRepository;
import com.ttl.tabletennis.scrape.FeedClient;
import com.ttl.tabletennis.scrape.HardRockFeedClient;
import com.ttl.tabletennis.scrape.HardRockOddsScraper;
import com.ttl.tabletennis.scrape.HardRockScoreStreamClient;
import com.ttl.tabletennis.util.CorrelationContext;
import com.ttl.tabletennis.util.NameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OddsValueEngineService {
    private static final Pattern SCORE_PAIR_PATTERN = Pattern.compile("(\\d{1,2})\\s*[-:]\\s*(\\d{1,2})");
    private static final Logger log = LoggerFactory.getLogger(OddsValueEngineService.class);

    public static final String STRATEGY_CONSERVATIVE = "CONSERVATIVE";
    public static final String STRATEGY_AGGRESSIVE = "AGGRESSIVE";

    /** Cached scrape result + the timestamp it was captured. Used by
     *  {@link #liveOddsRecommendations(String, String, int, boolean)} so the
     *  live board doesn't synchronously hit the upstream HTTP scraper on
     *  every page load. TTL is governed by {@link #LIVE_SCRAPE_CACHE_TTL_MS}.
     *  Stale-while-revalidate: if the cache exists but is older than the TTL,
     *  the request still serves the stale rows immediately and triggers a
     *  background refresh — so the board stays responsive even when Hard Rock
     *  is slow / rate-limiting / down. */
    private static final long LIVE_SCRAPE_CACHE_TTL_MS = 5_000L;
    /** Hard cap on how long the first request will block waiting for the
     *  initial scrape. Beyond this we return an empty list and let the
     *  scrape finish in the background. */
    private static final long LIVE_SCRAPE_FIRST_HIT_BUDGET_MS = 4_000L;
    /** Result-level cache (post predict) — much heavier than the scrape, so a
     *  longer TTL is fine. Live odds shift on second-by-second timescales, but
     *  a 10s cache is still well below "feels stale" for the UI and saves us
     *  re-running per-row prediction on every poll. */
    private static final long LIVE_RECS_CACHE_TTL_MS = 10_000L;
    /** First-paint budget for the full recommendation pipeline. Beyond this
     *  the request returns whatever is already in cache (possibly empty) and
     *  the background task continues to fill the cache for the next paint. */
    private static final long LIVE_RECS_FIRST_HIT_BUDGET_MS = 4_000L;
    /** Lite-row cache TTL — used as the cold-cache fallback before the full
     *  prediction-enriched compute finishes. Lite rows have odds + player
     *  identities but no predictions, so they're cheap (~ms) to recompute. */
    private static final long LIVE_LITE_CACHE_TTL_MS = 5_000L;
    private static final int LIVE_RECS_CACHE_ROW_LIMIT = 250;
    private final java.util.concurrent.atomic.AtomicReference<CachedScrape> cachedScrape =
            new java.util.concurrent.atomic.AtomicReference<>(null);
    private final java.util.concurrent.atomic.AtomicBoolean scrapeInflight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.ExecutorService scrapeRefreshExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "odds-engine-scrape-refresh");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.ConcurrentMap<RecsKey, CachedRecs> cachedRecs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<RecsKey, CachedRecs> cachedLiteRecs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<RecsKey, java.util.concurrent.Future<?>> recsInflight =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService recsRefreshExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "odds-engine-recs-refresh");
                t.setDaemon(true);
                return t;
            });

    private record CachedScrape(List<MatchOdds> rows, long capturedAtMillis) { }
    private record RecsKey(String strategy, String modelSelector) { }
    private record CachedRecs(List<LiveOddsRecommendationDto> rows, long capturedAtMillis) { }

    private final PredictionFacade predictionFacade;
    private final PlayerIdentityService playerIdentityService;
    private final HardRockOddsScraper hardRockOddsScraper;
    private HardRockFeedClient hardRockFeedClient;
    private HardRockScoreStreamClient hardRockScoreStreamClient;
    private final OddsQuoteRepository oddsQuoteRepository;
    private final ValueOpportunityRepository valueOpportunityRepository;
    private final MatchRepository matchRepository;

    @Value("${ttl.odds.defaultModelFamily:ENSEMBLE}")
    private String defaultModelFamily;

    @Value("${ttl.odds.conservativeThreshold:0.055}")
    private double conservativeThreshold;

    @Value("${ttl.odds.aggressiveThreshold:0.030}")
    private double aggressiveThreshold;

    @Value("${ttl.odds.retentionDays:30}")
    private int retentionDays;

    @Value("${ttl.odds.maxRecommendedAmericanOdds:220}")
    private int maxRecommendedAmericanOdds;

    @Value("${ttl.odds.maxCompletedDataAgeDays:14}")
    private int maxCompletedDataAgeDays;

    @Value("${ttl.prediction.liveScoreModelValidated:false}")
    private boolean liveScoreModelValidated;

    public OddsValueEngineService(PredictionFacade predictionFacade,
                                  PlayerIdentityService playerIdentityService,
                                  HardRockOddsScraper hardRockOddsScraper,
                                  OddsQuoteRepository oddsQuoteRepository,
                                  ValueOpportunityRepository valueOpportunityRepository,
                                  MatchRepository matchRepository) {
        this.predictionFacade = predictionFacade;
        this.playerIdentityService = playerIdentityService;
        this.hardRockOddsScraper = hardRockOddsScraper;
        this.oddsQuoteRepository = oddsQuoteRepository;
        this.valueOpportunityRepository = valueOpportunityRepository;
        this.matchRepository = matchRepository;
    }

    /**
     * Route production market pulls through the Phase 01 feed adapter so the
     * admin health surface and Redis shadow stream observe the exact traffic
     * that powers the live board. Kept as an optional setter so focused unit
     * tests can continue constructing this service with a mocked legacy
     * scraper and no Spring context.
     */
    @Autowired(required = false)
    void setHardRockFeedClient(HardRockFeedClient hardRockFeedClient) {
        this.hardRockFeedClient = hardRockFeedClient;
    }

    @Autowired(required = false)
    void setHardRockScoreStreamClient(HardRockScoreStreamClient hardRockScoreStreamClient) {
        this.hardRockScoreStreamClient = hardRockScoreStreamClient;
    }

    /**
     * Eagerly warm the live-board prediction cache when the application is
     * ready. Without this, the first user request after boot only sees lite
     * rows (player names + odds) while the full predict-per-row compute runs
     * in the background. With it, the per-matchup {@link PredictionFacade}
     * cache populates during boot so the user's first paint already shows
     * model probabilities and edges.
     *
     * <p>Runs on the existing background executor so we never block startup.
     * Idempotent — re-firing is a cache hit if predictions are already warm.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmLiveBoardOnStartup() {
        // Delay on the scrape executor, then enter the same per-key refresh
        // path as request traffic. A first browser poll can therefore share
        // this future instead of queuing a duplicate full-board calculation.
        scrapeRefreshExecutor.submit(() -> {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            String strategy = STRATEGY_CONSERVATIVE;
            String modelSelector = StringUtils.hasText(defaultModelFamily) ? defaultModelFamily : "ENSEMBLE";
            RecsKey key = new RecsKey(strategy, modelSelector);
            log.info("[odds-engine] warmup scheduled — strategy={} model={}", strategy, modelSelector);
            triggerRecsBackgroundRefresh(key);
        });
    }

    @Transactional
    public OddsRefreshResultDto refresh(String strategyRaw, String modelFamilyRaw) {
        try (CorrelationContext.Scope ignored = CorrelationContext.openIfAbsent(null)) {
            List<MatchOdds> fetched = pullHardRockMarket("odds-engine-manual-refresh");
            return refreshFromQuotes(strategyRaw, modelFamilyRaw, fetched, "HARD_ROCK");
        }
    }

    @Transactional
    OddsRefreshResultDto refreshFromQuotes(String strategyRaw,
                                           String modelSelectorRaw,
                                           List<MatchOdds> fetchedQuotes,
                                           String source) {
        String strategy = normalizeStrategy(strategyRaw);
        String modelSelector = StringUtils.hasText(modelSelectorRaw)
                ? modelSelectorRaw.trim()
                : defaultModelFamily;
        String quoteSource = StringUtils.hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
        List<MatchOdds> fetched = fetchedQuotes == null ? List.of() : fetchedQuotes;

        int resolved = 0;
        int opportunities = 0;
        LocalDateTime now = LocalDateTime.now();
        String effectiveModelVersion = modelSelector;

        for (MatchOdds odds : fetched) {
            if (odds.getOddsA() <= 1.0 || odds.getOddsB() <= 1.0) {
                continue;
            }

            OddsQuote quote = persistQuote(odds, quoteSource, now);

            Optional<Player> p1Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerA());
            Optional<Player> p2Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerB());
            if (p1Opt.isEmpty() || p2Opt.isEmpty()) {
                continue;
            }
            Player p1 = p1Opt.get();
            Player p2 = p2Opt.get();
            if (p1.getId() == null || p2.getId() == null || p1.getId().equals(p2.getId())) {
                continue;
            }
            resolved++;

            double breakEven1 = 1.0 / odds.getOddsA();
            double breakEven2 = 1.0 / odds.getOddsB();
            double bookImpliedTotal = breakEven1 + breakEven2;
            if (bookImpliedTotal <= 0.0) {
                continue;
            }
            double noVigMarket1 = breakEven1 / bookImpliedTotal;
            double noVigMarket2 = breakEven2 / bookImpliedTotal;

            PredictionModelService.PredictionSnapshot prediction = predictionFacade.predict(
                    p1.getId(),
                    p2.getId(),
                    LocalDate.now(),
                    modelSelector
            );
            if (StringUtils.hasText(prediction.modelVersion())) {
                effectiveModelVersion = prediction.modelVersion();
            }

            LiveAdjustedProbability tunedSnapshot = applyRegimeTuning(
                    new LiveAdjustedProbability(
                            prediction.player1Probability(),
                            prediction.player2Probability(),
                            prediction.player1ConfidenceLow(),
                            prediction.player1ConfidenceHigh(),
                            1.0 - prediction.player1ConfidenceHigh(),
                            1.0 - prediction.player1ConfidenceLow()
                    ),
                    predictionFacade.currentAdaptiveRegimeTuning(odds.isLive(), odds.getMatchPhase(), noVigMarket1),
                    predictionFacade.currentAdaptiveRegimeTuning(odds.isLive(), odds.getMatchPhase(), noVigMarket2)
            );

            double threshold = strategyThreshold(strategy);
            opportunities += maybePersistOpportunity(
                    strategy,
                    prediction.modelVersion(),
                    threshold,
                    quote.getSource(),
                    p1,
                    p2,
                    p1,
                    tunedSnapshot.player1Probability(),
                    tunedSnapshot.player1ConfidenceLow(),
                    tunedSnapshot.player1ConfidenceHigh(),
                    breakEven1,
                    quote.getAmericanOddsPlayer1(),
                    now
            );

            opportunities += maybePersistOpportunity(
                    strategy,
                    prediction.modelVersion(),
                    threshold,
                    quote.getSource(),
                    p1,
                    p2,
                    p2,
                    tunedSnapshot.player2Probability(),
                    tunedSnapshot.player2ConfidenceLow(),
                    tunedSnapshot.player2ConfidenceHigh(),
                    breakEven2,
                    quote.getAmericanOddsPlayer2(),
                    now
            );
        }

        LocalDateTime cutoff = now.minusDays(Math.max(1, retentionDays));
        valueOpportunityRepository.deleteByCreatedAtBefore(cutoff);
        oddsQuoteRepository.deleteByScrapedAtBefore(cutoff);

        return new OddsRefreshResultDto(
                quoteSource,
                fetched.size(),
                resolved,
                opportunities,
                strategy,
                effectiveModelVersion,
                now
        );
    }

    @Transactional(readOnly = true)
    public List<ValueOpportunityDto> listValueOpportunities(String strategyRaw, int limit) {
        int take = Math.max(1, Math.min(limit, 200));
        String strategy = normalizeStrategy(strategyRaw);
        List<ValueOpportunity> rows;
        if (!StringUtils.hasText(strategyRaw) || "ALL".equalsIgnoreCase(strategyRaw.trim())) {
            rows = valueOpportunityRepository.findAllByOrderByCreatedAtDescEdgeDesc(PageRequest.of(0, take));
        } else {
            rows = valueOpportunityRepository.findByStrategyOrderByCreatedAtDescEdgeDesc(strategy, PageRequest.of(0, take));
        }
        List<ValueOpportunityDto> out = new ArrayList<>(rows.size());
        for (ValueOpportunity row : rows) {
            out.add(new ValueOpportunityDto(
                    row.getId(),
                    row.getSource(),
                    row.getStrategy(),
                    row.getModelVersion(),
                    row.getPlayer1Id(),
                    row.getPlayer2Id(),
                    row.getPlayerSideId(),
                    row.getPlayerSideName(),
                    row.getModelProbability(),
                    row.getConfidenceLow(),
                    row.getConfidenceHigh(),
                    row.getImpliedProbability(),
                    row.getEdge(),
                    row.getThreshold(),
                    row.getAmericanOdds(),
                    row.getCreatedAt()
            ));
        }
        return out;
    }

    public List<LiveOddsRecommendationDto> liveOddsRecommendations(String strategyRaw,
                                                                   String modelSelectorRaw,
                                                                   int limit,
                                                                   boolean includeUnresolved) {
        int take = Math.max(1, Math.min(limit, 250));
        String strategy = normalizeStrategy(strategyRaw);
        String modelSelector = StringUtils.hasText(modelSelectorRaw)
                ? modelSelectorRaw.trim()
                : defaultModelFamily;
        RecsKey key = new RecsKey(strategy, modelSelector);

        long now = System.currentTimeMillis();
        // ── Tier 1: full predictions, cached for LIVE_RECS_CACHE_TTL_MS. ──
        CachedRecs current = cachedRecs.get(key);
        if (current != null && (now - current.capturedAtMillis()) < LIVE_RECS_CACHE_TTL_MS) {
            return viewForRequest(current.rows(), take, includeUnresolved);
        }
        // Either stale or missing — kick off a background full recompute.
        java.util.concurrent.Future<?> refresh = triggerRecsBackgroundRefresh(key);
        if (current != null) {
            // Stale-while-revalidate: serve previous full rows immediately.
            return viewForRequest(current.rows(), take, includeUnresolved);
        }
        // ── Tier 2: lite rows. If we have a fresh lite cache entry, serve
        //   it instantly while the background full compute continues. This
        //   is the path almost all polls take once the lite cache is warm. ──
        CachedRecs lite = cachedLiteRecs.get(key);
        if (lite != null && (now - lite.capturedAtMillis()) < LIVE_LITE_CACHE_TTL_MS) {
            return viewForRequest(lite.rows(), take, includeUnresolved);
        }
        // ── First paint only (lite cache never populated). Wait briefly for
        //   the full compute (cheap in tests with mocked deps and once the
        //   page cache is warm in prod). Subsequent expired-lite calls skip
        //   this wait — they just recompute lite directly. ──
        if (refresh != null && lite == null) {
            try {
                refresh.get(LIVE_RECS_FIRST_HIT_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                CachedRecs justComputed = cachedRecs.get(key);
                if (justComputed != null) {
                    return viewForRequest(justComputed.rows(), take, includeUnresolved);
                }
            } catch (java.util.concurrent.TimeoutException ex) {
                log.info("[odds-engine] first-hit recs compute exceeded {} ms budget; falling back to lite rows while compute continues",
                        LIVE_RECS_FIRST_HIT_BUDGET_MS);
            } catch (java.util.concurrent.ExecutionException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("[odds-engine] first-hit recs compute failed: {}", ex.toString());
            }
        }
        try {
            double threshold = strategyThreshold(strategy);
            List<LiveOddsRecommendationDto> fresh = computeLiveOddsRecommendations(
                    strategy,
                    modelSelector,
                    LIVE_RECS_CACHE_ROW_LIMIT,
                    true,
                    threshold,
                    /* liteMode */ true);
            CachedRecs cached = new CachedRecs(
                    fresh == null ? List.of() : List.copyOf(fresh),
                    System.currentTimeMillis());
            cachedLiteRecs.put(key, cached);
            return viewForRequest(cached.rows(), take, includeUnresolved);
        } catch (RuntimeException ex) {
            log.warn("[odds-engine] synchronous lite compute failed: {}", ex.toString());
            return lite == null ? List.of() : viewForRequest(lite.rows(), take, includeUnresolved);
        }
    }

    private List<LiveOddsRecommendationDto> viewForRequest(List<LiveOddsRecommendationDto> rows,
                                                            int take,
                                                            boolean includeUnresolved) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> includeUnresolved
                        || (row.player1Id() != null && row.player2Id() != null))
                .limit(take)
                .toList();
    }

    /**
     * Submit a single per-key recommendation refresh task. If one is already
     * in flight for this key, return that future so the caller can piggyback
     * on it. Background tasks run on a dedicated daemon thread pool — they
     * deliberately do NOT share a Spring transaction with the caller, but
     * each child service (FeatureService, PredictionModelService, etc.) is
     * itself {@code @Transactional(readOnly = true)} at class level so the
     * downstream reads still run in their own short transactions.
     */
    private java.util.concurrent.Future<?> triggerRecsBackgroundRefresh(RecsKey key) {
        return recsInflight.compute(key, (k, existing) -> {
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            return recsRefreshExecutor.submit(() -> {
                try {
                    double threshold = strategyThreshold(k.strategy());
                    List<LiveOddsRecommendationDto> fresh = computeLiveOddsRecommendations(
                            k.strategy(),
                            k.modelSelector(),
                            LIVE_RECS_CACHE_ROW_LIMIT,
                            true,
                            threshold,
                            /* liteMode */ false);
                    cachedRecs.put(k, new CachedRecs(
                            fresh == null ? List.of() : List.copyOf(fresh),
                            System.currentTimeMillis()));
                    log.info("[odds-engine] full recs compute finished for {}; rows={}",
                            k, fresh == null ? 0 : fresh.size());
                } catch (RuntimeException ex) {
                    log.warn("[odds-engine] background recs refresh failed for {}: {}", k, ex.toString());
                }
            });
        });
    }

    /**
     * Heavy computation extracted from {@link #liveOddsRecommendations} so it
     * can be invoked from a background thread without dragging the caller's
     * (potentially nonexistent) Spring transaction along.
     */
    private List<LiveOddsRecommendationDto> computeLiveOddsRecommendations(String strategy,
                                                                            String modelSelector,
                                                                            int take,
                                                                            boolean includeUnresolved,
                                                                            double threshold,
                                                                            boolean liteMode) {
        List<MatchOdds> fetched = fetchWithCache();
        List<LiveOddsRecommendationDto> out = new ArrayList<>();
        LocalDateTime snapshotTime = LocalDateTime.now();
        LocalDate latestCompleted = matchRepository.findLastCompletedMatchDate();
        int freshnessDays = Math.max(1, Math.min(90, maxCompletedDataAgeDays));
        boolean modelDataFresh = latestCompleted != null
                && !latestCompleted.isBefore(LocalDate.now().minusDays(freshnessDays));

        for (MatchOdds odds : fetched) {
            try {
                if (odds.getOddsA() <= 1.0 || odds.getOddsB() <= 1.0) {
                    continue;
                }

                double breakEven1 = 1.0 / odds.getOddsA();
                double breakEven2 = 1.0 / odds.getOddsB();
                double bookImpliedTotal = breakEven1 + breakEven2;
                if (bookImpliedTotal <= 0.0) {
                    continue;
                }
                double noVigMarket1 = breakEven1 / bookImpliedTotal;
                double noVigMarket2 = breakEven2 / bookImpliedTotal;
                double bookMargin = bookImpliedTotal - 1.0;

                Optional<Player> p1Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerA());
                Optional<Player> p2Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerB());
                boolean resolved = p1Opt.isPresent() && p2Opt.isPresent() && !p1Opt.get().getId().equals(p2Opt.get().getId());

                if (!resolved && !includeUnresolved) {
                    continue;
                }

                String source = StringUtils.hasText(odds.getSource()) ? odds.getSource() : "HARD_ROCK";
                String eventName = StringUtils.hasText(odds.getEventName())
                        ? odds.getEventName()
                        : odds.getPlayerA() + " vs " + odds.getPlayerB();
                String competitionName = StringUtils.hasText(odds.getCompetitionName())
                        ? odds.getCompetitionName()
                        : "Table Tennis";
                String unresolvedMatchupKey = buildMatchupKey(
                        null,
                        odds.getPlayerA(),
                        null,
                        odds.getPlayerB(),
                        odds.getStartTimeIso()
                );

                if (!resolved) {
                    out.add(new LiveOddsRecommendationDto(
                            source,
                            strategy,
                            modelSelector,
                            eventName,
                            competitionName,
                            odds.isLive(),
                            odds.getStartTimeIso(),
                            odds.getLiveScore(),
                            StringUtils.hasText(odds.getMatchPhase()) ? odds.getMatchPhase() : (odds.isLive() ? "LIVE" : "UPCOMING"),
                            null,
                            odds.getPlayerA(),
                            null,
                            odds.getPlayerB(),
                            odds.getOddsA(),
                            odds.getOddsB(),
                            decimalToAmerican(odds.getOddsA()),
                            decimalToAmerican(odds.getOddsB()),
                            breakEven1,
                            breakEven2,
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
                            "Players could not be resolved to internal identities.",
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            unresolvedMatchupKey,
                            null,
                            odds.getSourceType(),
                            odds.getSourceConfidence(),
                            odds.getExternalEventId(),
                            odds.isDisplayed(),
                            odds.isResulted(),
                            odds.isMatchCompleted(),
                            odds.getSourceFeedCode(),
                            odds.getSourceFeedEventId(),
                            odds.getScoreDetail()
                    ));
                    continue;
                }

                Player p1 = p1Opt.get();
                Player p2 = p2Opt.get();
                String matchupKey = buildMatchupKey(
                        p1.getId(),
                        p1.getName(),
                        p2.getId(),
                        p2.getName(),
                        odds.getStartTimeIso()
                );
                if (liteMode) {
                    // Fast path: skip predict() entirely. Row carries player IDs,
                    // odds, and implied probabilities — enough for the UI to
                    // render the board. The background full-prediction compute
                    // will replace this row on the next poll.
                    out.add(new LiveOddsRecommendationDto(
                            source,
                            strategy,
                            modelSelector,
                            eventName,
                            competitionName,
                            odds.isLive(),
                            odds.getStartTimeIso(),
                            odds.getLiveScore(),
                            StringUtils.hasText(odds.getMatchPhase()) ? odds.getMatchPhase() : (odds.isLive() ? "LIVE" : "UPCOMING"),
                            p1.getId(),
                            p1.getName(),
                            p2.getId(),
                            p2.getName(),
                            odds.getOddsA(),
                            odds.getOddsB(),
                            decimalToAmerican(odds.getOddsA()),
                            decimalToAmerican(odds.getOddsB()),
                            breakEven1,
                            breakEven2,
                            null, null,           // modelProbabilityPlayer1/2
                            null, null,           // edgePlayer1/2
                            null, null,           // modelFairAmericanOddsPlayer1/2
                            null,                 // suggestedSide
                            null,                 // suggestedEdge
                            null,                 // suggestedFairAmericanOdds
                            null, null,           // confidenceLow/High
                            false,                // recommended
                            "PENDING",            // grade
                            "Loading model predictions…",
                            null, null,           // topTrigger, topTriggerContribution
                            null, null, null, null, // overallReliability, ratingAgreement, topTriggerReliability, baselineStability
                            matchupKey,
                            null,                 // suggestedDedupeKey
                            odds.getSourceType(),
                            odds.getSourceConfidence(),
                            odds.getExternalEventId(),
                            odds.isDisplayed(),
                            odds.isResulted(),
                            odds.isMatchCompleted(),
                            odds.getSourceFeedCode(),
                            odds.getSourceFeedEventId(),
                            odds.getScoreDetail()
                    ));
                    continue;
                }
                PredictionModelService.PredictionSnapshot prediction = predictionFacade.predict(
                        p1.getId(),
                        p2.getId(),
                        LocalDate.now(),
                        modelSelector
                );
                MatchupFeatureVectorDto featureVector = prediction.featureVector();

                LiveAdjustedProbability adjusted = applyLiveContext(
                        prediction.player1Probability(),
                        prediction.player2Probability(),
                        prediction.player1ConfidenceLow(),
                        prediction.player1ConfidenceHigh(),
                        odds
                );
                PredictionModelService.AdaptiveRegimeTuning p1RegimeTuning = predictionFacade.currentAdaptiveRegimeTuning(
                        odds.isLive(),
                        odds.getMatchPhase(),
                        odds.isLive() ? 0.5 : noVigMarket1
                );
                PredictionModelService.AdaptiveRegimeTuning p2RegimeTuning = predictionFacade.currentAdaptiveRegimeTuning(
                        odds.isLive(),
                        odds.getMatchPhase(),
                        odds.isLive() ? 0.5 : noVigMarket2
                );
                if (!odds.isLive()) {
                    adjusted = applyRegimeTuning(adjusted, p1RegimeTuning, p2RegimeTuning);
                }

                double edge1 = adjusted.player1Probability() - breakEven1;
                double edge2 = adjusted.player2Probability() - breakEven2;
                boolean p1ConfidenceOk = adjusted.player1ConfidenceLow() > breakEven1;
                boolean p2ConfidenceOk = adjusted.player2ConfidenceLow() > breakEven2;
                boolean pickPlayer1 = edge1 >= edge2;

                String suggestedSide = pickPlayer1 ? p1.getName() : p2.getName();
                double suggestedEdge = pickPlayer1 ? edge1 : edge2;
                double confidenceLow = pickPlayer1 ? adjusted.player1ConfidenceLow() : adjusted.player2ConfidenceLow();
                double confidenceHigh = pickPlayer1 ? adjusted.player1ConfidenceHigh() : adjusted.player2ConfidenceHigh();
                boolean confidenceOk = pickPlayer1 ? p1ConfidenceOk : p2ConfidenceOk;
                boolean promotedModel = predictionFacade.isPromotedModel(
                        prediction.modelFamily(),
                        prediction.modelVersion()
                );
                int suggestedAmericanOdds = pickPlayer1 ? decimalToAmerican(odds.getOddsA()) : decimalToAmerican(odds.getOddsB());
                boolean longshotRisk = suggestedAmericanOdds > Math.abs(maxRecommendedAmericanOdds);
                boolean recommended = suggestedEdge >= threshold
                        && confidenceOk
                        && !longshotRisk
                        && modelDataFresh
                        && promotedModel
                        && (!odds.isLive() || liveScoreModelValidated);
                String topTrigger = null;
                Double topTriggerContribution = null;
                if (prediction.featureContributions() != null && !prediction.featureContributions().isEmpty()) {
                    MatchupAnalysisDto.FeatureContributionDto top = prediction.featureContributions().get(0);
                    topTrigger = top.feature();
                    topTriggerContribution = top.contribution();
                }
                Double overallReliability = extractOverallReliability(featureVector);
                Double ratingAgreement = extractRatingAgreement(featureVector);
                Double topTriggerReliability = extractTopTriggerReliability(featureVector, topTrigger);
                Double suggestedSideBaselineStability = extractSuggestedSideBaselineStability(featureVector, pickPlayer1);

                String grade = gradeFor(suggestedEdge, confidenceLow, confidenceHigh, recommended);
                String rationale = buildRationale(
                        suggestedSide,
                        suggestedEdge,
                        threshold,
                        confidenceLow,
                        confidenceHigh,
                        recommended,
                        longshotRisk,
                        overallReliability,
                        ratingAgreement,
                        topTrigger,
                        topTriggerReliability,
                        suggestedSideBaselineStability,
                        pickPlayer1 ? p1RegimeTuning : p2RegimeTuning
                );
                if (!modelDataFresh) {
                    rationale += " Recommendation paused: completed-match data is stale"
                            + (latestCompleted == null ? "." : " (latest " + latestCompleted + ").");
                }
                if (!promotedModel) {
                    rationale += " Recommendation paused: no candidate has passed the independent promotion gates.";
                }
                rationale += String.format(
                        " Fair odds are no-vig. Executable edge equals model probability minus the actual offered Hard Rock break-even probability, so its %.2f%% two-way margin is already embedded in the comparison.",
                        bookMargin * 100.0
                );
                if (odds.isLive()) {
                    rationale += " Score-conditioned table-tennis estimate.";
                    if (!liveScoreModelValidated) {
                        rationale += " Live recommendations remain watch-only until early/mid/late/deuce validation passes.";
                    }
                }

                out.add(new LiveOddsRecommendationDto(
                        source,
                        strategy,
                        prediction.modelVersion(),
                        eventName,
                        competitionName,
                        odds.isLive(),
                        odds.getStartTimeIso(),
                        odds.getLiveScore(),
                        StringUtils.hasText(odds.getMatchPhase()) ? odds.getMatchPhase() : (odds.isLive() ? "LIVE" : "UPCOMING"),
                        p1.getId(),
                        p1.getName(),
                        p2.getId(),
                        p2.getName(),
                        odds.getOddsA(),
                        odds.getOddsB(),
                        decimalToAmerican(odds.getOddsA()),
                        decimalToAmerican(odds.getOddsB()),
                        breakEven1,
                        breakEven2,
                        adjusted.player1Probability(),
                        adjusted.player2Probability(),
                        edge1,
                        edge2,
                        probabilityToAmerican(adjusted.player1Probability()),
                        probabilityToAmerican(adjusted.player2Probability()),
                        suggestedSide,
                        suggestedEdge,
                        probabilityToAmerican(pickPlayer1 ? adjusted.player1Probability() : adjusted.player2Probability()),
                        confidenceLow,
                        confidenceHigh,
                        recommended,
                        grade,
                        rationale,
                        topTrigger,
                        topTriggerContribution,
                        overallReliability,
                        ratingAgreement,
                        topTriggerReliability,
                        suggestedSideBaselineStability,
                        matchupKey,
                        buildSuggestedDedupeKey(matchupKey, suggestedSide),
                        odds.getSourceType(),
                        odds.getSourceConfidence(),
                        odds.getExternalEventId(),
                        odds.isDisplayed(),
                        odds.isResulted(),
                        odds.isMatchCompleted(),
                        odds.getSourceFeedCode(),
                        odds.getSourceFeedEventId(),
                        odds.getScoreDetail(),
                        prediction.featureContributions()
                ));
            } catch (Exception ex) {
                log.warn(
                        "[live-board] skipping matchup after row-level failure: event='{}' players='{}' vs '{}' source='{}'",
                        odds.getEventName(),
                        odds.getPlayerA(),
                        odds.getPlayerB(),
                        odds.getSource(),
                        ex
                );
            }
        }

        out.sort(Comparator
                .comparingInt((LiveOddsRecommendationDto row) -> row.live() ? 0 : 1)
                .thenComparing(row -> chronologicalStartToken(row.startTimeIso()))
                .thenComparingInt(row -> row.recommended() ? 0 : 1)
                .thenComparing((a, b) -> Double.compare(
                        Math.abs(valueOrZero(b.suggestedEdge())),
                        Math.abs(valueOrZero(a.suggestedEdge()))
                ))
                .thenComparing(row -> safeSortToken(row.eventName()), String.CASE_INSENSITIVE_ORDER));

        if (out.size() > take) {
            return out.subList(0, take);
        }
        return out;
    }

    public List<LiveScoreSnapshotDto> liveScoreSnapshots(int limit, boolean includeUnresolved) {
        int take = Math.max(1, Math.min(limit, 1600));
        // The sportsbook call is remote I/O. Player identity lookups below own
        // short repository transactions; an outer read transaction would pin a
        // pool connection for the entire network request.
        List<MatchOdds> marketRows = safeScoreboardFetch();
        List<MatchOdds> streamRows = hardRockScoreStreamClient == null
                ? List.of()
                : hardRockScoreStreamClient.snapshots();
        return toLiveScoreSnapshots(mergeScoreRows(marketRows, streamRows), take, includeUnresolved);
    }

    public List<LiveScoreSnapshotDto> liveScoreSnapshotsForEventIds(Collection<String> externalEventIds,
                                                                    int limit,
                                                                    boolean includeUnresolved) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (externalEventIds != null) {
            for (String raw : externalEventIds) {
                if (!StringUtils.hasText(raw)) {
                    continue;
                }
                String cleaned = raw.trim().replaceAll("[^A-Za-z0-9:_-]", "");
                if (StringUtils.hasText(cleaned)) {
                    normalized.add(cleaned);
                }
            }
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        int take = Math.max(1, Math.min(limit, 1600));
        List<MatchOdds> streamRows = List.of();
        if (hardRockScoreStreamClient != null) {
            hardRockScoreStreamClient.trackEventIds(normalized);
            streamRows = hardRockScoreStreamClient.snapshotsForEventIds(normalized);
        }
        Set<String> covered = streamRows.stream()
                .map(MatchOdds::getExternalEventId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> missing = new LinkedHashSet<>(normalized);
        missing.removeAll(covered);
        List<MatchOdds> marketRows = missing.isEmpty()
                ? List.of()
                : safeTargetedScoreboardFetch(missing);
        return toLiveScoreSnapshots(mergeScoreRows(marketRows, streamRows), take, includeUnresolved);
    }

    private List<MatchOdds> safeScoreboardFetch() {
        try {
            List<MatchOdds> rows = hardRockOddsScraper.fetchScoreboard();
            return rows == null ? List.of() : rows;
        } catch (RuntimeException ex) {
            log.warn("[scoreboard] market-backed score pull failed; retaining subscription snapshots", ex);
            return List.of();
        }
    }

    private List<MatchOdds> safeTargetedScoreboardFetch(Collection<String> eventIds) {
        try {
            List<MatchOdds> rows = hardRockOddsScraper.fetchScoreboardByEventIds(eventIds);
            return rows == null ? List.of() : rows;
        } catch (RuntimeException ex) {
            log.warn("[scoreboard] targeted market-backed score pull failed; retaining subscription snapshots", ex);
            return List.of();
        }
    }

    /**
     * One event may exist in both transports. The subscription wins when it
     * has terminal state, a richer score, or greater source confidence; this
     * prevents a newly-created but scoreless market row from masking the
     * final update retained by the score stream.
     */
    private List<MatchOdds> mergeScoreRows(List<MatchOdds> marketRows, List<MatchOdds> streamRows) {
        Map<String, MatchOdds> byEvent = new LinkedHashMap<>();
        addScoreRows(byEvent, marketRows);
        addScoreRows(byEvent, streamRows);
        return List.copyOf(byEvent.values());
    }

    private void addScoreRows(Map<String, MatchOdds> byEvent, List<MatchOdds> rows) {
        if (rows == null) return;
        for (MatchOdds row : rows) {
            if (row == null) continue;
            String key = StringUtils.hasText(row.getExternalEventId())
                    ? "id:" + row.getExternalEventId().trim()
                    : "match:" + safeSortToken(row.getPlayerA()) + "|" + safeSortToken(row.getPlayerB())
                    + "|" + safeSortToken(row.getStartTimeIso());
            byEvent.merge(key, row, this::preferScoreRow);
        }
    }

    private MatchOdds preferScoreRow(MatchOdds left, MatchOdds right) {
        if (isTerminal(right) != isTerminal(left)) return isTerminal(right) ? right : left;
        int leftScoreQuality = scoreQuality(left);
        int rightScoreQuality = scoreQuality(right);
        if (rightScoreQuality != leftScoreQuality) return rightScoreQuality > leftScoreQuality ? right : left;
        if (Double.compare(right.getSourceConfidence(), left.getSourceConfidence()) != 0) {
            return right.getSourceConfidence() > left.getSourceConfidence() ? right : left;
        }
        return right.getTimestamp() >= left.getTimestamp() ? right : left;
    }

    private static boolean isTerminal(MatchOdds row) {
        return row != null && (row.isResulted() || row.isMatchCompleted()
                || "FINISHED".equalsIgnoreCase(row.getMatchPhase()));
    }

    private static int scoreQuality(MatchOdds row) {
        if (row == null) return 0;
        int quality = StringUtils.hasText(row.getLiveScore()) ? 1 : 0;
        if (StringUtils.hasText(row.getScoreDetail())) quality++;
        return quality;
    }

    private List<LiveScoreSnapshotDto> toLiveScoreSnapshots(List<MatchOdds> fetched,
                                                            int take,
                                                            boolean includeUnresolved) {
        if (fetched == null || fetched.isEmpty()) {
            return List.of();
        }

        List<LiveScoreSnapshotDto> out = new ArrayList<>();
        for (MatchOdds odds : fetched) {
            if (odds == null || !StringUtils.hasText(odds.getPlayerA()) || !StringUtils.hasText(odds.getPlayerB())) {
                continue;
            }

            Optional<Player> p1Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerA());
            Optional<Player> p2Opt = playerIdentityService.findCanonicalPlayer(odds.getPlayerB());
            Long p1Id = p1Opt.map(Player::getId).orElse(null);
            Long p2Id = p2Opt.map(Player::getId).orElse(null);
            if (p1Id != null && p2Id != null && p1Id.equals(p2Id)) {
                continue;
            }

            boolean resolved = p1Id != null && p2Id != null;
            if (!resolved && !includeUnresolved) {
                continue;
            }

            String p1Name = resolved
                    ? p1Opt.map(Player::getName).orElse(odds.getPlayerA())
                    : odds.getPlayerA();
            String p2Name = resolved
                    ? p2Opt.map(Player::getName).orElse(odds.getPlayerB())
                    : odds.getPlayerB();

            String source = StringUtils.hasText(odds.getSource()) ? odds.getSource() : "HARD_ROCK_SCORE";
            String eventName = StringUtils.hasText(odds.getEventName())
                    ? odds.getEventName()
                    : odds.getPlayerA() + " vs " + odds.getPlayerB();
            String competitionName = StringUtils.hasText(odds.getCompetitionName())
                    ? odds.getCompetitionName()
                    : "Table Tennis";
            String matchupKey = buildMatchupKey(
                    p1Id,
                    p1Name,
                    p2Id,
                    p2Name,
                    odds.getStartTimeIso()
            );
            String phase = StringUtils.hasText(odds.getMatchPhase())
                    ? odds.getMatchPhase()
                    : (odds.isLive() ? "LIVE" : "UPCOMING");

            out.add(new LiveScoreSnapshotDto(
                    source,
                    StringUtils.hasText(odds.getSourceType()) ? odds.getSourceType() : "UNKNOWN",
                    odds.getSourceConfidence(),
                    Math.max(0L, (System.currentTimeMillis() - odds.getTimestamp()) / 1000L),
                    eventName,
                    competitionName,
                    odds.isLive(),
                    odds.getStartTimeIso(),
                    odds.getLiveScore(),
                    phase,
                    odds.getExternalEventId(),
                    odds.isDisplayed(),
                    odds.isResulted(),
                    odds.isMatchCompleted(),
                    odds.getSourceFeedCode(),
                    odds.getSourceFeedEventId(),
                    odds.getScoreDetail(),
                    p1Id,
                    p1Name,
                    p2Id,
                    p2Name,
                    matchupKey
            ));
        }

        out.sort(Comparator
                .comparingInt((LiveScoreSnapshotDto row) -> row.live() ? 0 : 1)
                .thenComparing(row -> chronologicalStartToken(row.startTimeIso()))
                .thenComparingInt(row -> StringUtils.hasText(row.liveScore()) ? 0 : 1)
                .thenComparing(LiveScoreSnapshotDto::sourceConfidence, Comparator.reverseOrder())
                .thenComparing(LiveScoreSnapshotDto::sourceAgeSeconds)
                .thenComparing(row -> safeSortToken(row.eventName()), String.CASE_INSENSITIVE_ORDER));

        if (out.size() > take) {
            return out.subList(0, take);
        }
        return out;
    }

    private OddsQuote persistQuote(MatchOdds odds, String source, LocalDateTime scrapedAt) {
        OddsQuote quote = new OddsQuote();
        quote.setSource(StringUtils.hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : "UNKNOWN");
        quote.setPlayer1Display(odds.getPlayerA());
        quote.setPlayer2Display(odds.getPlayerB());
        quote.setPlayer1Normalized(NameUtils.normalizeForLookup(odds.getPlayerA()));
        quote.setPlayer2Normalized(NameUtils.normalizeForLookup(odds.getPlayerB()));
        quote.setEventName(odds.getEventName());
        quote.setCompetitionName(odds.getCompetitionName());
        quote.setLiveAtQuote(odds.isLive());
        quote.setStartTimeIso(odds.getStartTimeIso());
        quote.setLiveScore(odds.getLiveScore());
        quote.setMatchPhase(odds.getMatchPhase());
        quote.setQuoteTimestampMs(odds.getTimestamp());
        quote.setDecimalOddsPlayer1(odds.getOddsA());
        quote.setDecimalOddsPlayer2(odds.getOddsB());
        quote.setAmericanOddsPlayer1(decimalToAmerican(odds.getOddsA()));
        quote.setAmericanOddsPlayer2(decimalToAmerican(odds.getOddsB()));
        quote.setScrapedAt(scrapedAt == null ? LocalDateTime.now() : scrapedAt);
        return oddsQuoteRepository.save(quote);
    }

    private int maybePersistOpportunity(String strategy,
                                        String modelVersion,
                                        double threshold,
                                        String source,
                                        Player p1,
                                        Player p2,
                                        Player side,
                                        double probability,
                                        double confidenceLow,
                                        double confidenceHigh,
                                        double impliedProbability,
                                        int americanOdds,
                                        LocalDateTime createdAt) {
        double edge = probability - impliedProbability;
        boolean strongEnough = edge >= threshold && confidenceLow > impliedProbability;
        if (!strongEnough) {
            return 0;
        }

        ValueOpportunity opp = new ValueOpportunity();
        opp.setSource(source);
        opp.setStrategy(strategy);
        opp.setModelVersion(StringUtils.hasText(modelVersion) ? modelVersion : "unknown");
        opp.setPlayer1Id(p1.getId());
        opp.setPlayer2Id(p2.getId());
        opp.setPlayerSideId(side.getId());
        opp.setPlayerSideName(side.getName());
        opp.setModelProbability(probability);
        opp.setConfidenceLow(confidenceLow);
        opp.setConfidenceHigh(confidenceHigh);
        opp.setImpliedProbability(impliedProbability);
        opp.setEdge(edge);
        opp.setThreshold(threshold);
        opp.setAmericanOdds(americanOdds);
        opp.setCreatedAt(createdAt);
        valueOpportunityRepository.save(opp);
        return 1;
    }

    /**
     * Returns the latest cached {@link MatchOdds} scrape, ALWAYS without
     * blocking. Behaviour:
     * <ul>
     *   <li>Cache fresh ({@code &lt; LIVE_SCRAPE_CACHE_TTL_MS}) → returns immediately.</li>
     *   <li>Cache stale → returns the stale rows and triggers a single
     *       background refresh (stale-while-revalidate).</li>
     *   <li>Cache miss → returns an empty list and triggers a background
     *       refresh. The first board paint may be empty for a moment; the
     *       next refresh tick fills it. Critically, the request never hangs.</li>
     * </ul>
     * <p>The original code synchronously called {@code hardRockOddsScraper.fetch()}
     * on every request — when the upstream feed slowed down, the live board
     * surface 500'd at 30 s. This cache turns a "30 s 500" failure into a
     * "first paint shows nothing, second paint shows data" UX.
     */
    private List<MatchOdds> fetchWithCache() {
        CachedScrape current = cachedScrape.get();
        if (current != null
                && (System.currentTimeMillis() - current.capturedAtMillis()) < LIVE_SCRAPE_CACHE_TTL_MS) {
            return current.rows();
        }
        // Cache stale or empty — start (or piggyback on) a background refresh.
        java.util.concurrent.Future<?> refresh = triggerBackgroundRefresh();
        if (current != null) {
            // Stale-while-revalidate: serve the previous rows immediately.
            return current.rows();
        }
        // Cache miss (first request after boot or after a clear). Wait up to
        // LIVE_SCRAPE_FIRST_HIT_BUDGET_MS for the refresh; if the upstream
        // scraper is slow, return [] and let the background task fill the
        // cache for the next request.
        if (refresh != null) {
            try {
                refresh.get(LIVE_SCRAPE_FIRST_HIT_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ex) {
                log.info("[odds-engine] first-hit scrape exceeded {} ms budget; serving empty rows while refresh continues",
                        LIVE_SCRAPE_FIRST_HIT_BUDGET_MS);
            } catch (java.util.concurrent.ExecutionException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("[odds-engine] first-hit scrape failed: {}", ex.toString());
            }
        }
        CachedScrape afterWait = cachedScrape.get();
        return afterWait == null ? List.of() : afterWait.rows();
    }

    /**
     * Package-private hook for tests: drop the cached scrape so the next
     * {@link #liveOddsRecommendations} call goes through the (mocked) scraper.
     * Production code must not call this — it'd defeat the cache's purpose.
     */
    void clearScrapeCacheForTest() {
        cachedScrape.set(null);
        scrapeInflight.set(false);
        cachedRecs.clear();
        cachedLiteRecs.clear();
        recsInflight.clear();
    }

    /**
     * Submit a single refresh task to the daemon executor (idempotent — if
     * one is already in flight, returns null and lets the existing one
     * proceed). Returned Future lets the calling thread optionally wait up
     * to a budgeted timeout for the result.
     */
    private java.util.concurrent.Future<?> triggerBackgroundRefresh() {
        if (!scrapeInflight.compareAndSet(false, true)) {
            return null;
        }
        return scrapeRefreshExecutor.submit(() -> {
            try {
                List<MatchOdds> fresh = pullHardRockMarket("odds-engine-live-board");
                cachedScrape.set(new CachedScrape(
                        fresh == null ? List.of() : List.copyOf(fresh),
                        System.currentTimeMillis()));
            } catch (RuntimeException ex) {
                log.warn("[odds-engine] background scrape refresh failed; keeping stale cache: {}", ex.toString());
            } finally {
                scrapeInflight.set(false);
            }
        });
    }

    private List<MatchOdds> pullHardRockMarket(String correlationId) {
        HardRockFeedClient feedClient = hardRockFeedClient;
        if (feedClient == null) {
            return hardRockOddsScraper.fetch();
        }
        return feedClient.pullOnce(FeedClient.PullContext.now(correlationId)).stream()
                .map(event -> event.payload())
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String normalizeStrategy(String strategyRaw) {
        if (!StringUtils.hasText(strategyRaw)) {
            return STRATEGY_CONSERVATIVE;
        }
        String s = strategyRaw.trim().toUpperCase(Locale.ROOT);
        if (STRATEGY_AGGRESSIVE.equals(s)) {
            return STRATEGY_AGGRESSIVE;
        }
        return STRATEGY_CONSERVATIVE;
    }

    private double strategyThreshold(String strategy) {
        if (STRATEGY_AGGRESSIVE.equalsIgnoreCase(strategy)) {
            return clamp(aggressiveThreshold, 0.005, 0.2);
        }
        return clamp(conservativeThreshold, 0.01, 0.3);
    }

    private int decimalToAmerican(double decimalOdds) {
        if (decimalOdds <= 1.0) return 0;
        if (decimalOdds >= 2.0) {
            return (int) Math.round((decimalOdds - 1.0) * 100.0);
        }
        return (int) Math.round(-100.0 / (decimalOdds - 1.0));
    }

    private String gradeFor(double edge,
                            double confidenceLow,
                            double confidenceHigh,
                            boolean recommended) {
        if (!recommended) {
            return "WATCH";
        }
        double ciWidth = Math.max(0.0, confidenceHigh - confidenceLow);
        double score = edge * 100.0;
        score += recommended ? 3.0 : 0.0;
        score += Math.max(0.0, (0.25 - ciWidth) * 10.0);

        if (score >= 12.0) return "A";
        if (score >= 8.0) return "B";
        if (score >= 5.0) return "C";
        if (score >= 2.0) return "D";
        return "F";
    }

    private String buildRationale(String side,
                                  double edge,
                                  double threshold,
                                  double confidenceLow,
                                  double confidenceHigh,
                                  boolean recommended,
                                  boolean longshotRisk,
                                  Double overallReliability,
                                  Double ratingAgreement,
                                  String topTrigger,
                                  Double topTriggerReliability,
                                  Double suggestedSideBaselineStability,
                                  PredictionModelService.AdaptiveRegimeTuning regimeTuning) {
        String verdict = recommended ? "Recommended" : "Watchlist";
        String risk = longshotRisk ? " Longshot guardrail triggered." : "";
        String reliability = buildReliabilityNote(
                overallReliability,
                ratingAgreement,
                topTrigger,
                topTriggerReliability,
                suggestedSideBaselineStability
        );
        String regimeNote = buildRegimeNote(regimeTuning);
        return String.format(
                "%s: %s executable edge %.2f%% vs threshold %.2f%%, confidence range %.1f%%-%.1f%%.%s%s%s",
                verdict,
                side,
                edge * 100.0,
                threshold * 100.0,
                confidenceLow * 100.0,
                confidenceHigh * 100.0,
                risk,
                reliability,
                regimeNote
        );
    }

    private String buildRegimeNote(PredictionModelService.AdaptiveRegimeTuning regimeTuning) {
        if (regimeTuning == null || regimeTuning.reliability() < 0.05) {
            return "";
        }
        return String.format(
                " Regime tuning: %s reliability %.0f%%, scale %.2f, CI %+,.2f%%.",
                regimeTuning.label(),
                regimeTuning.reliability() * 100.0,
                regimeTuning.confidenceScale(),
                regimeTuning.ciBoost() * 100.0
        );
    }

    private String buildReliabilityNote(Double overallReliability,
                                        Double ratingAgreement,
                                        String topTrigger,
                                        Double topTriggerReliability,
                                        Double suggestedSideBaselineStability) {
        List<String> parts = new ArrayList<>();
        if (overallReliability != null) {
            parts.add(String.format("overall %s (%.0f%%)", reliabilityBand(overallReliability), overallReliability * 100.0));
        }
        if (StringUtils.hasText(topTrigger) && topTriggerReliability != null) {
            parts.add(String.format("%s signal %s (%.0f%%)", topTrigger, reliabilityBand(topTriggerReliability), topTriggerReliability * 100.0));
        }
        if (suggestedSideBaselineStability != null) {
            parts.add(String.format("baseline %s (%.0f%%)", reliabilityBand(suggestedSideBaselineStability), suggestedSideBaselineStability * 100.0));
        }
        if (ratingAgreement != null) {
            parts.add(String.format("model agreement %s (%.0f%%)", reliabilityBand(ratingAgreement), ratingAgreement * 100.0));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return " Reliability: " + String.join(", ", parts) + ".";
    }

    private Double extractOverallReliability(MatchupFeatureVectorDto featureVector) {
        if (featureVector == null || featureVector.reliabilitySummary() == null) {
            return null;
        }
        return clamp(featureVector.reliabilitySummary().overallReliability(), 0.0, 1.0);
    }

    private Double extractRatingAgreement(MatchupFeatureVectorDto featureVector) {
        if (featureVector == null || featureVector.reliabilitySummary() == null) {
            return null;
        }
        return clamp(featureVector.reliabilitySummary().ratingAgreement(), 0.0, 1.0);
    }

    private Double extractSuggestedSideBaselineStability(MatchupFeatureVectorDto featureVector, boolean pickPlayer1) {
        if (featureVector == null) {
            return null;
        }
        MatchupFeatureVectorDto.PlayerFeatureDto player = pickPlayer1 ? featureVector.player1() : featureVector.player2();
        if (player == null) {
            return null;
        }
        return clamp(player.ratingStability(), 0.0, 1.0);
    }

    private Double extractTopTriggerReliability(MatchupFeatureVectorDto featureVector, String topTrigger) {
        if (featureVector == null
                || featureVector.reliabilitySummary() == null
                || !StringUtils.hasText(topTrigger)) {
            return null;
        }
        MatchupFeatureVectorDto.PlayerFeatureDto p1 = featureVector.player1();
        MatchupFeatureVectorDto.PlayerFeatureDto p2 = featureVector.player2();
        return switch (topTrigger.trim().toUpperCase(Locale.ROOT)) {
            case "HEAD-TO-HEAD (DECAYED)" -> clamp(featureVector.headToHeadReliability(), 0.0, 1.0);
            case "RECENT FORM DELTA", "P1 RECENT FORM", "P2 RECENT FORM" -> {
                double left = p1 == null ? 0.0 : clamp(p1.recentFormReliability(), 0.0, 1.0);
                double right = p2 == null ? 0.0 : clamp(p2.recentFormReliability(), 0.0, 1.0);
                yield clamp((left + right) / 2.0, 0.0, 1.0);
            }
            case "OPPONENT-ADJUSTED FORM DELTA" -> {
                double left = p1 == null ? 0.0 : clamp(p1.opponentAdjustedReliability(), 0.0, 1.0);
                double right = p2 == null ? 0.0 : clamp(p2.opponentAdjustedReliability(), 0.0, 1.0);
                yield clamp((left + right) / 2.0, 0.0, 1.0);
            }
            case "SCHEDULE STRENGTH DELTA" -> {
                double left = p1 == null ? 0.0 : clamp(p1.scheduleStrengthReliability(), 0.0, 1.0);
                double right = p2 == null ? 0.0 : clamp(p2.scheduleStrengthReliability(), 0.0, 1.0);
                yield clamp((left + right) / 2.0, 0.0, 1.0);
            }
            case "ELO PROBABILITY DELTA",
                 "GLICKO PROBABILITY DELTA",
                 "GLICKO RATING DELTA",
                 "GLICKO RD ADVANTAGE",
                 "VOLATILITY ADVANTAGE",
                 "FORM × H2H INTERACTION" -> clamp(featureVector.reliabilitySummary().ratingAgreement(), 0.0, 1.0);
            default -> clamp(featureVector.reliabilitySummary().overallReliability(), 0.0, 1.0);
        };
    }

    private String reliabilityBand(double value) {
        if (value >= 0.78) return "strong";
        if (value >= 0.6) return "solid";
        if (value >= 0.42) return "mixed";
        return "thin";
    }

    private LiveAdjustedProbability applyLiveContext(double baseP1,
                                                     double baseP2,
                                                     double p1Low,
                                                     double p1High,
                                                     MatchOdds odds) {
        double p1 = clamp(baseP1, 0.01, 0.99);
        double p2 = clamp(baseP2, 0.01, 0.99);
        double low = clamp(p1Low, 0.01, 0.99);
        double high = clamp(p1High, 0.01, 0.99);

        if (odds != null && odds.isLive()) {
            ScoreContext scoreContext = parseScoreContext(odds.getLiveScore());
            if (scoreContext != null) {
                p1 = TableTennisLiveProbability.estimate(
                        p1,
                        scoreContext.setsP1,
                        scoreContext.setsP2,
                        scoreContext.pointsP1,
                        scoreContext.pointsP2
                ).player1MatchProbability();
                low = TableTennisLiveProbability.estimate(
                        low,
                        scoreContext.setsP1,
                        scoreContext.setsP2,
                        scoreContext.pointsP1,
                        scoreContext.pointsP2
                ).player1MatchProbability();
                high = TableTennisLiveProbability.estimate(
                        high,
                        scoreContext.setsP1,
                        scoreContext.setsP2,
                        scoreContext.pointsP1,
                        scoreContext.pointsP2
                ).player1MatchProbability();
            } else {
                p1 = 0.5 + ((p1 - 0.5) * 0.90);
                double spread = Math.max(0.04, (high - low) * 0.60);
                low = clamp(p1 - spread, 0.01, 0.99);
                high = clamp(p1 + spread, 0.01, 0.99);
            }
        }

        p1 = clamp(p1, 0.01, 0.99);
        p2 = clamp(1.0 - p1, 0.01, 0.99);
        low = clamp(low, 0.01, 0.99);
        high = clamp(high, 0.01, 0.99);
        if (high < low) {
            double tmp = low;
            low = high;
            high = tmp;
        }
        double p2Low = clamp(1.0 - high, 0.01, 0.99);
        double p2High = clamp(1.0 - low, 0.01, 0.99);
        return new LiveAdjustedProbability(p1, p2, low, high, p2Low, p2High);
    }

    private LiveAdjustedProbability applyRegimeTuning(LiveAdjustedProbability base,
                                                      PredictionModelService.AdaptiveRegimeTuning p1Tuning,
                                                      PredictionModelService.AdaptiveRegimeTuning p2Tuning) {
        if (base == null) {
            return null;
        }
        PredictionModelService.AdaptiveRegimeTuning left = p1Tuning == null
                ? PredictionModelService.AdaptiveRegimeTuning.neutral("All Settled")
                : p1Tuning;
        PredictionModelService.AdaptiveRegimeTuning right = p2Tuning == null
                ? PredictionModelService.AdaptiveRegimeTuning.neutral("All Settled")
                : p2Tuning;

        double tunedP1 = 0.5 + ((base.player1Probability() - 0.5) * left.confidenceScale());
        double tunedP2 = 0.5 + ((base.player2Probability() - 0.5) * right.confidenceScale());
        tunedP1 = clamp(tunedP1, 0.01, 0.99);
        tunedP2 = clamp(tunedP2, 0.01, 0.99);
        double total = tunedP1 + tunedP2;
        if (total > 0.0) {
            tunedP1 = clamp(tunedP1 / total, 0.01, 0.99);
            tunedP2 = clamp(1.0 - tunedP1, 0.01, 0.99);
        } else {
            tunedP1 = base.player1Probability();
            tunedP2 = base.player2Probability();
        }

        double avgCiBoost = clamp((left.ciBoost() + right.ciBoost()) / 2.0, 0.0, 0.12);
        double spread = ((base.player1ConfidenceHigh() - base.player1ConfidenceLow()) / 2.0) + avgCiBoost;
        spread = clamp(spread, 0.03, 0.45);
        double low = clamp(tunedP1 - spread, 0.01, 0.99);
        double high = clamp(tunedP1 + spread, 0.01, 0.99);
        double p2Low = clamp(1.0 - high, 0.01, 0.99);
        double p2High = clamp(1.0 - low, 0.01, 0.99);
        return new LiveAdjustedProbability(tunedP1, tunedP2, low, high, p2Low, p2High);
    }

    private int probabilityToAmerican(double probability) {
        double p = clamp(probability, 0.01, 0.99);
        if (p >= 0.5) {
            return (int) Math.round(-(100.0 * p) / Math.max(0.001, (1.0 - p)));
        }
        return (int) Math.round((100.0 * (1.0 - p)) / Math.max(0.001, p));
    }

    private ScoreContext parseScoreContext(String score) {
        if (!StringUtils.hasText(score)) {
            return null;
        }
        Matcher matcher = SCORE_PAIR_PATTERN.matcher(score);
        List<int[]> pairs = new ArrayList<>();
        while (matcher.find()) {
            try {
                pairs.add(new int[]{
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))
                });
            } catch (Exception ignore) {
                // keep scanning
            }
        }
        if (pairs.isEmpty()) {
            return null;
        }

        Integer setsP1 = null;
        Integer setsP2 = null;
        Integer pointsP1 = null;
        Integer pointsP2 = null;

        int[] first = pairs.get(0);
        if (isLikelySetScore(first[0], first[1])) {
            setsP1 = first[0];
            setsP2 = first[1];
        } else if (isLikelyPointScore(first[0], first[1])) {
            pointsP1 = first[0];
            pointsP2 = first[1];
        }

        for (int i = 1; i < pairs.size(); i++) {
            int[] pair = pairs.get(i);
            if (pointsP1 == null && isPlausiblePointScore(pair[0], pair[1])) {
                pointsP1 = pair[0];
                pointsP2 = pair[1];
                continue;
            }
            if (setsP1 == null && isLikelySetScore(pair[0], pair[1])) {
                setsP1 = pair[0];
                setsP2 = pair[1];
            }
        }

        if (setsP1 == null && pointsP1 == null) {
            int[] last = pairs.get(pairs.size() - 1);
            if (isLikelySetScore(last[0], last[1])) {
                setsP1 = last[0];
                setsP2 = last[1];
            } else {
                pointsP1 = last[0];
                pointsP2 = last[1];
            }
        }
        return new ScoreContext(setsP1, setsP2, pointsP1, pointsP2);
    }

    private boolean isLikelySetScore(int left, int right) {
        return Math.max(left, right) <= 3;
    }

    private boolean isLikelyPointScore(int left, int right) {
        return Math.max(left, right) >= 8;
    }

    private boolean isPlausiblePointScore(int left, int right) {
        return left >= 0 && right >= 0 && Math.max(left, right) <= 99;
    }

    private String buildMatchupKey(Long player1Id,
                                   String player1Name,
                                   Long player2Id,
                                   String player2Name,
                                   String startTimeIso) {
        String p1 = player1Id == null ? normalizeToken(player1Name) : "id-" + player1Id;
        String p2 = player2Id == null ? normalizeToken(player2Name) : "id-" + player2Id;
        if (p1.compareTo(p2) > 0) {
            String tmp = p1;
            p1 = p2;
            p2 = tmp;
        }
        return p1 + "|" + p2 + "|" + startBucket(startTimeIso);
    }

    private String buildSuggestedDedupeKey(String matchupKey, String suggestedSide) {
        if (!StringUtils.hasText(matchupKey) || !StringUtils.hasText(suggestedSide)) {
            return null;
        }
        return matchupKey + "|" + normalizeToken(suggestedSide);
    }

    private String startBucket(String startTimeIso) {
        if (!StringUtils.hasText(startTimeIso)) {
            return LocalDate.now().toString();
        }
        String v = startTimeIso.trim();
        if (v.length() >= 16 && v.charAt(4) == '-' && v.charAt(7) == '-' && (v.charAt(10) == 'T' || v.charAt(10) == 't')) {
            return normalizeToken(v.substring(0, 16));
        }
        if (v.length() >= 10 && v.charAt(4) == '-' && v.charAt(7) == '-') {
            return v.substring(0, 10);
        }
        return normalizeToken(v);
    }

    private String normalizeToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "na";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static double valueOrZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private String safeSortToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim();
    }

    private String chronologicalStartToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "\uffff";
        }
        return value.trim();
    }

    private static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    private record LiveAdjustedProbability(double player1Probability,
                                           double player2Probability,
                                           double player1ConfidenceLow,
                                           double player1ConfidenceHigh,
                                           double player2ConfidenceLow,
                                           double player2ConfidenceHigh) {
    }

    private record ScoreContext(Integer setsP1,
                                Integer setsP2,
                                Integer pointsP1,
                                Integer pointsP2) {
    }
}
