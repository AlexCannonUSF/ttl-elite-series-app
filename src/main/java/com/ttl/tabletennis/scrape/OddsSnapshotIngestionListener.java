package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import com.ttl.tabletennis.model.MatchOdds;

import java.util.List;

@Component
public class OddsSnapshotIngestionListener {

    private final OddsSnapshotRepository oddsSnapshotRepository;
    private final OddsSnapshotFactory oddsSnapshotFactory;

    public OddsSnapshotIngestionListener(OddsSnapshotRepository oddsSnapshotRepository,
                                         OddsSnapshotFactory oddsSnapshotFactory) {
        this.oddsSnapshotRepository = oddsSnapshotRepository;
        this.oddsSnapshotFactory = oddsSnapshotFactory;
    }

    @Async("ttlIngestionBusExecutor")
    @EventListener
    public void onIngestEvent(IngestEvent<?> event) {
        if (event == null || !(event.payload() instanceof MatchOdds odds)) {
            return;
        }
        if (event.source() != HardRockFeedClient.SOURCE) {
            return;
        }

        List<OddsSnapshot> snapshots = oddsSnapshotFactory.fromMatchOddsEvent(event, odds);
        if (!snapshots.isEmpty()) {
            oddsSnapshotRepository.saveAll(snapshots);
        }
    }
}
