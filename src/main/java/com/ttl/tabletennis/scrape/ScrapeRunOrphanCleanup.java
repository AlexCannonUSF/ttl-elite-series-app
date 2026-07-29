package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.ScrapeRun;
import com.ttl.tabletennis.repository.ScrapeRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Finalises {@code scrape_run} rows whose JVM died mid-run.
 *
 * <p>{@link TtSeriesScraper#markFinish()} is the only path that writes a
 * terminal {@code SUCCESS} / {@code FAILED} status, and it only runs when
 * the scraper thread completes normally. A kill -9, OOM, or developer Ctrl-C
 * skips that path and leaves the row stuck in {@code RUNNING} forever. The
 * `/v3/ops/scrape` page then reads that row as "still running" the next
 * time it loads, which is misleading.
 *
 * <p>This component fires once on {@link ApplicationReadyEvent} and walks
 * every {@code RUNNING} row, flipping it to {@code FAILED} with an explicit
 * {@code JVM_RESTART} reason. It also exposes
 * {@link #finalizeOrphans()} for the admin endpoint so operators can clean
 * up after a failed scrape without restarting the backend.
 */
@Component
public class ScrapeRunOrphanCleanup {

    private static final Logger log = LoggerFactory.getLogger(ScrapeRunOrphanCleanup.class);
    private static final String RUNNING_STATUS = "RUNNING";
    private static final String FAILED_STATUS = "FAILED";
    private static final String ORPHAN_REASON = "JVM_RESTART";

    private final ScrapeRunRepository repository;

    public ScrapeRunOrphanCleanup(ScrapeRunRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            CleanupResult result = finalizeOrphans();
            if (result.finalized() > 0) {
                log.info("[scrape-orphan] finalized {} orphaned scrape_run rows at boot", result.finalized());
            }
        } catch (RuntimeException ex) {
            log.warn("[scrape-orphan] boot-time cleanup failed: {}", ex.getMessage(), ex);
        }
    }

    /** Walk every RUNNING row and finalize it. Returns the count. Idempotent. */
    public CleanupResult finalizeOrphans() {
        List<ScrapeRun> orphans = repository.findByStatus(RUNNING_STATUS);
        if (orphans.isEmpty()) {
            return new CleanupResult(0);
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = 0;
        for (ScrapeRun row : orphans) {
            if (row == null) {
                continue;
            }
            row.setStatus(FAILED_STATUS);
            row.setFinishedAt(now);
            String existingError = row.getErrorMessage();
            String stamp = "finalized at boot (" + ORPHAN_REASON + ")";
            row.setErrorMessage(existingError == null || existingError.isBlank()
                    ? stamp
                    : existingError + " | " + stamp);
            repository.save(row);
            updated++;
        }
        return new CleanupResult(updated);
    }

    public record CleanupResult(int finalized) { }
}
