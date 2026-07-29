package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.ScrapeErrorRepository;
import com.ttl.tabletennis.repository.ScrapeRunRepository;
import com.ttl.tabletennis.service.PlayerIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the #112 stuck-scrape watchdog in TtSeriesScraper.
 *
 * These tests reach into the private flags via reflection so we can simulate
 * a stuck scrape without spinning up a real HTTP fetch. The watchdog itself
 * is the unit under test, not the scrape pipeline.
 */
class TtSeriesScraperWatchdogTests {

    private TtSeriesScraper scraper;

    @BeforeEach
    void setUp() {
        scraper = new TtSeriesScraper(
                mock(MatchRepository.class),
                mock(PlayerIdentityService.class),
                mock(ScrapeRunRepository.class),
                mock(ScrapeErrorRepository.class),
                mock(ApplicationEventPublisher.class)
        );
        ReflectionTestUtils.setField(scraper, "scrapeWatchdogEnabled", true);
        // Tight thresholds so the math is easy to reason about in tests.
        ReflectionTestUtils.setField(scraper, "scrapeWatchdogFixedDelayMs", 60_000L);
        ReflectionTestUtils.setField(scraper, "scrapeWatchdogThresholdMs", 120_000L);
        ReflectionTestUtils.setField(scraper, "scrapeWatchdogRefreshThresholdMs", 60_000L);
    }

    @Test
    void watchdogIsNoOpWhenScrapeNotRunning() {
        scrapeRunningFlag().set(false);
        officialRefreshFlag().set(false);

        scraper.scrapeStuckRunWatchdog();

        assertThat(scrapeRunningFlag().get()).isFalse();
        assertThat(officialRefreshFlag().get()).isFalse();
    }

    @Test
    void watchdogDoesNotRequestStopBeforeThreshold() {
        scrapeRunningFlag().set(true);
        startedAtRef().set(LocalDateTime.now().minusSeconds(30)); // 30s < 120s threshold

        scraper.scrapeStuckRunWatchdog();

        assertThat(scrapeRunningFlag().get()).isTrue(); // untouched
        assertThat(stopRequestedFlag().get()).isFalse();
    }

    @Test
    void watchdogRequestsStopOncePastThreshold() {
        scrapeRunningFlag().set(true);
        startedAtRef().set(LocalDateTime.now().minusSeconds(150)); // 150s > 120s threshold,
                                                                    // but < 180s force-reset window

        scraper.scrapeStuckRunWatchdog();

        // Cooperative stop requested but flag NOT yet force-cleared:
        assertThat(stopRequestedFlag().get()).isTrue();
        assertThat(scrapeRunningFlag().get()).isTrue();
    }

    @Test
    void watchdogForceResetsAfterGracePeriod() {
        scrapeRunningFlag().set(true);
        startedAtRef().set(LocalDateTime.now().minusSeconds(200)); // > threshold + fixedDelay
        ReflectionTestUtils.setField(scraper, "lastMode",
                new AtomicReference<>("PAGE_RANGE"));

        scraper.scrapeStuckRunWatchdog();

        // Force-reset path: scrapeRunning cleared, stopRequested cleared too.
        assertThat(scrapeRunningFlag().get()).isFalse();
        assertThat(stopRequestedFlag().get()).isFalse();
    }

    @Test
    void watchdogClearsStuckOfficialResultsRefresh() {
        officialRefreshFlag().set(true);
        officialRefreshStartedAtRef().set(LocalDateTime.now().minusSeconds(120)); // > 60s threshold

        scraper.scrapeStuckRunWatchdog();

        assertThat(officialRefreshFlag().get()).isFalse();
        assertThat(officialRefreshStartedAtRef().get()).isNull();
    }

    @Test
    void watchdogIsDisabledWhenFlagOff() {
        ReflectionTestUtils.setField(scraper, "scrapeWatchdogEnabled", false);
        scrapeRunningFlag().set(true);
        startedAtRef().set(LocalDateTime.now().minusHours(1)); // wildly stuck

        scraper.scrapeStuckRunWatchdog();

        // Disabled → no action even though the scrape is clearly hung.
        assertThat(scrapeRunningFlag().get()).isTrue();
    }

    @Test
    void operatorForceResetClearsBothFlagsAndReportsState() {
        scrapeRunningFlag().set(true);
        startedAtRef().set(LocalDateTime.now().minusMinutes(35));
        officialRefreshFlag().set(true);
        officialRefreshStartedAtRef().set(LocalDateTime.now().minusMinutes(15));
        ReflectionTestUtils.setField(scraper, "lastMode",
                new AtomicReference<>("OFFICIAL_RESULTS_REFRESH"));

        TtSeriesScraper.ScrapeForceResetResult result = scraper.forceResetForOperator();

        assertThat(result.scrapeRunningWasStuck()).isTrue();
        assertThat(result.scrapeRunningStuckMs()).isGreaterThan(30 * 60_000L);
        assertThat(result.officialRefreshWasStuck()).isTrue();
        assertThat(result.officialRefreshStuckMs()).isGreaterThan(10 * 60_000L);
        assertThat(scrapeRunningFlag().get()).isFalse();
        assertThat(officialRefreshFlag().get()).isFalse();
    }

    @Test
    void operatorForceResetIsNoOpWhenNothingStuck() {
        scrapeRunningFlag().set(false);
        officialRefreshFlag().set(false);

        TtSeriesScraper.ScrapeForceResetResult result = scraper.forceResetForOperator();

        assertThat(result.scrapeRunningWasStuck()).isFalse();
        assertThat(result.scrapeRunningStuckMs()).isZero();
        assertThat(result.officialRefreshWasStuck()).isFalse();
        assertThat(result.officialRefreshStuckMs()).isZero();
    }

    // --- reflection helpers ---

    @SuppressWarnings("unchecked")
    private AtomicBoolean scrapeRunningFlag() {
        return (AtomicBoolean) ReflectionTestUtils.getField(scraper, "scrapeRunning");
    }

    @SuppressWarnings("unchecked")
    private AtomicBoolean stopRequestedFlag() {
        return (AtomicBoolean) ReflectionTestUtils.getField(scraper, "stopRequested");
    }

    @SuppressWarnings("unchecked")
    private AtomicBoolean officialRefreshFlag() {
        return (AtomicBoolean) ReflectionTestUtils.getField(scraper, "officialResultsRefreshInFlight");
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<LocalDateTime> startedAtRef() {
        return (AtomicReference<LocalDateTime>) ReflectionTestUtils.getField(scraper, "lastStartedAt");
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<LocalDateTime> officialRefreshStartedAtRef() {
        return (AtomicReference<LocalDateTime>) ReflectionTestUtils.getField(scraper, "officialResultsRefreshStartedAt");
    }
}
