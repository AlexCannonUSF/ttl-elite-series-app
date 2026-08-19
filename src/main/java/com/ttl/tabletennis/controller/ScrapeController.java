package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.scrape.ScrapeRunOrphanCleanup;
import com.ttl.tabletennis.scrape.TtSeriesScraper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/scrape")
public class ScrapeController {

    private final TtSeriesScraper scraper;
    private final ScrapeRunOrphanCleanup orphanCleanup;

    public ScrapeController(TtSeriesScraper scraper, ScrapeRunOrphanCleanup orphanCleanup) {
        this.scraper = scraper;
        this.orphanCleanup = orphanCleanup;
    }

    /**
     * Manually flip any {@code scrape_run} rows stuck in {@code RUNNING}
     * to {@code FAILED} with reason {@code JVM_RESTART}. Idempotent; safe
     * to call any time. The same cleanup also runs once on
     * {@code ApplicationReadyEvent}, so this endpoint is for ops who want
     * to clear a stuck row without bouncing the backend.
     */
    @PostMapping("/finalize-orphans")
    public ResponseEntity<ScrapeRunOrphanCleanup.CleanupResult> finalizeOrphans() {
        return ResponseEntity.ok(orphanCleanup.finalizeOrphans());
    }

    /** Uses current properties (e.g., -Dttl.onlyId=####). */
    @PostMapping("/run")
    public ResponseEntity<String> run() {
        new Thread(scraper::run, "ttl-scraper-manual").start();
        return ResponseEntity.ok("Scraper started.");
    }

    /** Force a specific post id, ignoring ttl.onlyId. */
    @PostMapping("/id/{id}")
    public ResponseEntity<String> runOne(@PathVariable int id) {
        new Thread(() -> scraper.scrapePost(id), "ttl-scrape-" + id).start();
        return ResponseEntity.ok("Scraping id " + id + " started.");
    }

    /** Force a page range scrape run without restarting the app. */
    @PostMapping("/range")
    public ResponseEntity<String> runRange(@RequestParam int fromPage, @RequestParam int toPage) {
        int normalizedFrom = Math.max(1, fromPage);
        int normalizedTo = Math.max(normalizedFrom, toPage);
        new Thread(() -> scraper.runPageRange(normalizedFrom, normalizedTo),
                "ttl-scrape-range-" + normalizedFrom + "-" + normalizedTo).start();
        return ResponseEntity.ok("Scraping pages " + normalizedFrom + ".." + normalizedTo + " started.");
    }

    @GetMapping("/status")
    public ResponseEntity<TtSeriesScraper.ScrapeStatus> status() {
        return ResponseEntity.ok(scraper.status());
    }

    /**
     * Cooperative stop: the in-flight page completes (no partial saves),
     * subsequent pages are skipped. The run is finalised with status
     * SUCCESS and the saved-match counter reflects what made it to disk.
     */
    @PostMapping("/stop")
    public ResponseEntity<String> stop() {
        boolean wasRunning = scraper.requestStop();
        if (!wasRunning) {
            return ResponseEntity.ok("No scrape is running.");
        }
        return ResponseEntity.ok("Stop requested. The in-flight page will complete, then the run will end.");
    }

    /**
     * Operator escape hatch (#112). Force-clear the {@code scrapeRunning} and
     * {@code officialResultsRefreshInFlight} flags without a JVM restart for
     * cases where {@link #stop()} fails to unstick the scrape (e.g. a Jsoup
     * fetch that's blocked past its 20s timeout, a deadlock, or a stalled
     * underlying network call). The periodic watchdog also calls this path
     * automatically once a run has been stuck longer than the threshold.
     */
    @PostMapping("/force-reset")
    public ResponseEntity<TtSeriesScraper.ScrapeForceResetResult> forceReset() {
        return ResponseEntity.ok(scraper.forceResetForOperator());
    }

    @GetMapping("/runs")
    public ResponseEntity<List<TtSeriesScraper.ScrapeRunRecord>> runs(@RequestParam(required = false) String status,
                                                                      @RequestParam(required = false) String mode,
                                                                      @RequestParam(defaultValue = "25") Integer limit) {
        return ResponseEntity.ok(scraper.recentRuns(status, mode, limit));
    }

    @GetMapping("/errors")
    public ResponseEntity<List<TtSeriesScraper.ScrapeErrorRecord>> errors(@RequestParam(defaultValue = "25") Integer limit) {
        return ResponseEntity.ok(scraper.recentErrors(limit));
    }

    @GetMapping("/metrics")
    public ResponseEntity<TtSeriesScraper.ScrapeMetrics> metrics(@RequestParam(defaultValue = "200") Integer limit) {
        return ResponseEntity.ok(scraper.metrics(limit));
    }

    @GetMapping("/dry-run")
    public ResponseEntity<TtSeriesScraper.DryRunPreview> dryRun(@RequestParam(defaultValue = "1") int page) throws IOException {
        return ResponseEntity.ok(scraper.dryRunListPage(page));
    }
}
