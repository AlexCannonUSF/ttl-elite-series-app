package com.ttl.tabletennis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaperTradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaperTradingScheduler.class);

    private final PaperTradingService paperTradingService;

    @Value("${ttl.paper.autoSyncEnabled:false}")
    private boolean autoSyncEnabled;

    @Value("${ttl.odds.defaultStrategy:CONSERVATIVE}")
    private String defaultStrategy;

    @Value("${ttl.odds.defaultModelFamily:ENSEMBLE}")
    private String defaultModelVersion;

    @Value("${ttl.paper.liveRowsLimit:80}")
    private int liveRowsLimit;

    public PaperTradingScheduler(PaperTradingService paperTradingService) {
        this.paperTradingService = paperTradingService;
    }

    @Scheduled(cron = "${ttl.paper.autoSyncCron:0 */2 * * * *}")
    public void scheduledPaperSync() {
        if (!autoSyncEnabled) {
            return;
        }
        try {
            var result = paperTradingService.syncLiveSession(defaultStrategy, defaultModelVersion, liveRowsLimit);
            log.info("[paper] sync done: scanned={}, placed={}, settled={}, voided={}, bankroll={}",
                    result.rowsScanned(),
                    result.betsPlaced(),
                    result.betsSettled(),
                    result.betsVoided(),
                    result.session().currentBankroll());
        } catch (ObjectOptimisticLockingFailureException e) {
            // A reset/sync race can briefly invalidate the loaded session row; skip this tick and continue.
            log.info("[paper] scheduled sync skipped due concurrent session update");
        } catch (Exception e) {
            log.warn("[paper] scheduled sync failed: {}", e.getMessage(), e);
        }
    }
}
