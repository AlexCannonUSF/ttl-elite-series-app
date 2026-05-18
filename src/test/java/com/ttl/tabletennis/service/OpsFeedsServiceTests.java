package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.FeedHealthSample;
import com.ttl.tabletennis.dto.OpsFeedStatusDto;
import com.ttl.tabletennis.dto.OpsFeedsDto;
import com.ttl.tabletennis.repository.FeedHealthSampleRepository;
import com.ttl.tabletennis.repository.IngestDlqRepository;
import com.ttl.tabletennis.scrape.FeedClient;
import com.ttl.tabletennis.scrape.FeedHealth;
import com.ttl.tabletennis.scrape.SourceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsFeedsServiceTests {

    @Test
    void snapshotAggregatesFeedHealthCapabilitiesAndDlqDepth() {
        FeedHealthService feedHealthService = mock(FeedHealthService.class);
        FeedHealthSampleRepository feedHealthSampleRepository = mock(FeedHealthSampleRepository.class);
        IngestDlqRepository ingestDlqRepository = mock(IngestDlqRepository.class);
        @SuppressWarnings("unchecked")
        FeedClient<Object> hardRockClient = mock(FeedClient.class);
        @SuppressWarnings("unchecked")
        FeedClient<Object> sofaScoreClient = mock(FeedClient.class);

        when(hardRockClient.source()).thenReturn(SourceId.HR_MKT);
        when(hardRockClient.capabilities()).thenReturn(Set.of(
                FeedClient.Capability.ODDS,
                FeedClient.Capability.SCORES,
                FeedClient.Capability.MARKET_STATE
        ));
        when(sofaScoreClient.source()).thenReturn(SourceId.SOFASCORE);
        when(sofaScoreClient.capabilities()).thenReturn(Set.of(
                FeedClient.Capability.SCORES,
                FeedClient.Capability.RESULTS,
                FeedClient.Capability.POINT_BY_POINT
        ));

        FeedHealth hardRockHealth = new FeedHealth(
                SourceId.HR_MKT,
                Instant.now().minusSeconds(12),
                Instant.now().minusSeconds(80),
                0.91,
                122.0,
                268.0,
                12L,
                1,
                "ACTIVE",
                ""
        );
        FeedHealth sofaScoreHealth = FeedHealth.idle(SourceId.SOFASCORE);

        when(feedHealthService.currentFeeds()).thenReturn(List.of(hardRockHealth, sofaScoreHealth));
        when(ingestDlqRepository.countBySourceId(SourceId.HR_MKT)).thenReturn(3L);
        when(ingestDlqRepository.countBySourceId(SourceId.SOFASCORE)).thenReturn(0L);
        when(feedHealthSampleRepository.findTopBySourceIdOrderByObservedAtDesc(SourceId.HR_MKT))
                .thenReturn(Optional.of(sample(SourceId.HR_MKT, "2026-04-19T16:03:00")));
        when(feedHealthSampleRepository.findTopBySourceIdOrderByObservedAtDesc(SourceId.SOFASCORE))
                .thenReturn(Optional.empty());

        OpsFeedsService service = new OpsFeedsService(
                List.of(hardRockClient, sofaScoreClient),
                feedHealthService,
                feedHealthSampleRepository,
                ingestDlqRepository
        );

        OpsFeedsDto snapshot = service.snapshot();

        assertEquals(2L, snapshot.summary().totalSources());
        assertEquals(0L, snapshot.summary().healthySources());
        assertEquals(1L, snapshot.summary().degradedSources());
        assertEquals(0L, snapshot.summary().downSources());
        assertEquals(1L, snapshot.summary().idleSources());
        assertEquals(3L, snapshot.summary().totalDlqDepth());

        OpsFeedStatusDto hardRock = snapshot.feeds().stream()
                .filter(feed -> "HR_MKT".equals(feed.sourceId()))
                .findFirst()
                .orElseThrow();
        assertEquals("DEGRADED", hardRock.status());
        assertEquals(List.of("MARKET_STATE", "ODDS", "SCORES"), hardRock.capabilities());
        assertEquals(3L, hardRock.dlqDepth());
        assertEquals(0.91, hardRock.successRate5m(), 1.0e-9);
        assertNotNull(hardRock.lastSampleAt());

        OpsFeedStatusDto sofaScore = snapshot.feeds().stream()
                .filter(feed -> "SOFASCORE".equals(feed.sourceId()))
                .findFirst()
                .orElseThrow();
        assertEquals("IDLE", sofaScore.status());
        assertFalse(sofaScore.liveTick());
        assertNull(sofaScore.successRate5m());
        assertNull(sofaScore.stalenessSeconds());
    }

    private FeedHealthSample sample(SourceId sourceId, String observedAtUtc) {
        FeedHealthSample sample = new FeedHealthSample();
        sample.setSourceId(sourceId);
        sample.setObservedAt(LocalDateTime.parse(observedAtUtc));
        return sample;
    }
}
