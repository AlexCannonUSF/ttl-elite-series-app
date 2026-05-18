package com.ttl.tabletennis.scrape;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeedHealthTrackerTests {

    @Test
    void snapshotReportsRollingLatencyQuantilesAndStaleness() {
        MutableClock clock = new MutableClock(Instant.parse("2026-04-19T16:00:00Z"));
        FeedHealthTracker tracker = new FeedHealthTracker(clock);

        completeSuccessfulPull(tracker, clock, 100);
        completeSuccessfulPull(tracker, clock, 200);
        completeSuccessfulPull(tracker, clock, 300);

        Instant failureStartedAt = tracker.onPullStart();
        clock.advance(Duration.ofMillis(400));
        tracker.onPullFailure(failureStartedAt, new IllegalStateException("boom"));

        FeedHealth health = tracker.snapshot(SourceId.HR_MKT);
        assertEquals(0.75, health.rollingSuccessRate5m(), 1.0e-9);
        assertEquals(200.0, health.rollingP50LatencyMs(), 1.0e-9);
        assertEquals(400.0, health.rollingP95LatencyMs(), 1.0e-9);
        assertEquals(0L, health.stalenessSeconds());
        assertEquals("boom", health.lastError());

        clock.advance(Duration.ofSeconds(61));
        FeedHealth agedHealth = tracker.snapshot(SourceId.HR_MKT);
        assertEquals(-1.0, agedHealth.rollingP50LatencyMs(), 1.0e-9);
        assertEquals(-1.0, agedHealth.rollingP95LatencyMs(), 1.0e-9);
        assertEquals(61L, agedHealth.stalenessSeconds());
        assertEquals(0.75, agedHealth.rollingSuccessRate5m(), 1.0e-9);
    }

    private void completeSuccessfulPull(FeedHealthTracker tracker, MutableClock clock, long latencyMs) {
        Instant startedAt = tracker.onPullStart();
        clock.advance(Duration.ofMillis(latencyMs));
        tracker.onPullSuccess(startedAt);
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
