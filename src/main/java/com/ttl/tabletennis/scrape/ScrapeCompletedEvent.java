package com.ttl.tabletennis.scrape;

import java.time.LocalDateTime;

/**
 * Published by {@link TtSeriesScraper} when a scrape run finishes with
 * status {@code SUCCESS} and {@code savedMatches > 0}. Listeners that
 * need to react to fresh match data (e.g. the ratings auto-rebuild
 * listener) subscribe via {@code @EventListener}.
 *
 * <p>The event is fired on the thread that ended the scrape; listeners
 * should annotate with {@code @Async} if they want to offload work.
 */
public record ScrapeCompletedEvent(int runId,
                                   String mode,
                                   int savedMatches,
                                   LocalDateTime finishedAt) {
}
