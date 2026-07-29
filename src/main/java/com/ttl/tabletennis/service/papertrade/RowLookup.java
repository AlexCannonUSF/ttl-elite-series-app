package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;

import java.util.List;
import java.util.Map;

/**
 * Multi-index lookup over a batch of {@link LiveOddsRecommendationDto} rows.
 * The placement loop and settlement path use the same lookup to find the
 * current row for a bet via the fastest available key — feed event id,
 * external event id, dedupe key, then progressively looser identifiers.
 *
 * <p>Lifted from a private nested record in {@code PaperTradingService} as
 * part of the §4 decomposition (paired with {@link RowLookupBuilder}).
 * Construct via {@link RowLookupBuilder#build(List)}.
 *
 * @param byDedupe              suggested-dedupe-key → preferred row
 * @param byEvent               matchup-key / event-key → preferred row
 * @param byExternalEventId     external event id (feed) → preferred row
 * @param bySourceFeedEventId   source feed's event id → preferred row
 * @param byPairStart           pair+minute-bucket → preferred row
 * @param byPair                pair (id or name) → preferred row
 * @param allRows               in-order list of all input rows (no filtering)
 */
public record RowLookup(Map<String, LiveOddsRecommendationDto> byDedupe,
                        Map<String, LiveOddsRecommendationDto> byEvent,
                        Map<String, LiveOddsRecommendationDto> byExternalEventId,
                        Map<String, LiveOddsRecommendationDto> bySourceFeedEventId,
                        Map<String, LiveOddsRecommendationDto> byPairStart,
                        Map<String, LiveOddsRecommendationDto> byPair,
                        List<LiveOddsRecommendationDto> allRows) {
}
