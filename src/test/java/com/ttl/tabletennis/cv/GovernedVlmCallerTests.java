package com.ttl.tabletennis.cv;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedVlmCallerTests {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-05-17T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void callPassesThroughWhenGovernorAllows() {
        RecordingClient client = new RecordingClient("gemini-flash", VlmScoreReadingResult.ok(
                new VlmScoreReading(1, 0, 7, 4, ServerSide.TOP, 0.9),
                Duration.ofMillis(300), 100, 12, 0.0001));
        CostGovernor governor = newGovernor(2500, 4000, 5, true);
        GovernedVlmCaller caller = new GovernedVlmCaller(client, governor, new VlmCallRecorder(Optional.empty()), fixedClock);

        GovernedVlmCaller.Outcome outcome = caller.call("w1", request());

        assertEquals(GovernedVlmCaller.Outcome.Status.COMPLETED, outcome.status());
        assertSame(client.next, outcome.result().orElseThrow());
        assertFalse(outcome.overSoftCap());
        assertEquals(1, client.invocations);
        assertEquals(1, governor.snapshot(fixedClock.instant()).dailyCalls());
    }

    @Test
    void callIsBlockedWhenGovernorDenies() {
        RecordingClient client = new RecordingClient("gemini-flash", VlmScoreReadingResult.ok(
                new VlmScoreReading(0, 0, 0, 0, ServerSide.UNKNOWN, 0.5),
                Duration.ofMillis(50), 10, 4, 0.0));
        CostGovernor governor = newGovernor(2500, 4000, 1, true);
        GovernedVlmCaller caller = new GovernedVlmCaller(client, governor, new VlmCallRecorder(Optional.empty()), fixedClock);

        assertEquals(GovernedVlmCaller.Outcome.Status.COMPLETED, caller.call("w1", request()).status());
        GovernedVlmCaller.Outcome second = caller.call("w1", request());
        assertEquals(GovernedVlmCaller.Outcome.Status.BLOCKED, second.status());
        assertEquals("worker_hourly_cap", second.reason());
        assertEquals(1, client.invocations);
    }

    @Test
    void callIsSkippedWhenUnderlyingClientDisabled() {
        VlmClient disabled = new DisabledVlmClient();
        CostGovernor governor = newGovernor(2500, 4000, 5, true);
        GovernedVlmCaller caller = new GovernedVlmCaller(disabled, governor, new VlmCallRecorder(Optional.empty()), fixedClock);

        GovernedVlmCaller.Outcome outcome = caller.call("w1", request());
        assertEquals(GovernedVlmCaller.Outcome.Status.SKIPPED, outcome.status());
        assertEquals("vlm-disabled", outcome.reason());
        assertEquals(0, governor.snapshot(fixedClock.instant()).dailyCalls());
    }

    @Test
    void callExposesOverSoftCapFlag() {
        RecordingClient client = new RecordingClient("gemini-flash", VlmScoreReadingResult.ok(
                new VlmScoreReading(0, 0, 0, 0, ServerSide.UNKNOWN, 0.5),
                Duration.ofMillis(50), 0, 0, 0.0));
        CostGovernor governor = newGovernor(1, 5, 10, true);
        GovernedVlmCaller caller = new GovernedVlmCaller(client, governor, new VlmCallRecorder(Optional.empty()), fixedClock);

        GovernedVlmCaller.Outcome first = caller.call("w1", request());
        GovernedVlmCaller.Outcome second = caller.call("w2", request());
        assertFalse(first.overSoftCap());
        assertTrue(second.overSoftCap());
    }

    @Test
    void recorderIsInvokedOnCompletedCalls() {
        RecordingClient client = new RecordingClient("claude-haiku", VlmScoreReadingResult.ok(
                new VlmScoreReading(0, 0, 0, 0, ServerSide.UNKNOWN, 0.5),
                Duration.ofMillis(75), 50, 6, 0.0002));
        CostGovernor governor = newGovernor(2500, 4000, 10, true);
        RecordingRecorder recorder = new RecordingRecorder();
        GovernedVlmCaller caller = new GovernedVlmCaller(client, governor, recorder, fixedClock);

        caller.call("w1", request());

        assertEquals(1, recorder.calls.size());
        assertEquals("w1", recorder.calls.get(0).workerId());
        assertEquals("claude-haiku", recorder.calls.get(0).engineId());
    }

    @Test
    void constructorRejectsNulls() {
        VlmClient client = new DisabledVlmClient();
        CostGovernor governor = newGovernor(2500, 4000, 10, true);
        VlmCallRecorder recorder = new VlmCallRecorder(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> new GovernedVlmCaller(null, governor, recorder, fixedClock));
        assertThrows(IllegalArgumentException.class, () -> new GovernedVlmCaller(client, null, recorder, fixedClock));
        assertThrows(IllegalArgumentException.class, () -> new GovernedVlmCaller(client, governor, null, fixedClock));
    }

    private VlmRequest request() {
        return new VlmRequest("frame".getBytes(), "image/jpeg", "match-1", "frame-1", Duration.ofSeconds(2));
    }

    private CostGovernor newGovernor(int soft, int hard, int hourly, boolean enabled) {
        return new CostGovernor(soft, hard, hourly, enabled, fixedClock,
                new StreamVlmMetrics(new SimpleMeterRegistry()));
    }

    private static final class RecordingClient implements VlmClient {
        private final String engineId;
        private final VlmScoreReadingResult next;
        int invocations;

        RecordingClient(String engineId, VlmScoreReadingResult next) {
            this.engineId = engineId;
            this.next = next;
        }

        @Override
        public String engineId() {
            return engineId;
        }

        @Override
        public VlmScoreReadingResult readScoreboard(VlmRequest request) {
            invocations++;
            return next;
        }
    }

    private static final class RecordingRecorder extends VlmCallRecorder {
        record Call(String workerId, String engineId, VlmScoreReadingResult result) { }

        final List<Call> calls = new ArrayList<>();

        RecordingRecorder() {
            super(Optional.empty());
        }

        @Override
        public void record(String workerId, String matchId, String engineId, VlmRequest request,
                           VlmScoreReadingResult result, Instant now) {
            calls.add(new Call(workerId, engineId, result));
        }
    }
}
