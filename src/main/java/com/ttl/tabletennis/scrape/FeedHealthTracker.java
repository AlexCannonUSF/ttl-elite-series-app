package com.ttl.tabletennis.scrape;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

final class FeedHealthTracker {

    private static final Duration SUCCESS_WINDOW = Duration.ofMinutes(5);
    private static final Duration LATENCY_WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Deque<Attempt> attempts = new ArrayDeque<>();
    private final Deque<LatencySample> latencySamples = new ArrayDeque<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailure = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    FeedHealthTracker() {
        this(Clock.systemUTC());
    }

    FeedHealthTracker(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    Instant onPullStart() {
        Instant startedAt = clock.instant();
        inFlight.incrementAndGet();
        return startedAt;
    }

    void onPullSuccess() {
        onPullSuccess(clock.instant());
    }

    void onPullSuccess(Instant startedAt) {
        Instant now = clock.instant();
        lastSuccess.set(now);
        pruneAndAppend(now, true);
        pruneAndAppendLatency(now, startedAt);
        lastError.set("");
        finishPull();
    }

    void onPullFailure(Exception exception) {
        onPullFailure(clock.instant(), exception);
    }

    void onPullFailure(Instant startedAt, Exception exception) {
        Instant now = clock.instant();
        lastFailure.set(now);
        pruneAndAppend(now, false);
        pruneAndAppendLatency(now, startedAt);
        lastError.set(exception == null ? "" : safeMessage(exception));
        finishPull();
    }

    FeedHealth snapshot(SourceId source) {
        Instant now = clock.instant();
        double successRate = rollingSuccessRate(now);
        double p50LatencyMs = latencyPercentile(now, 0.50);
        double p95LatencyMs = latencyPercentile(now, 0.95);
        return new FeedHealth(
                source,
                lastSuccess.get(),
                lastFailure.get(),
                successRate,
                p50LatencyMs,
                p95LatencyMs,
                stalenessSeconds(now),
                Math.max(0, inFlight.get()),
                inFlight.get() > 0 ? "ACTIVE" : "IDLE",
                lastError.get()
        );
    }

    private void finishPull() {
        inFlight.updateAndGet(current -> Math.max(0, current - 1));
    }

    private synchronized void pruneAndAppend(Instant now, boolean success) {
        pruneAttempts(now);
        attempts.addLast(new Attempt(now, success));
    }

    private synchronized double rollingSuccessRate(Instant now) {
        pruneAttempts(now);
        if (attempts.isEmpty()) {
            return 1.0;
        }
        int successCount = 0;
        for (Attempt attempt : attempts) {
            if (attempt.success()) {
                successCount++;
            }
        }
        return successCount / (double) attempts.size();
    }

    private synchronized void pruneAndAppendLatency(Instant now, Instant startedAt) {
        pruneLatencies(now);
        if (startedAt == null) {
            return;
        }
        long latencyMs = Math.max(0L, Duration.between(startedAt, now).toMillis());
        latencySamples.addLast(new LatencySample(now, latencyMs));
    }

    private synchronized double latencyPercentile(Instant now, double percentile) {
        pruneLatencies(now);
        if (latencySamples.isEmpty()) {
            return -1.0;
        }
        long[] sorted = latencySamples.stream()
                .mapToLong(LatencySample::latencyMs)
                .sorted()
                .toArray();
        int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1));
        return sorted[index];
    }

    private long stalenessSeconds(Instant now) {
        Instant lastSuccessfulPull = lastSuccess.get();
        if (lastSuccessfulPull == null) {
            return -1L;
        }
        return Math.max(0L, Duration.between(lastSuccessfulPull, now).toSeconds());
    }

    private void pruneAttempts(Instant now) {
        Instant cutoff = now.minus(SUCCESS_WINDOW);
        Iterator<Attempt> iterator = attempts.iterator();
        while (iterator.hasNext()) {
            Attempt attempt = iterator.next();
            if (attempt.at().isBefore(cutoff)) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private void pruneLatencies(Instant now) {
        Instant cutoff = now.minus(LATENCY_WINDOW);
        Iterator<LatencySample> iterator = latencySamples.iterator();
        while (iterator.hasNext()) {
            LatencySample sample = iterator.next();
            if (sample.at().isBefore(cutoff)) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.trim();
    }

    private record Attempt(Instant at, boolean success) {
    }

    private record LatencySample(Instant at, long latencyMs) {
    }
}
