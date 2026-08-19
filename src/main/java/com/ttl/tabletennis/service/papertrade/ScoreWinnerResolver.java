package com.ttl.tabletennis.service.papertrade;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.isFinishedPhase;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.isLateLikePhase;

/**
 * Settlement-side score interpreter: given a raw live-score string + phase
 * + the two player ids, returns whose id won the match (or
 * {@link Optional#empty()} when the score isn't conclusive).
 *
 * <p>Fourteenth §4 slice — first piece of the PlacementService row. Covers
 * the dense settlement decision logic that the {@code legacy
 * settleOpenBetsLegacy} path leans on hard. Pulled into a Spring
 * {@code @Service} because the five {@code @Value}-injected guard rails
 * ({@code targetSets}, {@code minMarginSets}, the near-finish fallback
 * trio) belong with the math; the caller (today {@code PaperTradingService})
 * is a thin delegate at every call site.
 *
 * <p>Behaviour verbatim from the original cluster
 * ({@code determineWinnerFromScore} + {@code determineWinnerFromNearFinishFallback}
 * + their 7 internal helpers). Same clamps, same legacy hard-coded constants
 * (11-point game floor, 2-point margin), same regex via
 * {@link ScorePair#parseAll(String)}.
 */
@Service
public class ScoreWinnerResolver {

    /** Minimum game points required to count a deciding-set frame. */
    private static final int GAME_POINT_FLOOR = 11;
    /** Minimum winning margin in a single game in points. */
    private static final int GAME_MIN_MARGIN = 2;

    @Value("${ttl.paper.scoreSettlementTargetSets:3}")
    private int scoreSettlementTargetSets;

    @Value("${ttl.paper.scoreSettlementMinMarginSets:2}")
    private int scoreSettlementMinMarginSets;

    @Value("${ttl.paper.nearFinishFallbackEnabled:true}")
    private boolean nearFinishFallbackEnabled;

    @Value("${ttl.paper.nearFinishFallbackMinPointLead:2}")
    private int nearFinishFallbackMinPointLead;

    @Value("${ttl.paper.nearFinishFallbackPointFloor:10}")
    private int nearFinishFallbackPointFloor;

    // #130 — confidence-settle thresholds. Used to call a winner from a
    // decisive but not-yet-mathematically-final last live state when a
    // match has gone dark (feed dropped the event before the terminal
    // "3-X" arrived). The caller gates this on staleness; this resolver
    // only judges whether the state itself is decisive enough.
    @Value("${ttl.paper.confidenceSettle.enabled:true}")
    private boolean confidenceSettleEnabled;

    /** Minimum set cushion to call the set leader the winner (e.g. 2-0). */
    @Value("${ttl.paper.confidenceSettle.minSetLead:2}")
    private int confidenceMinSetLead;

    /** When the leader is one set from the match, the commanding-game-lead floor. */
    @Value("${ttl.paper.confidenceSettle.gamePointFloor:9}")
    private int confidenceGamePointFloor;

    /** Minimum current-game point lead to treat a set-point game as decided. */
    @Value("${ttl.paper.confidenceSettle.minGameLead:5}")
    private int confidenceMinGameLead;

