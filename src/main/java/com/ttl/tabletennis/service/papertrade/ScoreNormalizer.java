package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalise a row's raw score string to match the bet's player orientation.
 * The bet was placed with a fixed P1/P2 ordering; the feed row may report
 * scores with the opposite ordering. This utility detects the mismatch and
 * swaps the digits in every {@code left-right} pair when needed.
 *
 * <p>Twentieth §4 slice — pure-function score reorientation. Used by the
 * placement loop's settlement-row interpretation path.
 *
 * <p>Behaviour verbatim from the original three private helpers in
 * {@code PaperTradingService}. Uses {@link MatchKeyBuilder#playerToken(Long, String)}
 * to canonicalise player identities for comparison.
 */
public final class ScoreNormalizer {

    /** Same regex shape as {@link ScorePair} — duplicated because the
     *  reverse logic needs {@code Matcher.appendReplacement} which doesn't
     *  fit the simpler ScorePair parser. String literal is interned, so
     *  no runtime drift. */
    private static final Pattern SCORE_PAIR_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*[-:]\\s*(\\d{1,2})");

    private ScoreNormalizer() {
        // utility class — not instantiable
    }

    /**
     * Reorient {@code rawScore} so its left-right pairs align with the
     * bet's P1/P2 ordering. Returns the trimmed input when the orientation
     * is {@link ScoreOrientation#DIRECT} or {@link ScoreOrientation#UNKNOWN}.
     */
    public static String normalizeScoreForBet(PaperTradeBet bet,
                                              String rawScore,
                                              Long rowPlayer1Id,
                                              String rowPlayer1Name,
                                              Long rowPlayer2Id,
                                              String rowPlayer2Name) {
        if (bet == null || !StringUtils.hasText(rawScore)) {
            return rawScore;
        }
        ScoreOrientation orientation = resolveScoreOrientation(
                bet.getPlayer1Id(),
                bet.getPlayer1Name(),
                bet.getPlayer2Id(),
                bet.getPlayer2Name(),
                rowPlayer1Id,
                rowPlayer1Name,
                rowPlayer2Id,
                rowPlayer2Name
        );
        if (orientation == ScoreOrientation.REVERSED) {
            return reverseScorePairs(rawScore);
        }
        return rawScore.trim();
    }

    /**
     * Compare bet's P1/P2 player tokens to row's P1/P2 player tokens.
     * Returns {@link ScoreOrientation#DIRECT} when they match in order,
     * {@link ScoreOrientation#REVERSED} when they match swapped, otherwise
     * {@link ScoreOrientation#UNKNOWN} (e.g. any token blank).
     */
    public static ScoreOrientation resolveScoreOrientation(Long betPlayer1Id,
                                                            String betPlayer1Name,
                                                            Long betPlayer2Id,
                                                            String betPlayer2Name,
                                                            Long rowPlayer1Id,
                                                            String rowPlayer1Name,
                                                            Long rowPlayer2Id,
                                                            String rowPlayer2Name) {
        String betLeft = MatchKeyBuilder.playerToken(betPlayer1Id, betPlayer1Name);
        String betRight = MatchKeyBuilder.playerToken(betPlayer2Id, betPlayer2Name);
        String rowLeft = MatchKeyBuilder.playerToken(rowPlayer1Id, rowPlayer1Name);
        String rowRight = MatchKeyBuilder.playerToken(rowPlayer2Id, rowPlayer2Name);
        if (!StringUtils.hasText(betLeft)
                || !StringUtils.hasText(betRight)
                || !StringUtils.hasText(rowLeft)
                || !StringUtils.hasText(rowRight)) {
            return ScoreOrientation.UNKNOWN;
        }
        if (betLeft.equals(rowLeft) && betRight.equals(rowRight)) {
            return ScoreOrientation.DIRECT;
        }
        if (betLeft.equals(rowRight) && betRight.equals(rowLeft)) {
            return ScoreOrientation.REVERSED;
        }
        return ScoreOrientation.UNKNOWN;
    }

    /**
     * Swap each {@code left-right} integer pair in {@code rawScore} to
     * {@code right-left}, preserving surrounding text. Returns the trimmed
     * input when the regex matches nothing.
     */
    public static String reverseScorePairs(String rawScore) {
        if (!StringUtils.hasText(rawScore)) {
            return rawScore;
        }
        Matcher matcher = SCORE_PAIR_PATTERN.matcher(rawScore);
        StringBuffer swapped = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String replacement = matcher.group(2) + "-" + matcher.group(1);
            matcher.appendReplacement(swapped, Matcher.quoteReplacement(replacement));
        }
        if (!found) {
            return rawScore.trim();
        }
        matcher.appendTail(swapped);
        return swapped.toString().trim();
    }

    /** Three-state orientation result used by the score normaliser. */
    public enum ScoreOrientation {
        /** Bet's P1/P2 align with row's P1/P2 in the same order. */
        DIRECT,
        /** Bet's P1/P2 align with row's P1/P2 swapped — scores need reversal. */
        REVERSED,
        /** Any token blank or no match — caller should treat as ambiguous. */
        UNKNOWN
    }
}
