package com.ttl.tabletennis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrueSkill2Scheduler {

    private static final Logger log = LoggerFactory.getLogger(TrueSkill2Scheduler.class);

    private final TrueSkill2Service trueSkill2Service;

    @Value("${ttl.trueskill2.autoRebuild:false}")
    private boolean autoRebuild;

    public TrueSkill2Scheduler(TrueSkill2Service trueSkill2Service) {
        this.trueSkill2Service = trueSkill2Service;
    }

    @Scheduled(cron = "${ttl.trueskill2.rebuildCron:0 0 3 * * *}", zone = "UTC")
    public void scheduledRebuild() {
        if (!autoRebuild) {
            return;
        }
        try {
            trueSkill2Service.rebuild(null, null);
        } catch (Exception e) {
            log.warn("[trueskill2] scheduled rebuild failed: {}", e.getMessage(), e);
        }
    }
}
