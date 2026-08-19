package com.ttl.tabletennis.service.papertrade;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreWinnerResolverTests {

    private static final Long P1 = 1L;
    private static final Long P2 = 2L;

    private static ScoreWinnerResolver fresh() {
        ScoreWinnerResolver r = new ScoreWinnerResolver();
        // mirror prod defaults
        r.overrideForTest(3, 2, true, 2, 10);
        return r;
    }

    @Test
    void strictSetScore_yieldsWinner() {
        Optional<Long> w = fresh().determineWinnerFromScore(
                "3-1", P1, P2, "LIVE_LATE", false);
        assertEquals(Optional.of(P1), w);
    }

    @Test
    void lenientInferenceFalse_blocksMidMatchHints() {
        // Mid-match 2-1 sets — not strict (top < targetSets=3), and
        // lenient inference is disabled.
        Optional<Long> w = fresh().determineWinnerFromScore(
                "2-1 11-9 9-11 11-7", P1, P2, "LIVE_MID", false);
        assertTrue(w.isEmpty(), "without lenient inference + strict match unfinished → empty");
    }

    @Test
    void nullOrBlankInputs_yieldEmpty() {
        ScoreWinnerResolver r = fresh();
        assertTrue(r.determineWinnerFromScore(null, P1, P2, "LIVE_LATE", true).isEmpty());
        assertTrue(r.determineWinnerFromScore("   ", P1, P2, "LIVE_LATE", true).isEmpty());
        assertTrue(r.determineWinnerFromScore("3-1", null, P2, "LIVE_LATE", true).isEmpty());
        assertTrue(r.determineWinnerFromScore("3-1", P1, null, "LIVE_LATE", true).isEmpty());
    }

    @Test
    void nearFinishFallback_disabled_returnsEmpty() {
        ScoreWinnerResolver r = new ScoreWinnerResolver();
        r.overrideForTest(3, 2, false, 2, 10);  // disabled
        Optional<Long> w = r.determineWinnerFromNearFinishFallback("2-2 11-9 11-7 9-11 11-9", P1, P2);
        assertTrue(w.isEmpty(), "fallback disabled → empty");
    }

    @Test
    void nearFinishFallback_resolvesTiedFinalSet() {
        // Setup: a 5-game match (best-of-5, targetSets=3). Set score 2-2 then 11-9 in
        // the deciding game → P1 wins with a 2-point margin.
        Optional<Long> w = fresh().determineWinnerFromNearFinishFallback(
                "2-2 11-9 7-11 11-8 9-11 11-9", P1, P2);
        assertEquals(Optional.of(P1), w);
    }

    @Test
    void findPrimarySetScorePairIndex_picksFirstFittingPair() {
        List<ScorePair> pairs = List.of(
                new ScorePair(11, 9),    // not a set score (too many points for target=3)
                new ScorePair(3, 1)       // first set-shape pair
        );
        assertEquals(1, ScoreWinnerResolver.findPrimarySetScorePairIndex(pairs, 3));

        // No fitting pair → -1
        assertEquals(-1, ScoreWinnerResolver.findPrimarySetScorePairIndex(
                List.of(new ScorePair(11, 9), new ScorePair(15, 13)), 3));

        assertEquals(-1, ScoreWinnerResolver.findPrimarySetScorePairIndex(List.of(), 3));
        assertEquals(-1, ScoreWinnerResolver.findPrimarySetScorePairIndex(null, 3));
    }

    @Test
    void scorePair_parseAll_extractsPairsAndSkipsGarbage() {
        List<ScorePair> pairs = ScorePair.parseAll("3-1 11-9 abc:def 7-:bogus 9-11");
        assertEquals(3, pairs.size());
        assertEquals(3, pairs.get(0).left());
        assertEquals(1, pairs.get(0).right());
        assertEquals(11, pairs.get(1).left());
        assertEquals(11, pairs.get(2).right(), "third pair is 9-11");
    }

    @Test
    void scorePair_parseAll_emptyOrBlankYieldsEmpty() {
        assertTrue(ScorePair.parseAll(null).isEmpty());
        assertTrue(ScorePair.parseAll("").isEmpty());
        assertTrue(ScorePair.parseAll("   ").isEmpty());
        assertTrue(ScorePair.parseAll("no pairs here").isEmpty());
    }

    // --- #130 confidence-settle tests ---

    @Test
    void confidence_twoSetCushionSettlesLeader() {
        // 2-0 in best-of-5: leader needs 1 of 3 remaining → decisive.
        assertEquals(Optional.of(P1),
                fresh().determineWinnerFromConfidenceState("2-0", P1, P2, "LIVE_LATE"));
        assertEquals(Optional.of(P2),
                fresh().determineWinnerFromConfidenceState("0-2", P1, P2, "LIVE_LATE"));
        // with a current game in progress, still decisive on the set cushion
        assertEquals(Optional.of(P1),
                fresh().determineWinnerFromConfidenceState("2-0 (5-3)", P1, P2, "LIVE_LATE"));
    }

    @Test
    void confidence_setPointWithCommandingGameSettlesLeader() {
        // 2-1 and the set leader is crushing the would-be clincher 9-3.
        assertEquals(Optional.of(P1),
                fresh().determineWinnerFromConfidenceState("2-1 (9-3)", P1, P2, "LIVE_LATE"));
        assertEquals(Optional.of(P2),
                fresh().determineWinnerFromConfidenceState("1-2 (3-9)", P1, P2, "LIVE_LATE"));
    }

    @Test
    void confidence_ambiguousStatesHeldNotCalled() {
        ScoreWinnerResolver r = fresh();
        // 2-2 going to game 5 — genuinely undecided.
        assertTrue(r.determineWinnerFromConfidenceState("2-2 (9-8)", P1, P2, "LIVE_LATE").isEmpty());
        // 2-1 but the set leader is BEHIND in the current game → could go to 5.
        assertTrue(r.determineWinnerFromConfidenceState("2-1 (3-9)", P1, P2, "LIVE_LATE").isEmpty());
        // 1-1 — way too early.
        assertTrue(r.determineWinnerFromConfidenceState("1-1 (5-5)", P1, P2, "LIVE_LATE").isEmpty());
        // 2-1 with only a slim game lead (8-6) → not commanding enough.
        assertTrue(r.determineWinnerFromConfidenceState("2-1 (8-6)", P1, P2, "LIVE_LATE").isEmpty());
    }

    @Test
    void confidence_neverCalledInEarlyPhase() {
        // Even a 2-0 must not be confidence-settled while the match is early/prematch —
        // the caller only invokes this when the match has gone stale, but the
        // resolver also guards on phase as defence in depth.
        assertTrue(fresh().determineWinnerFromConfidenceState("2-0", P1, P2, "LIVE_EARLY").isEmpty());
        assertTrue(fresh().determineWinnerFromConfidenceState("2-0", P1, P2, "PREMATCH").isEmpty());
    }

    @Test
    void confidence_finalScoreDefersToStrictResolver() {
        // A mathematically-final 3-1 is NOT a confidence case (strict resolver owns it).
        assertTrue(fresh().determineWinnerFromConfidenceState("3-1", P1, P2, "LIVE_LATE").isEmpty());
    }

    @Test
    void confidence_disabledFlagYieldsEmpty() {
        ScoreWinnerResolver r = new ScoreWinnerResolver();
        r.overrideForTest(3, 2, true, 2, 10);
        r.overrideConfidenceForTest(false, 2, 9, 5);
        assertTrue(r.determineWinnerFromConfidenceState("2-0", P1, P2, "LIVE_LATE").isEmpty());
    }

    @Test
    void phaseHelpers_classifyExpectedKeywords() {
        assertTrue(PaperTradingHelpers.isFinishedPhase("FINISHED"));
        assertTrue(PaperTradingHelpers.isFinishedPhase("Match Result"));
        assertTrue(PaperTradingHelpers.isFinishedPhase("Final"));
        assertFalse(PaperTradingHelpers.isFinishedPhase("LIVE_EARLY"));
        assertFalse(PaperTradingHelpers.isFinishedPhase(null));
        assertFalse(PaperTradingHelpers.isFinishedPhase("   "));

        assertTrue(PaperTradingHelpers.isLateLikePhase("LIVE_LATE"));
        assertTrue(PaperTradingHelpers.isLateLikePhase("LIVE_MID"));
        assertTrue(PaperTradingHelpers.isLateLikePhase("Ended"));
        // FinishedPhase ⊂ LateLikePhase
        assertTrue(PaperTradingHelpers.isLateLikePhase("FINISHED"));
        assertFalse(PaperTradingHelpers.isLateLikePhase("LIVE_EARLY"));
    }
}
