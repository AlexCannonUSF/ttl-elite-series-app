package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.util.NameUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.parseStartDateTime;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.startBucket;

/**
 * Pure-function name + start-time matching helpers used by the placement and
 * settlement loops to map bets back to live-board rows when the strict
 * dedupe key didn't resolve.
 *
 * <p>Fifteenth §4 slice — second piece of the PlacementService row. The
 * matcher is deliberately fuzzy: it survives name re-orderings (P1/P2 swap),
 * minor spelling variants via {@link NameUtils#areNamesSimilar(String, String)},
 * and start-time clocks that drift up to 12 hours across feeds. Errs on the
 * side of "still the same match" so a missing tracked observation doesn't
 * lose its bet.
 *
 * <p>Static utility; no Spring dependency. All inputs come from the caller —
 * the matcher reads no state.
 */
public final class BetIdentityMatcher {

    /** Maximum minute drift between bet-side and row-side start timestamps before they're considered different events. */
    private static final long MAX_START_TIME_DRIFT_MINUTES = 720L;

    private BetIdentityMatcher() {
        // utility class — not instantiable
    }

    /**
     * True when bet+row are arguably the same match by participants alone:
     * either both players match in order, or both match swapped.
     */
    public static boolean isLoosePairNameMatch(PaperTradeBet bet, LiveOddsRecommendationDto row) {
        if (bet == null || row == null) {
            return false;
        }
        return isLoosePairNameMatch(bet, row.player1Name(), row.player2Name());
    }

    /**
     * Overload (#114): compares the bet's locked player names against a raw
     * (player1, player2) pair from any source — used by
     * {@link BetIdentityLockManager}'s identity-drift fallback when the
     * candidate isn't a {@link LiveOddsRecommendationDto} (e.g. a
     * {@link com.ttl.tabletennis.domain.TrackedMatchObservation}).
     *
     * <p>Loose: tolerates swapped order, normalisation differences, and minor
     * spelling variants via {@link #isSameParticipantLoose}. Both names must
     * be non-blank for the comparison to fire.
     */
    public static boolean isLoosePairNameMatch(PaperTradeBet bet, String candidateP1, String candidateP2) {
        if (bet == null
                || !StringUtils.hasText(candidateP1)
                || !StringUtils.hasText(candidateP2)) {
            return false;
        }
        return (isSameParticipantLoose(bet.getPlayer1Name(), candidateP1)
                && isSameParticipantLoose(bet.getPlayer2Name(), candidateP2))
                || (isSameParticipantLoose(bet.getPlayer1Name(), candidateP2)
                && isSameParticipantLoose(bet.getPlayer2Name(), candidateP1));
    }

    /**
     * Loose single-participant compare. Returns true on
     * {@link NameUtils#areNamesSimilar(String, String)} match, on normalised
     * equality, or when last-names match and first-name initials line up.
     */
    public static boolean isSameParticipantLoose(String betName, String rowName) {
        if (!StringUtils.hasText(betName) || !StringUtils.hasText(rowName)) {
            return false;
        }
        if (NameUtils.areNamesSimilar(betName, rowName)) {
            return true;
        }

        String betLookup = NameUtils.normalizeForLookup(betName);
        String rowLookup = NameUtils.normalizeForLookup(rowName);
        if (!StringUtils.hasText(betLookup) || !StringUtils.hasText(rowLookup)) {
            return false;
        }
        if (betLookup.equals(rowLookup)) {
            return true;
        }

        String[] betParts = betLookup.split("\\s+");
        String[] rowParts = rowLookup.split("\\s+");
        if (betParts.length == 0 || rowParts.length == 0) {
            return false;
        }
        String betLast = betParts[betParts.length - 1];
        String rowLast = rowParts[rowParts.length - 1];
        if (!betLast.equals(rowLast)) {
            return false;
        }
        String betFirst = betParts[0];
        String rowFirst = rowParts[0];
        if (!StringUtils.hasText(betFirst) || !StringUtils.hasText(rowFirst)) {
            return true;
        }
        return betFirst.charAt(0) == rowFirst.charAt(0);
    }

    /** Strict pair compare — requires all four names non-blank, then exact match (in either order). */
    public static boolean isSamePair(String a1, String a2, String b1, String b2) {
        if (!StringUtils.hasText(a1) || !StringUtils.hasText(a2) || !StringUtils.hasText(b1) || !StringUtils.hasText(b2)) {
            return false;
        }
        return (a1.equals(b1) && a2.equals(b2)) || (a1.equals(b2) && a2.equals(b1));
    }

    /**
     * True when bet-side and row-side start timestamps are within the
     * {@link #MAX_START_TIME_DRIFT_MINUTES} drift threshold, or when at least
     * one side is missing (treated as compatible). When both are present but
     * unparseable, falls back to comparing minute-bucketed keys.
     */
    public static boolean isCompatibleStartTime(String betStartIso, String rowStartIso) {
        if (!StringUtils.hasText(betStartIso) || !StringUtils.hasText(rowStartIso)) {
            return true;
        }
        Optional<LocalDateTime> betStart = parseStartDateTime(betStartIso);
        Optional<LocalDateTime> rowStart = parseStartDateTime(rowStartIso);
        if (betStart.isPresent() && rowStart.isPresent()) {
            long diffMinutes = Math.abs(ChronoUnit.MINUTES.between(betStart.get(), rowStart.get()));
            return diffMinutes <= MAX_START_TIME_DRIFT_MINUTES;
        }
        return startBucket(betStartIso).equals(startBucket(rowStartIso));
    }

    /**
     * Decide whether the candidate start ISO should replace the current one
     * on a bet. Prefers the parseable one when only one parses; otherwise
     * prefers the earlier of the two parsed timestamps; otherwise compares
     * minute-bucket strings.
     */
    public static boolean shouldReplaceStartTimeIso(String currentStartIso, String candidateStartIso) {
        if (!StringUtils.hasText(candidateStartIso)) {
            return false;
        }
        if (!StringUtils.hasText(currentStartIso)) {
            return true;
        }
        String current = currentStartIso.trim();
        String candidate = candidateStartIso.trim();
        if (candidate.equals(current)) {
            return false;
        }

        Optional<LocalDateTime> currentParsed = parseStartDateTime(current);
        Optional<LocalDateTime> candidateParsed = parseStartDateTime(candidate);
        if (currentParsed.isPresent() && candidateParsed.isPresent()) {
            return candidateParsed.get().isBefore(currentParsed.get());
        }
        if (currentParsed.isEmpty() && candidateParsed.isPresent()) {
            return true;
        }
        if (currentParsed.isPresent()) {
            return false;
        }
        return startBucket(candidate).compareTo(startBucket(current)) < 0;
    }
}
