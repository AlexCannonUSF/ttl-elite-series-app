package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.scrape.TtSeriesScraper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/scrape")
public class ScrapeController {

    private final TtSeriesScraper scraper;

    public ScrapeController(TtSeriesScraper scraper) {
        this.scraper = scraper;
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
