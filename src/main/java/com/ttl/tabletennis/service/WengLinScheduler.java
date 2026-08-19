package com.ttl.tabletennis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WengLinScheduler {

    private static final Logger log = LoggerFactory.getLogger(WengLinScheduler.class);

    private final WengLinService wengLinService;

    @Value("${ttl.wenglin.autoRebuild:false}")
    private boolean autoRebuild;

    public WengLinScheduler(WengLinService wengLinService) {
        this.wengLinService = wengLinService;
    }

    @Scheduled(cron = "${ttl.wenglin.rebuildCron:0 0 3 * * *}", zone = "UTC")
    public void scheduledRebuild() {
        if (!autoRebuild) {
            return;
        }
        try {
            wengLinService.rebuild(null, null);
        } catch (Exception e) {
            log.warn("[wenglin] scheduled rebuild failed: {}", e.getMessage(), e);
        }
    }
}
