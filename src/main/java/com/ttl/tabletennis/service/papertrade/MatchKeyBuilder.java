package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.util.NameUtils;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.normalizeKey;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.startBucket;

/**
 * Pure-function key builders used by the placement loop, row lookup chain,
 * and observation tracking to map row + bet identities to deterministic
 * stringified keys.
 *
 * <p>Sixteenth §4 slice — supporting infrastructure for the RowLookup
 * extract that comes next. Six methods + one regex constant, all static,
 * all stateless.
 *
 * <p>The keys produced here are stable across feeds: a row with player ids
 * routes to {@code id-1234}, otherwise to {@code nm-<normalised-name>}.
 * The pair-key is order-insensitive (sorts the two tokens), so the same
 * matchup keys the same regardless of which side feeds the row.
 */
public final class MatchKeyBuilder {

    /** Matches {@code |event=ABC123} appended to feed-source strings; group 1 is the raw id. */
    private static final Pattern SOURCE_EVENT_ID_PATTERN =
            Pattern.compile("\\|event=([A-Za-z0-9:_-]+)", Pattern.CASE_INSENSITIVE);

    private MatchKeyBuilder() {
        // utility class — not instantiable
    }

    /** Composite "competition|event|p1|p2|startBucket" key used as a fallback when matchupKey is missing. */
    public static String buildEventKey(LiveOddsRecommendationDto row) {
        if (row == null) {
            return null;
        }
        String startBucketRaw = StringUtils.hasText(row.startTimeIso())
                ? row.startTimeIso().trim()
                : LocalDate.now().toString();
        return normalizeKey(row.competitionName()) + "|"
                + normalizeKey(row.eventName()) + "|"
                + normalizeKey(row.player1Name()) + "|"
                + normalizeKey(row.player2Name()) + "|"
                + normalizeKey(startBucketRaw);
    }

    /**
     * Pluck the external event id from a feed-source string of the form
     * {@code <source>|event=<id>}. Returns {@code ""} when no match —
     * callers treat that as "no event id available".
     */
    public static String extractExternalEventId(String source) {
        if (!StringUtils.hasText(source)) {
            return "";
        }
        Matcher matcher = SOURCE_EVENT_ID_PATTERN.matcher(source.trim());
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group(1);
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim().replaceAll("[^A-Za-z0-9:_-]", "");
    }

    /**
     * Order-insensitive pair key — sorts the two player tokens so swapping
     * P1/P2 produces the same key. Returns null when either token is blank.
     */
    public static String toPairKey(Long player1Id,
                                    String player1Name,
                                    Long player2Id,
                                    String player2Name) {
        String token1 = playerToken(player1Id, player1Name);
        String token2 = playerToken(player2Id, player2Name);
        if (!StringUtils.hasText(token1) || !StringUtils.hasText(token2)) {
            return null;
        }
        String left = token1.compareTo(token2) <= 0 ? token1 : token2;
        String right = token1.compareTo(token2) <= 0 ? token2 : token1;
        return left + "|" + right;
    }

    /** Pair key + start-time minute bucket — discriminates same-pair matches on different dates/times. */
    public static String toPairStartKey(Long player1Id,
                                         String player1Name,
                                         Long player2Id,
                                         String player2Name,
                                         String startTimeIso) {
        String pairKey = toPairKey(player1Id, player1Name, player2Id, player2Name);
        if (!StringUtils.hasText(pairKey)) {
            return null;
        }
        return pairKey + "|" + startBucket(startTimeIso);
    }

    /**
     * Single-player token: prefers {@code id-<playerId>} when id is present,
     * falls back to {@code nm-<normalised-name>}. Returns null when nothing
     * usable is present.
     */
    public static String playerToken(Long playerId, String playerName) {
        if (playerId != null) {
            return "id-" + playerId;
        }
        if (StringUtils.hasText(playerName)) {
            String normalized = normalizePersonToken(playerName);
            if (StringUtils.hasText(normalized) && !"na".equals(normalized)) {
                return "nm-" + normalized;
            }
        }
        return null;
    }

    /**
     * Strip accents + lowercase + alpha-sort name tokens so "John Smith"
     * and "Smith John" produce the same normalised key. Falls back to
     * {@link PaperTradingHelpers#normalizeKey(String)} when the canonical
     * cleanup leaves nothing usable.
     */
    public static String normalizePersonToken(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return "na";
        }
        String lookup = NameUtils.normalizeForLookup(rawName);
        if (!StringUtils.hasText(lookup)) {
            lookup = rawName;
        }
        String ascii = Normalizer.normalize(lookup, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('ł', 'l')
                .replace('Ł', 'l');
        ascii = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!StringUtils.hasText(ascii)) {
            return normalizeKey(rawName);
        }
        String[] parts = ascii.split(" ");
        Arrays.sort(parts);
        String normalized = String.join("-", parts)
                .replaceAll("^-+|-+$", "");
        return StringUtils.hasText(normalized) ? normalized : normalizeKey(rawName);
    }
}
