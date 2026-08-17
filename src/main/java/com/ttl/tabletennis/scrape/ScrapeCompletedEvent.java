package com.ttl.tabletennis.scrape;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Published by {@link TtSeriesScraper} whenever a completed run committed one
 * or more new or updated match rows. This also covers partially successful
 * batches whose final run status is failed: committed data still needs to flow
 * into ratings and prediction caches immediately. Listeners subscribe via
 * {@code @EventListener}.
 *
 * <p>The event is fired on the thread that ended the scrape; listeners
 * should annotate with {@code @Async} if they want to offload work.
 */
public record ScrapeCompletedEvent(int runId,
                                   String mode,
                                   int savedMatches,
                                   int newMatches,
                                   int updatedMatches,
                                   Set<Long> affectedPlayerIds,
                                   LocalDateTime finishedAt) {

    public ScrapeCompletedEvent {
        affectedPlayerIds = affectedPlayerIds == null ? Set.of() : Set.copyOf(affectedPlayerIds);
    }

    /** Compatibility constructor for older callers and tests. */
    public ScrapeCompletedEvent(int runId,
                               String mode,
                               int savedMatches,
                               LocalDateTime finishedAt) {
        this(runId, mode, savedMatches, savedMatches, 0, Set.of(), finishedAt);
    }
}
