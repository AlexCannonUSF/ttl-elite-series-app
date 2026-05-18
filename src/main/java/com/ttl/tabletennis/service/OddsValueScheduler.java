package com.ttl.tabletennis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OddsValueScheduler {

    private static final Logger log = LoggerFactory.getLogger(OddsValueScheduler.class);

    private final OddsValueEngineService oddsValueEngineService;

    @Value("${ttl.odds.autoMonitorEnabled:false}")
    private boolean autoMonitorEnabled;

    @Value("${ttl.odds.defaultStrategy:CONSERVATIVE}")
    private String defaultStrategy;

    @Value("${ttl.odds.defaultModelFamily:ENSEMBLE}")
    private String defaultModelFamily;

    public OddsValueScheduler(OddsValueEngineService oddsValueEngineService) {
        this.oddsValueEngineService = oddsValueEngineService;
    }

    @Scheduled(cron = "${ttl.odds.monitorCron:0 */20 * * * *}")
    public void scheduledRefresh() {
        if (!autoMonitorEnabled) {
            return;
        }
        try {
            var result = oddsValueEngineService.refresh(defaultStrategy, defaultModelFamily);
            log.info("[odds] refresh done: quotes={}, resolved={}, opportunities={}",
                    result.quotesFetched(), result.quotesResolved(), result.opportunitiesCreated());
        } catch (Exception e) {
            log.warn("[odds] scheduled refresh failed: {}", e.getMessage(), e);
        }
    }
}
