package com.ttl.tabletennis.cv;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StreamVlmMetricsTests {

    @Test
    void recordCallIncrementsCallCostAndTokenCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StreamVlmMetrics metrics = new StreamVlmMetrics(registry);

        VlmScoreReadingResult result = VlmScoreReadingResult.ok(
                new VlmScoreReading(0, 0, 0, 0, ServerSide.UNKNOWN, 0.9),
                Duration.ofMillis(220), 120, 16, 0.00045);
        metrics.recordCall("gemini-flash", result);

        Counter calls = counter(registry, StreamVlmMetrics.METRIC_CALLS_TOTAL, "model", "gemini-flash", "reason", "ok");
        assertEquals(1.0, calls.count());

        Counter cost = counter(registry, StreamVlmMetrics.METRIC_COST_USD_TOTAL, "model", "gemini-flash");
        assertEquals(0.00045, cost.count(), 1e-9);

        Counter tokensIn = counter(registry, StreamVlmMetrics.METRIC_TOKENS_TOTAL, "model", "gemini-flash", "kind", "input");
        assertEquals(120, tokensIn.count());
        Counter tokensOut = counter(registry, StreamVlmMetrics.METRIC_TOKENS_TOTAL, "model", "gemini-flash", "kind", "output");
        assertEquals(16, tokensOut.count());

        Timer latency = timer(registry, StreamVlmMetrics.METRIC_LATENCY, "model", "gemini-flash", "reason", "ok");
        assertEquals(1L, latency.count());
        assertEquals(220.0, latency.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 1.0);
    }

    @Test
    void recordCallSkipsCostAndTokensWhenZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StreamVlmMetrics metrics = new StreamVlmMetrics(registry);

        VlmScoreReadingResult result = VlmScoreReadingResult.error("boom", Duration.ofMillis(50));
        metrics.recordCall("claude-haiku", result);

        assertEquals(1.0, counter(registry, StreamVlmMetrics.METRIC_CALLS_TOTAL, "model", "claude-haiku", "reason", "error").count());
        assertNull(registry.find(StreamVlmMetrics.METRIC_COST_USD_TOTAL).counter());
        assertNull(registry.find(StreamVlmMetrics.METRIC_TOKENS_TOTAL).counter());
    }

    @Test
    void recordBlockIncrementsBlockCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StreamVlmMetrics metrics = new StreamVlmMetrics(registry);

        metrics.recordBlock("daily_hard_cap");
        metrics.recordBlock("daily_hard_cap");
        metrics.recordBlock("worker_hourly_cap");

        assertEquals(2.0, counter(registry, StreamVlmMetrics.METRIC_BLOCKS_TOTAL, "reason", "daily_hard_cap").count());
        assertEquals(1.0, counter(registry, StreamVlmMetrics.METRIC_BLOCKS_TOTAL, "reason", "worker_hourly_cap").count());
    }

    @Test
    void dailyGaugesReflectLatestUpdate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StreamVlmMetrics metrics = new StreamVlmMetrics(registry);

        metrics.updateDailyCalls("all", 12);
        metrics.updateDailyCalls("all", 42);
        metrics.updateDailyCost("gemini-flash", 0.123);

        Gauge calls = gauge(registry, StreamVlmMetrics.METRIC_DAILY_CALLS, "model", "all");
        assertEquals(42.0, calls.value());

        Gauge cost = gauge(registry, StreamVlmMetrics.METRIC_DAILY_COST, "model", "gemini-flash");
        assertEquals(0.123, cost.value(), 1e-9);
    }

    @Test
    void nullModelFallsBackToUnknownTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StreamVlmMetrics metrics = new StreamVlmMetrics(registry);

        VlmScoreReadingResult result = VlmScoreReadingResult.unreadable("u", Duration.ZERO, 0, 0, 0.0);
        metrics.recordCall(null, result);

        assertNotNull(counter(registry, StreamVlmMetrics.METRIC_CALLS_TOTAL, "model", "unknown", "reason", "unreadable"));
    }

    private static Counter counter(SimpleMeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        assertNotNull(counter, "expected counter " + name + " tags=" + String.join(",", tags));
        return counter;
    }

    private static Timer timer(SimpleMeterRegistry registry, String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        assertNotNull(timer, "expected timer " + name);
        return timer;
    }

    private static Gauge gauge(SimpleMeterRegistry registry, String name, String... tags) {
        Gauge gauge = registry.find(name).tags(tags).gauge();
        assertNotNull(gauge, "expected gauge " + name);
        return gauge;
    }
}
