package com.ttl.tabletennis.Controller;

import com.ttl.tabletennis.scrape.TtSeriesScraper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}