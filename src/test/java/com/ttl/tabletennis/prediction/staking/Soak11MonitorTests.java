package com.ttl.tabletennis.prediction.staking;

import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class Soak11MonitorTests {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-19T18:00:00Z");

    @Test
    void reportsUninitialisedWhenStartIsBlank() {
        Soak11Monitor monitor = new Soak11Monitor(
                Mockito.mock(SettlementDiffLogRepository.class),
                new SimpleMeterRegistry(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(monitor, "configuredSoakStart", "");
        ReflectionTestUtils.setField(monitor, "clvTargetRatio", 0.02);

        Soak11Monitor.Soak11Status status = monitor.refresh();

        assertFalse(status.allGreen());
        assertTrue(status.notes().contains("not set"), status.notes());
        assertEquals(0, status.daysElapsed());
        assertEquals(14, status.daysRequired());
    }

    @Test
    void allGreenWhenAllGatesPassAndTwoWeeksElapsed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // pre-populate counters/gauges the monitor reads from
        registry.counter("ttl.staking.exposure_cap_breach_total"); // present, value 0
        Counter reloads = Counter.builder("ttl.staking.policy.reloads").tag("status", "RELOADED").register(registry);
        reloads.increment(3); // 3 successful reloads
        Counter scoreBacked = Counter.builder("ttl.score_truth.primary.closures").tag("outcome", "SCORE_BACKED_ONLY").register(registry);
        scoreBacked.increment(2); // 2 SCORE_BACKED_ONLY holds
        AtomicReference<Double> clvRef = new AtomicReference<>(0.025); // 2.5% > 2.0%
        Gauge.builder("ttl.staking.clv_7d", clvRef, ref -> ref.get()).register(registry);

        SettlementDiffLogRepository repo = Mockito.mock(SettlementDiffLogRepository.class);
        when(repo.countByDiffKindAndDecidedAtAfter(anyString(), any(LocalDateTime.class))).thenReturn(0L);

        Soak11Monitor monitor = new Soak11Monitor(repo, registry,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        Instant soakStart = FIXED_NOW.minus(java.time.Duration.ofDays(15)); // 15 days ago > 14 required
        ReflectionTestUtils.setField(monitor, "configuredSoakStart", soakStart.toString());
        ReflectionTestUtils.setField(monitor, "clvTargetRatio", 0.02);

        Soak11Monitor.Soak11Status status = monitor.refresh();

        assertTrue(status.allGreen(), "expected all-green; got " + status);
        assertEquals(15, status.daysElapsed());
        assertTrue(status.contradictions().passing());
        assertTrue(status.exposureCapBreaches().passing());
        assertTrue(status.clv().passing());
        assertTrue(status.policyReloadDrill().passing());
        assertTrue(status.streamCvCoverage().passing());
        assertEquals(1.0, registry.find(Soak11Monitor.METRIC_OVERALL_PASS).gauge().value(), 1e-9);
    }

    @Test
    void anySingleFailingGateBlocksOverallPass() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter reloads = Counter.builder("ttl.staking.policy.reloads").tag("status", "RELOADED").register(registry);
        reloads.increment(3);
        Counter scoreBacked = Counter.builder("ttl.score_truth.primary.closures").tag("outcome", "SCORE_BACKED_ONLY").register(registry);
        scoreBacked.increment(2);
        AtomicReference<Double> clvRef = new AtomicReference<>(0.005); // 0.5% < 2.0% — fails CLV gate
        Gauge.builder("ttl.staking.clv_7d", clvRef, ref -> ref.get()).register(registry);

        SettlementDiffLogRepository repo = Mockito.mock(SettlementDiffLogRepository.class);
        when(repo.countByDiffKindAndDecidedAtAfter(anyString(), any(LocalDateTime.class))).thenReturn(0L);

        Soak11Monitor monitor = new Soak11Monitor(repo, registry,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        Instant soakStart = FIXED_NOW.minus(java.time.Duration.ofDays(20));
        ReflectionTestUtils.setField(monitor, "configuredSoakStart", soakStart.toString());
        ReflectionTestUtils.setField(monitor, "clvTargetRatio", 0.02);

        Soak11Monitor.Soak11Status status = monitor.refresh();

        assertFalse(status.allGreen());
        assertFalse(status.clv().passing());
        assertTrue(status.contradictions().passing());
    }

    @Test
    void gateMapEnumeratesAllFive() {
        Soak11Monitor.Soak11Status status = Soak11Monitor.Soak11Status.uninitialised();
        assertEquals(5, status.gateMap().size());
        assertNotNull(status.gateMap().get("contradictions"));
        assertNotNull(status.gateMap().get("exposureCapBreaches"));
        assertNotNull(status.gateMap().get("clv"));
        assertNotNull(status.gateMap().get("policyReloadDrill"));
        assertNotNull(status.gateMap().get("streamCvCoverage"));
    }

    @Test
    void notStartedWithGatesPassingShowsHelpfulNote() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SettlementDiffLogRepository repo = Mockito.mock(SettlementDiffLogRepository.class);
        when(repo.countByDiffKindAndDecidedAtAfter(anyString(), any(LocalDateTime.class))).thenReturn(0L);

        Soak11Monitor monitor = new Soak11Monitor(repo, registry,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(monitor, "configuredSoakStart", "not-an-instant");
        ReflectionTestUtils.setField(monitor, "clvTargetRatio", 0.02);

        Soak11Monitor.Soak11Status status = monitor.refresh();

        assertFalse(status.allGreen());
        assertNotNull(status.notes());
        assertTrue(status.notes().contains("not set"), status.notes());
    }
}
