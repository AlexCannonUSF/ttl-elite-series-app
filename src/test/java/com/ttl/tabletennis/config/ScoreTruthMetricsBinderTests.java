package com.ttl.tabletennis.config;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.service.ScoreTruthAdvisoryService;
import com.ttl.tabletennis.service.ScoreTruthReviewService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreTruthMetricsBinderTests {

    @Test
    void registersPhase03ScoreTruthAlertMetrics() {
        ScoreTruthReviewService reviewService = mock(ScoreTruthReviewService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-17T21:00:00Z"), ZoneOffset.UTC);

        when(reviewService.unresolvedQueueDepth()).thenReturn(16L);
        when(betRepository.countByStatusAndPendingEvidenceReasonAndPendingEvidenceUpdatedAtGreaterThanEqual(
                eq(PaperTradeBet.STATUS_PENDING_EVIDENCE),
                eq(ScoreTruthAdvisoryService.PENDING_EVIDENCE_TTL_EXPIRED),
                any(LocalDateTime.class)
        )).thenReturn(3L);

        ScoreTruthMetricsBinder binder = new ScoreTruthMetricsBinder(reviewService, betRepository, clock);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        binder.bindTo(meterRegistry);

        assertEquals(16.0, meterRegistry.get("ttl.score_truth.manual_review.queue.depth").gauge().value());
        assertEquals(3.0, meterRegistry.get("ttl.score_truth.pending_evidence.ttl_expired.last_hour").gauge().value());
    }
}
