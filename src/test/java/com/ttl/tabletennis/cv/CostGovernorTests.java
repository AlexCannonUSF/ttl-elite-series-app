package com.ttl.tabletennis.cv;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostGovernorTests {

    @Test
    void reserveAllowsUpToHourlyCapPerWorker() {
        CostGovernor governor = newGovernor(2500, 4000, 3, true);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");

        assertTrue(governor.reserve("w1", t0).allowed());
        assertTrue(governor.reserve("w1", t0.plusSeconds(1)).allowed());
        assertTrue(governor.reserve("w1", t0.plusSeconds(2)).allowed());

        CostGovernor.Verdict denied = governor.reserve("w1", t0.plusSeconds(3));
        assertFalse(denied.allowed());
        assertEquals("worker_hourly_cap", denied.reason());
    }

    @Test
    void hourlyWindowSlidesAfterOneHour() {
        CostGovernor governor = newGovernor(2500, 4000, 2, true);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");

        assertTrue(governor.reserve("w1", t0).allowed());
        assertTrue(governor.reserve("w1", t0.plusSeconds(1)).allowed());
        assertFalse(governor.reserve("w1", t0.plusSeconds(2)).allowed());

        Instant tLater = t0.plus(Duration.ofMinutes(61));
        assertTrue(governor.reserve("w1", tLater).allowed());
    }

    @Test
    void perWorkerCapsAreIndependent() {
        CostGovernor governor = newGovernor(2500, 4000, 1, true);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");

        assertTrue(governor.reserve("w1", t0).allowed());
        assertTrue(governor.reserve("w2", t0).allowed());
        assertFalse(governor.reserve("w1", t0).allowed());
    }

    @Test
    void dailyHardCapDeniesAllReserves() {
        CostGovernor governor = newGovernor(2, 3, 100, true);
        Instant t0 = Instant.parse("2026-05-17T00:00:00Z");

        assertTrue(governor.reserve("w1", t0).allowed());
        assertTrue(governor.reserve("w2", t0).allowed());
        CostGovernor.Verdict third = governor.reserve("w3", t0);
        assertTrue(third.allowed());
        assertTrue(third.overSoftCap());

        CostGovernor.Verdict denied = governor.reserve("w4", t0);
        assertFalse(denied.allowed());
        assertEquals("daily_hard_cap", denied.reason());
    }

    @Test
    void softCapAllowsButFlagsOverSoftCap() {
        CostGovernor governor = newGovernor(1, 5, 100, true);
        Instant t0 = Instant.parse("2026-05-17T00:00:00Z");

        CostGovernor.Verdict v1 = governor.reserve("w1", t0);
        assertTrue(v1.allowed());
        assertFalse(v1.overSoftCap());

        CostGovernor.Verdict v2 = governor.reserve("w2", t0);
        assertTrue(v2.allowed());
        assertTrue(v2.overSoftCap());
    }

    @Test
    void dailyCountResetsOnUtcRollover() {
        CostGovernor governor = newGovernor(1, 1, 100, true);
        Instant day1 = Instant.parse("2026-05-17T23:59:00Z");

        assertTrue(governor.reserve("w1", day1).allowed());
        assertFalse(governor.reserve("w2", day1).allowed());

        Instant day2 = Instant.parse("2026-05-18T00:00:01Z");
        assertTrue(governor.reserve("w3", day2).allowed());
        assertEquals(1, governor.snapshot(day2).dailyCalls());
        assertEquals(day2.atZone(ZoneOffset.UTC).toLocalDate(), governor.snapshot(day2).date());
    }

    @Test
    void recordCallAccumulatesDailyCost() {
        CostGovernor governor = newGovernor(2500, 4000, 150, true);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");

        governor.reserve("w1", t0);
        governor.recordCall("w1", "gemini-flash", VlmScoreReadingResult.ok(
                new VlmScoreReading(0, 0, 0, 0, ServerSide.UNKNOWN, 0.9),
                Duration.ofMillis(400), 100, 20, 0.0123), t0.plusMillis(400));

        CostGovernor.Snapshot snap = governor.snapshot(t0.plusSeconds(1));
        assertEquals(1, snap.dailyCalls());
        assertEquals(0.0123, snap.dailyCostUsd(), 1e-9);
    }

    @Test
    void recordCallIgnoresNegativeCost() {
        CostGovernor governor = newGovernor(2500, 4000, 150, true);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");

        governor.reserve("w1", t0);
        governor.recordCall("w1", "claude-haiku", VlmScoreReadingResult.error(
                "boom", Duration.ofMillis(100)), t0.plusMillis(100));

        assertEquals(0.0, governor.snapshot(t0).dailyCostUsd());
    }

    @Test
    void disabledGovernorAllowsEverything() {
        CostGovernor governor = newGovernor(0, 0, 0, false);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");
        assertTrue(governor.reserve("w1", t0).allowed());
        assertTrue(governor.reserve("w1", t0).allowed());
        assertTrue(governor.reserve("w1", t0).allowed());

        Optional<CostGovernor.Verdict> staticVerdict = governor.staticVerdictForDisabled();
        assertTrue(staticVerdict.isPresent());
        assertTrue(staticVerdict.get().allowed());
    }

    @Test
    void anonymousWorkerIdIsAllowedWithoutHourlyTracking() {
        CostGovernor governor = newGovernor(2500, 4000, 1, true);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");
        assertTrue(governor.reserve(null, t0).allowed());
        assertTrue(governor.reserve("", t0).allowed());
    }

    @Test
    void constructorRejectsNegativeOrInvalidCaps() {
        assertThrows(IllegalArgumentException.class, () -> newGovernor(-1, 10, 10, true));
        assertThrows(IllegalArgumentException.class, () -> newGovernor(10, 5, 10, true));
        assertThrows(IllegalArgumentException.class, () -> new CostGovernor(1, 1, 1, true, Clock.systemUTC(), null));
    }

    @Test
    void snapshotReportsExhaustedAndOverSoftCap() {
        CostGovernor governor = newGovernor(1, 2, 10, true);
        Instant t0 = Instant.parse("2026-05-17T10:00:00Z");
        governor.reserve("w1", t0);
        governor.reserve("w2", t0);

        CostGovernor.Snapshot snap = governor.snapshot(t0);
        assertTrue(snap.exhausted());
        assertTrue(snap.overSoftCap());
    }

    private CostGovernor newGovernor(int soft, int hard, int hourly, boolean enabled) {
        return new CostGovernor(soft, hard, hourly, enabled, Clock.systemUTC(),
                new StreamVlmMetrics(new SimpleMeterRegistry()));
    }
}
