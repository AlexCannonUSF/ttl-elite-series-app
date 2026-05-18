package com.ttl.tabletennis.cv;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamCvVlmFallbackHookTests {

    @Test
    void operatorForceIsConsumedOnceForTheNextFrame() {
        StreamCvVlmFallbackHook hook = new StreamCvVlmFallbackHook(true, Duration.ofMinutes(5));
        Instant now = Instant.parse("2026-04-19T12:00:00Z");

        StreamCvVlmForceRequest request = hook.forceNextFrame(
                "match-1",
                "ops",
                "scoreboard occluded",
                Duration.ofMinutes(2),
                now
        ).orElseThrow();
        assertEquals(1, hook.activeForceCount(now));

        Optional<StreamCvVlmFallbackDecision> decision = hook.consumeForcedDecision(
                "match-1",
                "match-1:22",
                now.plusSeconds(10)
        );

        assertEquals("match-1", request.matchId());
        assertTrue(decision.isPresent());
        assertEquals(StreamCvVlmFallbackTrigger.OPERATOR_FORCE, decision.get().trigger());
        assertEquals("match-1:22", decision.get().frameId());
        assertEquals("ops", decision.get().requestedBy());
        assertTrue(hook.consumeForcedDecision("match-1", "match-1:23", now.plusSeconds(11)).isEmpty());
    }

    @Test
    void expiredOperatorForceDropsWithoutDecision() {
        StreamCvVlmFallbackHook hook = new StreamCvVlmFallbackHook(true, Duration.ofSeconds(5));
        Instant now = Instant.parse("2026-04-19T12:00:00Z");

        hook.forceNextFrame("match-1", "ops", "late frame", Duration.ofSeconds(5), now);

        assertEquals(0, hook.activeForceCount(now.plusSeconds(5)));
        assertTrue(hook.consumeForcedDecision("match-1", "match-1:9", now.plusSeconds(6)).isEmpty());
    }

    @Test
    void systemFallbackDecisionsAreReadyButCanBeDisabled() {
        StreamCvVlmFallbackHook enabled = new StreamCvVlmFallbackHook(true, Duration.ofMinutes(5));
        StreamCvVlmFallbackHook disabled = new StreamCvVlmFallbackHook(false, Duration.ofMinutes(5));
        Instant now = Instant.parse("2026-04-19T12:00:00Z");

        Optional<StreamCvVlmFallbackDecision> exhausted = enabled.classicCvExhausted("match-1", "match-1:44", 5, now);
        Optional<StreamCvVlmFallbackDecision> disagreement = enabled.ocrDisagreement("match-2", "match-2:8", "paddle/easy mismatch", now);

        assertTrue(exhausted.isPresent());
        assertEquals(StreamCvVlmFallbackTrigger.CLASSIC_CV_EXHAUSTED, exhausted.get().trigger());
        assertTrue(disagreement.isPresent());
        assertEquals(StreamCvVlmFallbackTrigger.OCR_DISAGREEMENT, disagreement.get().trigger());
        assertFalse(disabled.forceNextFrame("match-1", "ops", "disabled", now).isPresent());
        assertFalse(disabled.classicCvExhausted("match-1", "match-1:44", 5, now).isPresent());
    }
}
