package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ttl.tabletennis.service.papertrade.ObservationClassifier.OBSERVATION_SOURCE_SCORE_FEED;
import static com.ttl.tabletennis.service.papertrade.ObservationClassifier.hasExplicitCompletionSignal;
import static com.ttl.tabletennis.service.papertrade.ObservationClassifier.inferObservationSourceKind;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.isFinishedPhase;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.isLateLikePhase;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.normalizeKey;

/**
 * Build a {@link RowLookup} index over a batch of live-odds rows. The same
 * row is registered under every key it could be retrieved by — dedupe,
 * matchup, external event id, source-feed id, pair+start, pair.
 *
 * <p>Eighteenth §4 slice — pure-function indexer. When two rows share a
 * key, the one that ranks higher under {@link #settlementRowRank(LiveOddsRecommendationDto)}
 * wins; ties are broken by parsed score-pair count (more frames = fresher
 * row), then by score-feed source preference.
 *
 * <p>Why a static utility and not a Spring service: no repository, no
 * config, no clock. Pure compute over the input list.
 */
public final class RowLookupBuilder {

    private RowLookupBuilder() {
        // utility class — not instantiable
    }

    /**
     * Build a multi-index lookup from the supplied rows. {@code null} or
     * empty input yields an empty lookup. Null rows in the list are
     * silently skipped.
     */
    public static RowLookup build(List<LiveOddsRecommendationDto> rows) {
        Map<String, LiveOddsRecommendationDto> byDedupe = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> byEvent = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> byExternalEventId = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> bySourceFeedEventId = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> byPairStart = new HashMap<>();
        Map<String, LiveOddsRecommendationDto> byPair = new HashMap<>();
        List<LiveOddsRecommendationDto> allRows = new ArrayList<>();
        if (rows == null) {
            return new RowLookup(byDedupe, byEvent, byExternalEventId, bySourceFeedEventId, byPairStart, byPair, allRows);
        }
        for (LiveOddsRecommendationDto row : rows) {
            if (row == null) {
                continue;
            }
            allRows.add(row);
            String dedupeKey = row.suggestedDedupeKey();
            if (!StringUtils.hasText(dedupeKey) && StringUtils.hasText(row.matchupKey()) && StringUtils.hasText(row.suggestedSide())) {
                dedupeKey = row.matchupKey().trim() + "|" + normalizeKey(row.suggestedSide());
            }
            putPreferredRow(byDedupe, dedupeKey, row);
            putPreferredRow(byEvent, row.matchupKey(), row);
            putPreferredRow(byEvent, MatchKeyBuilder.buildEventKey(row), row);
            String externalEventId = StringUtils.hasText(row.externalEventId())
                    ? row.externalEventId().trim()
                    : MatchKeyBuilder.extractExternalEventId(row.source());
            putPreferredRow(byExternalEventId, externalEventId, row);
            putPreferredRow(bySourceFeedEventId, row.sourceFeedEventId(), row);
            String pairStartKey = MatchKeyBuilder.toPairStartKey(
                    row.player1Id(),
                    row.player1Name(),
                    row.player2Id(),
                    row.player2Name(),
                    row.startTimeIso()
            );
            putPreferredRow(byPairStart, pairStartKey, row);
            String namePairStartKey = MatchKeyBuilder.toPairStartKey(
                    null,
                    row.player1Name(),
                    null,
                    row.player2Name(),
                    row.startTimeIso()
            );
            putPreferredRow(byPairStart, namePairStartKey, row);
            String pairKey = MatchKeyBuilder.toPairKey(
                    row.player1Id(),
                    row.player1Name(),
                    row.player2Id(),
                    row.player2Name()
            );
            putPreferredRow(byPair, pairKey, row);
            String namePairKey = MatchKeyBuilder.toPairKey(
                    null,
                    row.player1Name(),
                    null,
                    row.player2Name()
            );
            putPreferredRow(byPair, namePairKey, row);
        }
        return new RowLookup(byDedupe, byEvent, byExternalEventId, bySourceFeedEventId, byPairStart, byPair, allRows);
    }

    /**
     * Insert {@code candidate} under {@code rawKey} if the slot is empty or
     * the candidate outranks the current occupant per
     * {@link #preferSettlementRow(LiveOddsRecommendationDto, LiveOddsRecommendationDto)}.
     * Silently no-ops on null inputs or blank keys.
     */
    public static void putPreferredRow(Map<String, LiveOddsRecommendationDto> index,
                                       String rawKey,
                                       LiveOddsRecommendationDto candidate) {
        if (index == null || candidate == null || !StringUtils.hasText(rawKey)) {
            return;
        }
        String key = rawKey.trim();
        LiveOddsRecommendationDto current = index.get(key);
        if (current == null || preferSettlementRow(candidate, current)) {
            index.put(key, candidate);
        }
    }

    /**
     * Settlement-side preference ranking: higher {@link #settlementRowRank}
     * wins; otherwise more parsed score pairs wins; otherwise score-feed
     * source beats market-board source. Returns true when {@code candidate}
     * should replace {@code current}.
     */
    public static boolean preferSettlementRow(LiveOddsRecommendationDto candidate, LiveOddsRecommendationDto current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        int candidateRank = settlementRowRank(candidate);
        int currentRank = settlementRowRank(current);
        if (candidateRank != currentRank) {
            return candidateRank > currentRank;
        }
        int candidatePairs = ScorePair.parseAll(candidate.liveScore()).size();
        int currentPairs = ScorePair.parseAll(current.liveScore()).size();
        if (candidatePairs != currentPairs) {
            return candidatePairs > currentPairs;
        }
        boolean candidateScoreSource = OBSERVATION_SOURCE_SCORE_FEED.equals(inferObservationSourceKind(candidate));
        boolean currentScoreSource = OBSERVATION_SOURCE_SCORE_FEED.equals(inferObservationSourceKind(current));
        if (candidateScoreSource != currentScoreSource) {
            return candidateScoreSource;
        }
        return false;
    }

    /**
     * Composite rank: live score present (+4), explicit completion (+5),
     * finished phase (+3), late-like phase (+2), live flag (+1). Used by
     * {@link #preferSettlementRow} as the primary settlement-preference
     * signal.
     */
    public static int settlementRowRank(LiveOddsRecommendationDto row) {
        if (row == null) {
            return 0;
        }
        int rank = 0;
        if (StringUtils.hasText(row.liveScore())) {
            rank += 4;
        }
        if (hasExplicitCompletionSignal(row)) {
            rank += 5;
        }
        if (isFinishedPhase(row.matchPhase())) {
            rank += 3;
        }
        if (isLateLikePhase(row.matchPhase())) {
            rank += 2;
        }
        if (row.live()) {
            rank += 1;
        }
        return rank;
    }
}
