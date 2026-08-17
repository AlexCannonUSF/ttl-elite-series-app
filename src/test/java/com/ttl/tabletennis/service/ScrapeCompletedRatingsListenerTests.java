package com.ttl.tabletennis.service;

import com.ttl.tabletennis.scrape.ScrapeCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ScrapeCompletedRatingsListenerTests {

    private TtSeriesEloSyncService eloSyncService;
    private TrueSkill2Service trueSkill2Service;
    private WengLinService wengLinService;
    private Glicko2RatingService glicko2RatingService;

    @BeforeEach
    void setUp() {
        eloSyncService = mock(TtSeriesEloSyncService.class);
        trueSkill2Service = mock(TrueSkill2Service.class);
        wengLinService = mock(WengLinService.class);
        glicko2RatingService = mock(Glicko2RatingService.class);
    }

    @Test
    void rebuildsAllFourFamiliesOnSuccess() {
        ScrapeCompletedRatingsListener listener = listener(
                Clock.fixed(Instant.parse("2026-05-19T10:00:00Z"), ZoneOffset.UTC));

        listener.rebuild(event(1, 12));

        InOrder order = inOrder(eloSyncService, trueSkill2Service, wengLinService, glicko2RatingService);
        order.verify(eloSyncService).syncFromRankingPage();
        order.verify(trueSkill2Service).rebuild(null, null);
        order.verify(wengLinService).rebuild(null, null);
        order.verify(glicko2RatingService).rebuild(null, null);
    }

    @Test
    void debouncesBurstScrapes() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-05-19T10:00:00Z"));
        ScrapeCompletedRatingsListener listener = listener(mutableClock(now));

        listener.rebuild(event(1, 12));
        now.set(Instant.parse("2026-05-19T10:01:00Z")); // +60s, inside the 300s window
        listener.rebuild(event(2, 3));

        verify(eloSyncService, times(1)).syncFromRankingPage();
        verify(trueSkill2Service, times(1)).rebuild(null, null);
        verify(wengLinService, times(1)).rebuild(null, null);
        verify(glicko2RatingService, times(1)).rebuild(null, null);
    }

    @Test
    void runsAgainAfterDebounceWindowElapses() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-05-19T10:00:00Z"));
        ScrapeCompletedRatingsListener listener = listener(mutableClock(now));

        listener.rebuild(event(1, 12));
        now.set(Instant.parse("2026-05-19T10:10:00Z")); // +10 min, outside the 5-min window
        listener.rebuild(event(2, 3));

        verify(eloSyncService, times(2)).syncFromRankingPage();
        verify(trueSkill2Service, times(2)).rebuild(null, null);
    }

    @Test
    void skipsEverythingWhenDisabled() {
        ScrapeCompletedRatingsListener listener = listener(
                Clock.fixed(Instant.parse("2026-05-19T10:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(listener, "enabled", false);

        listener.rebuild(event(1, 12));

        verifyNoInteractions(eloSyncService, trueSkill2Service, wengLinService, glicko2RatingService);
    }

    @Test
    void oneFailingStepDoesNotBlockOthers() {
        doThrow(new RuntimeException("ts2 boom")).when(trueSkill2Service).rebuild(any(), any());
        ScrapeCompletedRatingsListener listener = listener(
                Clock.fixed(Instant.parse("2026-05-19T10:00:00Z"), ZoneOffset.UTC));

        listener.rebuild(event(1, 12));

        verify(eloSyncService).syncFromRankingPage();
        verify(trueSkill2Service).rebuild(null, null);
        verify(wengLinService).rebuild(null, null);
        verify(glicko2RatingService).rebuild(null, null);
    }

    @Test
    void refreshesLiveStatisticsAndInvalidatesPredictionCaches() {
        ScrapeCompletedRatingsListener listener = listener(
                Clock.fixed(Instant.parse("2026-05-19T10:00:00Z"), ZoneOffset.UTC));
        SnapshotIndexCache snapshotIndexCache = mock(SnapshotIndexCache.class);
        FeatureService featureService = mock(FeatureService.class);
        PredictionFacade predictionFacade = mock(PredictionFacade.class);
        OddsValueEngineService oddsValueEngineService = mock(OddsValueEngineService.class);
        listener.setLiveModelRefreshDependencies(
                snapshotIndexCache, featureService, predictionFacade, oddsValueEngineService);

        Set<Long> affectedPlayers = Set.of(11L, 22L);
        listener.rebuild(new ScrapeCompletedEvent(
                7, "OFFICIAL_RESULTS", 2, 1, 1, affectedPlayers,
                LocalDateTime.of(2026, 5, 19, 12, 0)));

        verify(snapshotIndexCache).refresh();
        verify(featureService).invalidateForFreshMatchData(affectedPlayers);
        verify(predictionFacade).invalidateForFreshPlayerData();
        verify(oddsValueEngineService).invalidateRecommendationsForFreshPlayerData();
    }

    @Test
    void zeroDebounceProcessesEveryCompletedMutationBatch() {
        ScrapeCompletedRatingsListener listener = listener(
                Clock.fixed(Instant.parse("2026-05-19T10:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(listener, "debounceSeconds", 0L);

        listener.rebuild(event(1, 2));
        listener.rebuild(event(2, 3));

        verify(eloSyncService, times(2)).syncFromRankingPage();
        verify(glicko2RatingService, times(2)).rebuild(null, null);
    }

    private ScrapeCompletedRatingsListener listener(Clock clock) {
        ScrapeCompletedRatingsListener listener = new ScrapeCompletedRatingsListener(
                eloSyncService, trueSkill2Service, wengLinService, glicko2RatingService, clock);
        ReflectionTestUtils.setField(listener, "enabled", true);
        ReflectionTestUtils.setField(listener, "debounceSeconds", 300L);
        return listener;
    }

    private static ScrapeCompletedEvent event(int runId, int savedMatches) {
        return new ScrapeCompletedEvent(runId, "MANUAL", savedMatches, LocalDateTime.of(2026, 5, 19, 12, 0));
    }

    private static Clock mutableClock(AtomicReference<Instant> instantRef) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return instantRef.get(); }
        };
    }
}
