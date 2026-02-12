package com.ttl.tabletennis.scrape;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks progress (% done) and estimates remaining time based on average throughput.
 * Thread-safe for the single-scraper use here; logs are throttled to ~1/sec.
 */
public final class ProgressTracker {
    private static final Logger log = LoggerFactory.getLogger(ProgressTracker.class);

    private volatile int total;
    private volatile int done;
    private volatile int saved;

    private final long t0Ns = System.nanoTime();
    private volatile long lastLogNs = 0L;

    private ProgressTracker(int total) {
        this.total = Math.max(1, total);
        this.done = 0;
        this.saved = 0;
    }

    public static ProgressTracker create(int total) {
        return new ProgressTracker(total);
    }

    /** If we discover more (or fewer) items than expected, adjust the total. */
    public synchronized void addToTotal(int delta) {
        int newTotal = this.total + delta;
        this.total = Math.max(1, newTotal);
    }

    /** Record one unit of work; if it resulted in a DB insert, mark saved=true. */
    public synchronized void tick(boolean wasSaved, String context) {
        done++;
        if (wasSaved) saved++;

        long now = System.nanoTime();
        if (shouldLog(now) || done >= total) {
            lastLogNs = now;
            String line = buildLine(now, context);
            log.info(line);
        }
    }

    public synchronized void finish(String message) {
        long now = System.nanoTime();
        String line = buildLine(now, message);
        log.info(line);
    }

    // ----------------- internals -----------------

    private boolean shouldLog(long nowNs) {
        // Throttle to ~1 log/sec
        return (nowNs - lastLogNs) >= 1_000_000_000L;
    }

    private String buildLine(long nowNs, String context) {
        double pct = 100.0 * done / Math.max(1, total);
        long elapsedSec = Math.max(1, Math.round((nowNs - t0Ns) / 1_000_000_000.0));
        double ratePerSec = done / (double) elapsedSec;
        double ratePerMin = ratePerSec * 60.0;

        int remaining = Math.max(0, total - done);
        long etaSec = remaining > 0 && ratePerSec > 0.0
                ? Math.round(remaining / ratePerSec)
                : 0;

        return String.format(
                "[scrape] progress %d/%d (%.1f%%) | saved=%d | rate=%.1f/min | ETA=%s | %s",
                done, total, pct, saved, ratePerMin, pretty(etaSec), context
        );
        // Example:
        // [scrape] progress 96/120 (80.0%) | saved=90 | rate=45.2/min | ETA=00:00:32 | page 2/3 | link 8/10 | postId=4781 | match 4/12
    }

    private static String pretty(long totalSec) {
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format("%02d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }
}