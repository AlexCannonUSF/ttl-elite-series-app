package com.ttl.tabletennis.service;

import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OddsSnapshotRetentionService {

    private final OddsSnapshotRepository oddsSnapshotRepository;

    @Value("${ttl.odds.retentionDays:30}")
    private int retentionDays;

    public OddsSnapshotRetentionService(OddsSnapshotRepository oddsSnapshotRepository) {
        this.oddsSnapshotRepository = oddsSnapshotRepository;
    }

    @Scheduled(cron = "${ttl.odds.snapshot.retentionCron:0 15 3 * * *}")
    @Transactional
    public void scheduledRetentionSweep() {
        pruneExpiredSnapshots(LocalDateTime.now());
    }

    @Transactional
    void pruneExpiredSnapshots(LocalDateTime now) {
        LocalDateTime safeNow = now == null ? LocalDateTime.now() : now;
        LocalDateTime cutoff = safeNow.minusDays(Math.max(1, retentionDays));
        oddsSnapshotRepository.deleteByObservedAtBefore(cutoff);
    }
}
