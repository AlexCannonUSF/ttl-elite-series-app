package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Classify a {@link LiveOddsRecommendationDto} by its source-kind +
 * completion signal — used by the row lookup chain, observation tracking,
 * and update apply paths to decide which row "wins" a tie.
 *
 * <p>Seventeenth §4 slice — pure-function shared classifier so the
 * upcoming {@code RowLookupBuilder} doesn't have to drag this logic with
 * it. Behaviour verbatim from the original two private helpers in
 * {@code PaperTradingService}.
 *
 * <p>The observation-source constants are duplicated with
 * {@link IntegrityService} (string literals get inlined at compile-time,
 * so cohesion is preserved at runtime); promoting them to a shared
 * {@code PaperTradingConstants} class can come later if we want a single
 * source of truth.
 */
public final class ObservationClassifier {

    /** Market board (odds feed) — the default source when nothing else matches. */
    public static final String OBSERVATION_SOURCE_MARKET_BOARD = "MARKET_BOARD";
    /** Live score feed — preferred over the market board when both are available. */
    public static final String OBSERVATION_SOURCE_SCORE_FEED = "SCORE_FEED";

    private ObservationClassifier() {
        // utility class — not instantiable
    }

    /**
     * Returns one of the {@code OBSERVATION_SOURCE_*} constants based on the
     * row's {@code sourceType} / {@code source} fields. Defaults to
     * {@link #OBSERVATION_SOURCE_MARKET_BOARD} on null / blank / non-SCORE.
     */
    public static String inferObservationSourceKind(LiveOddsRecommendationDto row) {
        if (row == null) {
            return OBSERVATION_SOURCE_MARKET_BOARD;
        }
        if (StringUtils.hasText(row.sourceType())) {
            String sourceType = row.sourceType().trim().toUpperCase(Locale.ROOT);
            if (sourceType.contains("SCORE")) {
                return OBSERVATION_SOURCE_SCORE_FEED;
            }
        }
        if (!StringUtils.hasText(row.source())) {
            return OBSERVATION_SOURCE_MARKET_BOARD;
        }
        String source = row.source().trim().toUpperCase(Locale.ROOT);
        if (source.contains("SCORE")) {
            return OBSERVATION_SOURCE_SCORE_FEED;
        }
        return OBSERVATION_SOURCE_MARKET_BOARD;
    }

    /** True when the row explicitly signals match completion via the feed
     *  ({@code matchCompleted} or {@code resulted}) — not phase-inferred. */
    public static boolean hasExplicitCompletionSignal(LiveOddsRecommendationDto row) {
        return row != null && (row.matchCompleted() || row.resulted());
    }
}
