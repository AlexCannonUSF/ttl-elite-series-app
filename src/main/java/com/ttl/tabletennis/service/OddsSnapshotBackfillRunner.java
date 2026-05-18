package com.ttl.tabletennis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OddsSnapshotBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OddsSnapshotBackfillRunner.class);

    private final OddsSnapshotBackfillService oddsSnapshotBackfillService;

    @Value("${ttl.odds.snapshot.backfillOnStartup:false}")
    private boolean backfillOnStartup;

    @Value("${ttl.odds.snapshot.backfill.batchSize:500}")
    private int batchSize;

    @Value("${ttl.odds.snapshot.backfill.maxPages:0}")
    private int maxPages;

    public OddsSnapshotBackfillRunner(OddsSnapshotBackfillService oddsSnapshotBackfillService) {
        this.oddsSnapshotBackfillService = oddsSnapshotBackfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!backfillOnStartup) {
            return;
        }
        OddsSnapshotBackfillService.BackfillResult result =
                oddsSnapshotBackfillService.backfillHistoricalQuotes(batchSize, maxPages);
        log.info("[odds-snapshot-backfill] scannedQuotes={}, eligibleQuotes={}, persistedSnapshots={}, skippedSnapshots={}, pagesProcessed={}",
                result.scannedQuotes(),
                result.eligibleQuotes(),
                result.persistedSnapshots(),
                result.skippedSnapshots(),
                result.pagesProcessed());
    }
}
