package com.ttl.tabletennis.service;

import com.ttl.tabletennis.scrape.ScrapeCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Listens for {@link ScrapeCompletedEvent} and rebuilds every rating
 * family (Elo, TrueSkill-2, Weng-Lin, Glicko-2) so predictions reflect
 * the fresh match rows without waiting for the scheduled cron.
 *
 * <p>Disabled by setting {@code ttl.ratings.autoRebuildOnScrape.enabled=false}.
 *
 * <p>An optional debounce window can prevent a burst of scrapes from
 * thrashing the rating math. It defaults to zero so every completed mutation
 * batch is incorporated immediately; operators may opt into a delay through
 * {@code ttl.ratings.autoRebuildOnScrape.debounceSeconds}.
 */
@Component
public class ScrapeCompletedRatingsListener {

    private static final Logger log = LoggerFactory.getLogger(ScrapeCompletedRatingsListener.class);

    private final TtSeriesEloSyncService eloSyncService;
    private final TrueSkill2Service trueSkill2Service;
    private final WengLinService wengLinService;
    private final Glicko2RatingService glicko2RatingService;
    private final Clock clock;
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>(Instant.EPOCH);
    private SnapshotIndexCache snapshotIndexCache;
    private FeatureService featureService;
    private PredictionFacade predictionFacade;
    private OddsValueEngineService oddsValueEngineService;

    @Value("${ttl.ratings.autoRebuildOnScrape.enabled:true}")
    private boolean enabled;

    @Value("${ttl.ratings.autoRebuildOnScrape.debounceSeconds:0}")
    private long debounceSeconds;

    @Autowired
    public ScrapeCompletedRatingsListener(TtSeriesEloSyncService eloSyncService,
                                          TrueSkill2Service trueSkill2Service,
                                          WengLinService wengLinService,
                                          Glicko2RatingService glicko2RatingService) {
        this(eloSyncService, trueSkill2Service, wengLinService, glicko2RatingService, Clock.systemUTC());
    }

    ScrapeCompletedRatingsListener(TtSeriesEloSyncService eloSyncService,
                                   TrueSkill2Service trueSkill2Service,
                                   WengLinService wengLinService,
                                   Glicko2RatingService glicko2RatingService,
                                   Clock clock) {
        this.eloSyncService = eloSyncService;
        this.trueSkill2Service = trueSkill2Service;
        this.wengLinService = wengLinService;
        this.glicko2RatingService = glicko2RatingService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Autowired(required = false)
    void setLiveModelRefreshDependencies(SnapshotIndexCache snapshotIndexCache,
                                         FeatureService featureService,
                                         PredictionFacade predictionFacade,
                                         OddsValueEngineService oddsValueEngineService) {
        this.snapshotIndexCache = snapshotIndexCache;
        this.featureService = featureService;
        this.predictionFacade = predictionFacade;
        this.oddsValueEngineService = oddsValueEngineService;
    }

    @Async
    @EventListener
    public void onScrapeCompleted(ScrapeCompletedEvent event) {
        rebuild(event);
    }

    /** Package-visible for tests so they can drive the listener synchronously. */
    synchronized void rebuild(ScrapeCompletedEvent event) {
        if (!enabled) {
            log.debug("[ratings] auto-rebuild disabled; skipping scrape event {}", event.runId());
            return;
        }

        Instant now = clock.instant();
        Instant previous = lastRunAt.get();
        long sincePrevious = Duration.between(previous, now).toSeconds();
        if (previous != Instant.EPOCH && sincePrevious < debounceSeconds) {
            log.info("[ratings] auto-rebuild debounced (last run {}s ago, window {}s); event runId={} matches={}",
                    sincePrevious, debounceSeconds, event.runId(), event.savedMatches());
            return;
        }
        if (!lastRunAt.compareAndSet(previous, now)) {
            // Another thread already started; let it own this tick.
            return;
        }

        log.info("[ratings] auto-rebuild triggered by scrape runId={} matches={}",
                event.runId(), event.savedMatches());
        runStep("elo", () -> eloSyncService.syncFromRankingPage());
        runStep("trueskill2", () -> trueSkill2Service.rebuild(null, null));
        runStep("wenglin", () -> wengLinService.rebuild(null, null));
        runStep("glicko2", () -> glicko2RatingService.rebuild(null, null));
        if (snapshotIndexCache != null) {
            runStep("snapshot-index", snapshotIndexCache::refresh);
        }
        if (featureService != null) {
            featureService.invalidateForFreshMatchData(event.affectedPlayerIds());
        }
        if (predictionFacade != null) {
            predictionFacade.invalidateForFreshPlayerData();
        }
        if (oddsValueEngineService != null) {
            oddsValueEngineService.invalidateRecommendationsForFreshPlayerData();
        }
        log.info("[ratings] auto-rebuild finished for scrape runId={}", event.runId());
    }

    private static void runStep(String name, Runnable step) {
        long start = System.currentTimeMillis();
        try {
            step.run();
            log.info("[ratings] {} rebuild ok in {} ms", name, System.currentTimeMillis() - start);
        } catch (RuntimeException ex) {
            log.warn("[ratings] {} rebuild failed in {} ms: {}",
                    name, System.currentTimeMillis() - start, ex.getMessage(), ex);
        }
    }
}
