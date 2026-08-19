package com.ttl.tabletennis.cv;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enforces the Stream-CV Spec §8.2 VLM budget caps. Pure logic — no Spring;
 * Phase 04 item 6.
 *
 * <ul>
 *     <li>Per-worker cap: 150 VLM calls per rolling 60-minute window.</li>
 *     <li>Global daily soft cap: 2500 calls/day (warn, allow).</li>
 *     <li>Global daily hard cap: 4000 calls/day (deny; flips global state to
 *     {@code VLM_EXHAUSTED} until the next UTC day rollover).</li>
 * </ul>
 *
 * Cost in USD is accumulated separately so it can be reported as a gauge by
 * {@link StreamVlmMetrics} and used by the {@code StreamVLMCostSpike}
 * Prometheus alert.
 */
public class CostGovernor {

    public static final int DEFAULT_DAILY_SOFT_CAP = 2500;
    public static final int DEFAULT_DAILY_HARD_CAP = 4000;
    public static final int DEFAULT_PER_WORKER_HOUR_CAP = 150;

    private final int dailySoftCap;
    private final int dailyHardCap;
    private final int perWorkerHourCap;
    private final boolean enabled;
    private final Clock clock;
    private final StreamVlmMetrics metrics;

    private final Map<String, Deque<Instant>> hourlyWindowsByWorker = new ConcurrentHashMap<>();
    private final AtomicReference<DailyState> dailyStateRef = new AtomicReference<>();

    public CostGovernor(int dailySoftCap,
                        int dailyHardCap,
                        int perWorkerHourCap,
                        boolean enabled,
                        Clock clock,
                        StreamVlmMetrics metrics) {
        if (dailySoftCap < 0 || dailyHardCap < 0 || perWorkerHourCap < 0) {
            throw new IllegalArgumentException("caps must be non-negative");
        }
        if (dailyHardCap < dailySoftCap) {
            throw new IllegalArgumentException("dailyHardCap must be >= dailySoftCap");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.dailySoftCap = dailySoftCap;
        this.dailyHardCap = dailyHardCap;
        this.perWorkerHourCap = perWorkerHourCap;
        this.enabled = enabled;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.metrics = metrics;
        this.dailyStateRef.set(new DailyState(today(this.clock.instant())));
    }

    public Verdict reserve(String workerId, Instant now) {
        Instant effectiveNow = now == null ? clock.instant() : now;
        if (!enabled) {
            return Verdict.allowed("governor-disabled", false);
        }
        if (workerId == null || workerId.isBlank()) {
            return Verdict.allowed("anonymous", false);
        }

        DailyState daily = currentDailyState(effectiveNow);
        int dailyCount = daily.calls.get();
        if (dailyCount >= dailyHardCap) {
            metrics.recordBlock("daily_hard_cap");
            return Verdict.denied("daily_hard_cap");
        }

        Deque<Instant> hourlyWindow = hourlyWindowsByWorker.computeIfAbsent(workerId, key -> new ArrayDeque<>());
        synchronized (hourlyWindow) {
            pruneHourly(hourlyWindow, effectiveNow);
            if (hourlyWindow.size() >= perWorkerHourCap) {
                metrics.recordBlock("worker_hourly_cap");
                return Verdict.denied("worker_hourly_cap");
            }
            hourlyWindow.addLast(effectiveNow);
        }

        int newDailyCount = daily.calls.incrementAndGet();
        metrics.updateDailyCalls("all", newDailyCount);
        boolean overSoftCap = newDailyCount > dailySoftCap;
        return Verdict.allowed(overSoftCap ? "over_soft_cap" : "ok", overSoftCap);
    }

    public void recordCall(String workerId,
                           String engineId,
                           VlmScoreReadingResult result,
                           Instant now) {
        Instant effectiveNow = now == null ? clock.instant() : now;
        DailyState daily = currentDailyState(effectiveNow);
        double cost = Math.max(0.0, result.costEstimateUsd());
        if (cost > 0.0) {
            double total;
            synchronized (daily.costLock) {
                daily.costUsd += cost;
                total = daily.costUsd;
            }
            metrics.updateDailyCost(engineId == null ? "all" : engineId, total);
        }
        metrics.recordCall(engineId, result);
    }

    public Snapshot snapshot(Instant now) {
        Instant effectiveNow = now == null ? clock.instant() : now;
        DailyState daily = currentDailyState(effectiveNow);
        double cost;
        synchronized (daily.costLock) {
            cost = daily.costUsd;
        }
        int dailyCount = daily.calls.get();
        return new Snapshot(
                daily.date,
                dailyCount,
                cost,
                dailyCount >= dailyHardCap,
                dailyCount > dailySoftCap,
                hourlyWindowsByWorker.size()
        );
    }

    private DailyState currentDailyState(Instant now) {
        LocalDate today = today(now);
        DailyState existing = dailyStateRef.get();
        if (existing != null && existing.date.equals(today)) {
            return existing;
        }
        DailyState rotated = new DailyState(today);
        if (dailyStateRef.compareAndSet(existing, rotated)) {
            metrics.updateDailyCalls("all", 0.0);
            return rotated;
        }
        return dailyStateRef.get();
    }

    private void pruneHourly(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(1));
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
            window.pollFirst();
        }
    }

    private static LocalDate today(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate();
    }

    public int dailySoftCap() { return dailySoftCap; }
    public int dailyHardCap() { return dailyHardCap; }
    public int perWorkerHourCap() { return perWorkerHourCap; }
    public boolean enabled() { return enabled; }

    public record Verdict(boolean allowed, String reason, boolean overSoftCap) {

        public static Verdict allowed(String reason, boolean overSoftCap) {
            return new Verdict(true, reason, overSoftCap);
        }

        public static Verdict denied(String reason) {
            return new Verdict(false, reason, false);
        }
    }

    public record Snapshot(LocalDate date,
                           int dailyCalls,
                           double dailyCostUsd,
                           boolean exhausted,
                           boolean overSoftCap,
                           int workersTracked) { }

    private static final class DailyState {
        final LocalDate date;
        final AtomicInteger calls = new AtomicInteger(0);
        final Object costLock = new Object();
        double costUsd;

        DailyState(LocalDate date) {
            this.date = date;
        }
    }

    public Optional<Verdict> staticVerdictForDisabled() {
        return enabled ? Optional.empty() : Optional.of(Verdict.allowed("governor-disabled", false));
    }
}
