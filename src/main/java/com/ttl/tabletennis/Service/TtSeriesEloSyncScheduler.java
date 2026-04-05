package com.ttl.tabletennis.service;

import com.ttl.tabletennis.dto.EloSyncResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TtSeriesEloSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(TtSeriesEloSyncScheduler.class);

    private final TtSeriesEloSyncService eloSyncService;

    @Value("${ttl.elo.sync.enabled:true}")
    private boolean enabled;

    @Value("${ttl.elo.sync.scheduled:true}")
    private boolean scheduledEnabled;

    public TtSeriesEloSyncScheduler(TtSeriesEloSyncService eloSyncService) {
        this.eloSyncService = eloSyncService;
    }

    @Scheduled(cron = "${ttl.elo.sync.cron:0 30 */6 * * *}")
    public void scheduledSync() {
        if (!enabled || !scheduledEnabled) {
            return;
        }
        EloSyncResultDto result = eloSyncService.syncFromRankingPage();
        if (!result.success()) {
            log.warn("[elo] scheduled sync failed: {}", result.message());
            return;
        }
        log.info("[elo] scheduled sync: rows={}, matched={}, inserted={}, updated={}, unchanged={}, unresolved={}",
                result.rankingRows(),
                result.matchedPlayers(),
                result.snapshotsInserted(),
                result.snapshotsUpdated(),
                result.unchangedPlayers(),
                result.unresolvedPlayers());
    }
}

