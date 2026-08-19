package com.ttl.tabletennis.config;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.SettlementDiffLog;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.PaperTradeBetShadowRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionShadowRepository;
import com.ttl.tabletennis.repository.ScrapeErrorRepository;
import com.ttl.tabletennis.repository.ScrapeRunRepository;
import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.scrape.FeedClient;
import com.ttl.tabletennis.scrape.FeedHealth;
import com.ttl.tabletennis.scrape.SourceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TtlBaseMetricsBinderTests {

    @Test
    void registersPrimaryShadowAndFeedMetrics() {
        PaperTradeSessionRepository sessionRepository = mock(PaperTradeSessionRepository.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        PaperTradeSessionShadowRepository sessionShadowRepository = mock(PaperTradeSessionShadowRepository.class);
        PaperTradeBetShadowRepository betShadowRepository = mock(PaperTradeBetShadowRepository.class);
        TrackedMatchObservationRepository trackedMatchObservationRepository = mock(TrackedMatchObservationRepository.class);
        ScrapeRunRepository scrapeRunRepository = mock(ScrapeRunRepository.class);
        ScrapeErrorRepository scrapeErrorRepository = mock(ScrapeErrorRepository.class);
        SettlementDiffLogRepository settlementDiffLogRepository = mock(SettlementDiffLogRepository.class);
        @SuppressWarnings("unchecked")
        FeedClient<Object> feedClient = mock(FeedClient.class);

        PaperTradeSession activeSession = mock(PaperTradeSession.class);
        when(activeSession.getId()).thenReturn(77L);
        when(activeSession.getCurrentBankroll()).thenReturn(912.45);
        when(activeSession.getRealizedPnl()).thenReturn(47.67);
        when(activeSession.getTotalStaked()).thenReturn(180.0);

        when(sessionRepository.count()).thenReturn(3L);
        when(sessionRepository.findByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE)).thenReturn(List.of(activeSession));
        when(sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE)).thenReturn(Optional.of(activeSession));
        when(sessionShadowRepository.count()).thenReturn(2L);
        when(betRepository.countBySessionIdAndStatus(77L, PaperTradeBet.STATUS_OPEN)).thenReturn(7L);
        when(betRepository.countBySessionIdAndStatus(77L, PaperTradeBet.STATUS_WON)).thenReturn(5L);
        when(betRepository.countBySessionIdAndStatus(77L, PaperTradeBet.STATUS_LOST)).thenReturn(1L);
        when(betRepository.countBySessionIdAndStatus(77L, PaperTradeBet.STATUS_PUSHED)).thenReturn(0L);
        when(betRepository.countBySessionIdAndStatus(77L, PaperTradeBet.STATUS_VOIDED)).thenReturn(0L);
        when(betRepository.countBySessionIdAndStatus(77L, PaperTradeBet.STATUS_PENDING_EVIDENCE)).thenReturn(2L);
        when(betShadowRepository.count()).thenReturn(13L);
        when(trackedMatchObservationRepository.count()).thenReturn(101L);
        when(scrapeRunRepository.count()).thenReturn(12L);
        when(scrapeErrorRepository.count()).thenReturn(4L);
        when(settlementDiffLogRepository.count()).thenReturn(9L);
        when(settlementDiffLogRepository.countByDiffKind(SettlementDiffLog.DIFF_KIND_AGREE)).thenReturn(8L);
        when(settlementDiffLogRepository.countByDiffKind(SettlementDiffLog.DIFF_KIND_CONTRADICTION)).thenReturn(2L);
        when(feedClient.source()).thenReturn(SourceId.HR_MKT);
        when(feedClient.currentHealth()).thenReturn(new FeedHealth(
                SourceId.HR_MKT,
                Instant.now().minusSeconds(12),
                Instant.now().minusSeconds(45),
                0.82,
                125.0,
                410.0,
                12L,
                1,
                "ACTIVE",
                ""
        ));

        TtlBaseMetricsBinder binder = new TtlBaseMetricsBinder(
                sessionRepository,
                betRepository,
                sessionShadowRepository,
                betShadowRepository,
                trackedMatchObservationRepository,
                scrapeRunRepository,
                scrapeErrorRepository,
                settlementDiffLogRepository,
                List.of(feedClient)
        );

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        binder.bindTo(meterRegistry);

        assertEquals(3.0, meterRegistry.get("ttl.paper.sessions").tag("table", "primary").tag("state", "all").gauge().value());
        assertEquals(1.0, meterRegistry.get("ttl.paper.sessions").tag("table", "primary").tag("state", "active").gauge().value());
        assertEquals(7.0, meterRegistry.get("ttl.paper.bets").tag("status", "open").gauge().value());
        assertEquals(5.0, meterRegistry.get("ttl.paper.bets").tag("status", "won").gauge().value());
        assertEquals(2.0, meterRegistry.get("ttl.paper.bets").tag("status", "pending_evidence").gauge().value());
        assertEquals(912.45, meterRegistry.get("ttl.paper.bankroll.current").gauge().value());
        assertEquals(47.67, meterRegistry.get("ttl.paper.pnl.realized").gauge().value());
        assertEquals(47.67 / 180.0, meterRegistry.get("ttl.paper.roi.current").gauge().value(), 1.0e-9);
        assertEquals(13.0, meterRegistry.get("ttl.shadow.bets").gauge().value());
        assertEquals(101.0, meterRegistry.get("ttl.tracked.observations").gauge().value());
        assertEquals(12.0, meterRegistry.get("ttl.scrape.runs").gauge().value());
        assertEquals(4.0, meterRegistry.get("ttl.scrape.errors").gauge().value());
        assertEquals(9.0, meterRegistry.get("ttl.settlement.diff.rows").gauge().value());
        assertEquals(1.0, meterRegistry.get("ttl.settlement.diff.disagreements").gauge().value());
        assertEquals(2.0, meterRegistry.get("ttl.settlement.diff.contradictions").gauge().value());
        assertEquals(0.82, meterRegistry.get("ttl.feed.success.rate").tag("source", "HR_MKT").gauge().value(), 1.0e-9);
        assertEquals(125.0, meterRegistry.get("ttl.feed.latency.p50.ms").tag("source", "HR_MKT").gauge().value(), 1.0e-9);
        assertEquals(410.0, meterRegistry.get("ttl.feed.latency.p95.ms").tag("source", "HR_MKT").gauge().value(), 1.0e-9);
        assertEquals(1.0, meterRegistry.get("ttl.feed.inflight").tag("source", "HR_MKT").gauge().value());
        assertEquals(12.0, meterRegistry.get("ttl.feed.staleness.seconds").tag("source", "HR_MKT").gauge().value(), 1.0e-9);
        assertTrue(meterRegistry.get("ttl.feed.last.success.age.seconds").tag("source", "HR_MKT").gauge().value() >= 0.0);
    }
}