    /**
     * Strict set-score winner (and lenient fallback when {@code allowLenientInference}
     * is true and the phase indicates a late / finished match).
     */
    public Optional<Long> determineWinnerFromScore(String rawScore,
                                                    Long player1Id,
                                                    Long player2Id,
                                                    String phaseRaw,
                                                    boolean allowLenientInference) {
        if (rawScore == null || rawScore.isBlank() || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        List<ScorePair> parsed = ScorePair.parseAll(rawScore);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        int targetSets = clamp(scoreSettlementTargetSets, 3, 7);
        int minMarginSets = clamp(scoreSettlementMinMarginSets, 1, 3);
        boolean finishedPhase = isFinishedPhase(phaseRaw);
        boolean latePhase = isLateLikePhase(phaseRaw);
        int setPairIndex = findPrimarySetScorePairIndex(parsed, targetSets);
        if (setPairIndex >= 0) {
            Optional<Long> strictSetWinner = winnerFromSetScorePair(parsed.get(setPairIndex), targetSets, player1Id, player2Id);
            if (strictSetWinner.isPresent()) {
                return strictSetWinner;
            }
        }

        if (!allowLenientInference) {
            return Optional.empty();
        }

        if (!latePhase && !finishedPhase) {
            return Optional.empty();
        }

        if (setPairIndex >= 0) {
            ScorePair setPair = parsed.get(setPairIndex);
            boolean tiedInFinalSet = setPair.left() == (targetSets - 1) && setPair.right() == (targetSets - 1);
            if ((finishedPhase || latePhase) && tiedInFinalSet) {
                Optional<ScorePair> pointScore = findPointScorePair(parsed, setPairIndex);
                if (pointScore.isPresent()) {
                    Optional<Long> inferred = finishedPhase
                            ? winnerFromFinishedPhaseTiedFinalSetPoints(pointScore.get(), player1Id, player2Id)
                            : winnerFromTiedFinalSetPoints(pointScore.get(), player1Id, player2Id);
                    if (inferred.isPresent()) {
                        return inferred;
                    }
                }
            }
            return Optional.empty();
        }

        Optional<ScorePair> pointOnly = findPointScorePair(parsed, -1);
        if (pointOnly.isEmpty()) {
            return Optional.empty();
        }
        if (!finishedPhase && parsed.size() > 1) {
            return Optional.empty();
        }
        return winnerFromPointScorePair(pointOnly.get(), minMarginSets, player1Id, player2Id);
    }

    /**
     * #130 — Confidence settlement from a decisive (but not yet
     * mathematically final) last live state.
     *
     * <p>TT Elite Series matches always resolve and the official result is
     * always eventually posted — but per-tournament-block, with a 1-3h lag.
     * When Hard Rock drops a finished event before the terminal "3-X"
     * arrives, the bet is left holding a decisive-but-not-final score like
     * "2-0" or "2-1 (9-3)". Voiding those (the old 90-min behaviour) threw
     * away a knowable W/L. This method calls the winner when the last state
     * is decisive enough that a comeback is improbable:
     *
     * <ul>
     *   <li><b>Set cushion</b> — a player leads by {@code confidenceMinSetLead}
     *       sets (default 2, i.e. "2-0") while one set short of the match.
     *       The trailing player would have to win {@code targetSets} sets in
     *       a row. Empirically ~95% hold rate.</li>
     *   <li><b>Set point + commanding game</b> — the set leader is one set
     *       from the match AND also leads the current game by
     *       {@code confidenceMinGameLead} at {@code confidenceGamePointFloor}+
     *       points (e.g. "2-1 (9-3)" — about to close it out).</li>
     * </ul>
     *
     * <p>The caller MUST gate this on staleness (match presumed finished) —
     * this method only judges whether the state itself is decisive, never
     * whether the match is over.
     */
    public Optional<Long> determineWinnerFromConfidenceState(String rawScore,
                                                             Long player1Id,
                                                             Long player2Id,
                                                             String phaseRaw) {
        if (!confidenceSettleEnabled || rawScore == null || rawScore.isBlank()
                || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        // Only meaningful for late/finished phases — never call a winner on
        // an early or prematch state.
        if (!isLateLikePhase(phaseRaw) && !isFinishedPhase(phaseRaw)) {
            return Optional.empty();
        }
        List<ScorePair> parsed = ScorePair.parseAll(rawScore);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        int targetSets = clamp(scoreSettlementTargetSets, 3, 7);
        int setIdx = findPrimarySetScorePairIndex(parsed, targetSets);
        if (setIdx < 0) {
            return Optional.empty();
        }
        ScorePair sets = parsed.get(setIdx);
        int leadSets = Math.abs(sets.left() - sets.right());
        int topSets = Math.max(sets.left(), sets.right());

        // The mathematically-final case (top >= targetSets) is handled by the
        // strict resolver; confidence settlement only covers near-final states.
        if (topSets >= targetSets) {
            return Optional.empty();
        }
        boolean setLeaderIsP1 = sets.left() > sets.right();

        // Rule A — a >= confidenceMinSetLead set cushion while one set short.
        int minSetLead = Math.max(2, confidenceMinSetLead);
        if (topSets == targetSets - 1 && leadSets >= minSetLead) {
            return Optional.of(setLeaderIsP1 ? player1Id : player2Id);
        }

        // Rule B — set leader is one set from the match AND commands the
        // current game (same player leading both sets and the game).
        if (topSets == targetSets - 1 && leadSets >= 1) {
            Optional<ScorePair> game = findPointScorePair(parsed, setIdx);
            if (game.isPresent()) {
                ScorePair g = game.get();
                int gTop = Math.max(g.left(), g.right());
                int gLead = Math.abs(g.left() - g.right());
                boolean gameLeaderIsP1 = g.left() > g.right();
                int floor = clamp(confidenceGamePointFloor, 7, 14);
                int minLead = clamp(confidenceMinGameLead, 2, 11);
                if (gTop >= floor && gLead >= minLead && gameLeaderIsP1 == setLeaderIsP1) {
                    return Optional.of(setLeaderIsP1 ? player1Id : player2Id);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Fallback for tied-deciding-set scores that didn't conclude in the
     * strict pass — guarded by {@code nearFinishFallbackEnabled} and the
     * pair of point-floor + min-lead clamps.
     */
    public Optional<Long> determineWinnerFromNearFinishFallback(String rawScore,
                                                                 Long player1Id,
                                                                 Long player2Id) {
        if (!nearFinishFallbackEnabled || rawScore == null || rawScore.isBlank()
                || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        List<ScorePair> pairs = ScorePair.parseAll(rawScore);
        if (pairs.size() < 2) {
            return Optional.empty();
        }

        int targetSets = clamp(scoreSettlementTargetSets, 3, 7);
        int maxTotalSets = Math.max(1, (targetSets * 2) - 1);

        ScorePair setScore = null;
        for (ScorePair pair : pairs) {
            int top = Math.max(pair.left(), pair.right());
            int total = pair.left() + pair.right();
            if (top <= targetSets && total <= maxTotalSets) {
                setScore = pair;
                break;
            }
        }
        if (setScore == null) {
            return Optional.empty();
        }
        int setTop = Math.max(setScore.left(), setScore.right());
        int setLow = Math.min(setScore.left(), setScore.right());
        if (setTop != (targetSets - 1) || setLow != (targetSets - 1)) {
            return Optional.empty();
        }

        ScorePair last = pairs.get(pairs.size() - 1);
        return winnerFromCompletedGamePoints(last, player1Id, player2Id);
    }

    public static int findPrimarySetScorePairIndex(List<ScorePair> parsed, int targetSets) {
        if (parsed == null || parsed.isEmpty()) {
            return -1;
        }
        int maxTotalSets = Math.max(1, (targetSets * 2) - 1);
        for (int i = 0; i < parsed.size(); i++) {
            ScorePair pair = parsed.get(i);
            int top = Math.max(pair.left(), pair.right());
            int total = pair.left() + pair.right();
            if (top <= targetSets && total <= maxTotalSets) {
                return i;
            }
        }
        return -1;
    }

    static Optional<ScorePair> findPointScorePair(List<ScorePair> parsed, int setPairIndex) {
        if (parsed == null || parsed.isEmpty()) {
            return Optional.empty();
        }
        for (int i = parsed.size() - 1; i >= 0; i--) {
            if (i == setPairIndex) {
                continue;
            }
            return Optional.of(parsed.get(i));
        }
        return Optional.empty();
    }

    static Optional<Long> winnerFromSetScorePair(ScorePair score,
                                                 int targetSets,
                                                 Long player1Id,
                                                 Long player2Id) {
        if (score == null || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        int p1 = score.left();
        int p2 = score.right();
        if (p1 == p2) {
            return Optional.empty();
        }
        int top = Math.max(p1, p2);
        if (top < targetSets) {
            return Optional.empty();
        }
        return Optional.of(p1 > p2 ? player1Id : player2Id);
    }

    static Optional<Long> winnerFromPointScorePair(ScorePair score,
                                                   int minMarginSets,
                                                   Long player1Id,
                                                   Long player2Id) {
        if (score == null || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        int p1 = score.left();
        int p2 = score.right();
        if (p1 == p2) {
            return Optional.empty();
        }
        int top = Math.max(p1, p2);
        int margin = Math.abs(p1 - p2);
        if (top < GAME_POINT_FLOOR || margin < Math.max(GAME_MIN_MARGIN, minMarginSets)) {
            return Optional.empty();
        }
        return Optional.of(p1 > p2 ? player1Id : player2Id);
    }

    static Optional<Long> winnerFromTiedFinalSetPoints(ScorePair score,
                                                       Long player1Id,
                                                       Long player2Id) {
        return winnerFromCompletedGamePoints(score, player1Id, player2Id);
    }

    Optional<Long> winnerFromFinishedPhaseTiedFinalSetPoints(ScorePair score,
                                                              Long player1Id,
                                                              Long player2Id) {
        if (score == null || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        int p1 = score.left();
        int p2 = score.right();
        if (p1 == p2) {
            return Optional.empty();
        }
        int top = Math.max(p1, p2);
        int margin = Math.abs(p1 - p2);
        int pointFloor = clamp(nearFinishFallbackPointFloor, 7, 15);
        int minLead = clamp(nearFinishFallbackMinPointLead, 2, 6);
        if (top < pointFloor || margin < minLead) {
            return Optional.empty();
        }
        return Optional.of(p1 > p2 ? player1Id : player2Id);
    }

    static Optional<Long> winnerFromCompletedGamePoints(ScorePair score,
                                                        Long player1Id,
                                                        Long player2Id) {
        if (score == null || player1Id == null || player2Id == null) {
            return Optional.empty();
        }
        int p1 = score.left();
        int p2 = score.right();
        if (p1 == p2) {
            return Optional.empty();
        }
        int top = Math.max(p1, p2);
        int margin = Math.abs(p1 - p2);
        if (top < GAME_POINT_FLOOR || margin < GAME_MIN_MARGIN) {
            return Optional.empty();
        }
        return Optional.of(p1 > p2 ? player1Id : player2Id);
    }

    // -- visible for tests: lets focused unit tests stub the @Value-injected config
    void overrideForTest(int targetSets, int minMarginSets,
                         boolean fallbackEnabled, int fallbackMinPointLead, int fallbackPointFloor) {
        this.scoreSettlementTargetSets = targetSets;
        this.scoreSettlementMinMarginSets = minMarginSets;
        this.nearFinishFallbackEnabled = fallbackEnabled;
        this.nearFinishFallbackMinPointLead = fallbackMinPointLead;
        this.nearFinishFallbackPointFloor = fallbackPointFloor;
        // confidence-settle defaults applied so tests that don't set them
        // explicitly still get a working resolver.
        if (this.confidenceMinSetLead <= 0) this.confidenceMinSetLead = 2;
        if (this.confidenceGamePointFloor <= 0) this.confidenceGamePointFloor = 9;
        if (this.confidenceMinGameLead <= 0) this.confidenceMinGameLead = 5;
        this.confidenceSettleEnabled = true;
    }

    /** Visible for tests: configure confidence-settle thresholds directly. */
    void overrideConfidenceForTest(boolean enabled, int minSetLead, int gamePointFloor, int minGameLead) {
        this.confidenceSettleEnabled = enabled;
        this.confidenceMinSetLead = minSetLead;
        this.confidenceGamePointFloor = gamePointFloor;
        this.confidenceMinGameLead = minGameLead;
    }
}
