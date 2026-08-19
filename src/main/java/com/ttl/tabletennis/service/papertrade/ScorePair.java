package com.ttl.tabletennis.service.papertrade;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pair of non-negative integer scores parsed from a raw live-score string
 * (e.g. {@code "11-9 11-7 9-11 11-5"} → 4 {@link ScorePair} entries).
 *
 * <p>Lifted from a private nested record in {@code PaperTradingService} as
 * part of the §4 decomposition. Behaviour verbatim — the same regex
 * ({@code (\d{1,2})\s*[-:]\s*(\d{1,2})}) feeds both the legacy parser and
 * the {@link #parseAll(String)} static factory.
 *
 * <p>Caller behaviour expectation: the parser is best-effort — malformed
 * tokens are silently skipped, and an empty / blank input returns an empty
 * list rather than throwing.
 */
public record ScorePair(int left, int right) {

    private static final Pattern SCORE_PAIR_PATTERN = Pattern.compile("(\\d{1,2})\\s*[-:]\\s*(\\d{1,2})");

    /**
     * Scan {@code rawScore} for {@code left-right} or {@code left:right}
     * integer pairs in order; returns the parsed list. Empty input → empty
     * list. Non-numeric / negative pairs are silently dropped.
     */
    public static List<ScorePair> parseAll(String rawScore) {
        List<ScorePair> pairs = new ArrayList<>();
        if (!StringUtils.hasText(rawScore)) {
            return pairs;
        }
        Matcher matcher = SCORE_PAIR_PATTERN.matcher(rawScore);
        while (matcher.find()) {
            try {
                int left = Integer.parseInt(matcher.group(1));
                int right = Integer.parseInt(matcher.group(2));
                if (left >= 0 && right >= 0) {
                    pairs.add(new ScorePair(left, right));
                }
            } catch (NumberFormatException ignore) {
                // continue scanning — best-effort parser
            }
        }
        return pairs;
    }
}
