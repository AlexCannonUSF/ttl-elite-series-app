package com.ttl.tabletennis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Glicko2Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Glicko2Scheduler.class);

    private final Glicko2RatingService glicko2RatingService;

    @Value("${ttl.glicko2.autoRebuild:false}")
    private boolean autoRebuild;

    public Glicko2Scheduler(Glicko2RatingService glicko2RatingService) {
        this.glicko2RatingService = glicko2RatingService;
    }

    @Scheduled(cron = "${ttl.glicko2.rebuildCron:0 0 3 * * MON}")
    public void scheduledRebuild() {
        if (!autoRebuild) {
            return;
        }
        try {
            glicko2RatingService.rebuild(null, null);
        } catch (Exception e) {
            log.warn("[glicko2] scheduled rebuild failed: {}", e.getMessage(), e);
        }
    }
}
