package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.ScrapeRun;
import com.ttl.tabletennis.repository.ScrapeRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Transactional
class ScrapeMetricsTests {

    @Autowired
    private TtSeriesScraper scraper;

    @Autowired
    private ScrapeRunRepository scrapeRunRepository;

    @Test
    void metricsAggregatesSuccessRateAndDurations() {
        saveRun(1, "MANUAL", "SUCCESS", 5, 10);
        saveRun(2, "PAGE_RANGE", "FAILED", 0, 40);
        saveRun(3, "AUTO", "SUCCESS", 7, 20);

        TtSeriesScraper.ScrapeMetrics metrics = scraper.metrics(50);

        assertEquals(3, metrics.totalRuns());
        assertEquals(2, metrics.successRuns());
        assertEquals(1, metrics.failedRuns());
        assertEquals(2.0 / 3.0, metrics.successRate(), 0.0001);
        assertEquals(23.333, metrics.averageDurationSeconds(), 0.01);
        assertEquals(20.0, metrics.medianDurationSeconds(), 0.01);
        assertEquals(40.0, metrics.p95DurationSeconds(), 0.01);
        assertEquals(4.0, metrics.averageMatchesAdded(), 0.0001);
    }

    private void saveRun(int runNumber, String mode, String status, int matchesAdded, long durationSeconds) {
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(runNumber);
        ScrapeRun run = new ScrapeRun();
        run.setRunNumber(runNumber);
        run.setMode(mode);
        run.setStatus(status);
        run.setStartedAt(startedAt);
        run.setFinishedAt(startedAt.plusSeconds(durationSeconds));
        run.setMatchesAdded(matchesAdded);
        run.setSource("https://www.tt-series.com/category/turnieje");
        scrapeRunRepository.save(run);
    }
}
