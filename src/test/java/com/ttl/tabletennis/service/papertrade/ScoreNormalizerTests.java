package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoreNormalizerTests {

    @Test
    void reverseScorePairs_swapsLeftRight() {
        assertEquals("9-11 7-11 11-5", ScoreNormalizer.reverseScorePairs("11-9 11-7 5-11"));
        // : separator also supported
        assertEquals("9-11", ScoreNormalizer.reverseScorePairs("11:9"));
    }

    @Test
    void reverseScorePairs_blankInputUnchanged() {
        assertEquals(null, ScoreNormalizer.reverseScorePairs(null));
        assertEquals("", ScoreNormalizer.reverseScorePairs(""));
    }

    @Test
    void reverseScorePairs_noMatchReturnsTrimmedRaw() {
        assertEquals("nothing here", ScoreNormalizer.reverseScorePairs("  nothing here  "));
    }

    @Test
    void resolveOrientation_returnsDirectWhenAligned() {
        ScoreNormalizer.ScoreOrientation r = ScoreNormalizer.resolveScoreOrientation(
                1L, "Alice", 2L, "Bob",
                1L, "Alice", 2L, "Bob");
        assertEquals(ScoreNormalizer.ScoreOrientation.DIRECT, r);
    }

    @Test
    void resolveOrientation_returnsReversedWhenSwapped() {
        ScoreNormalizer.ScoreOrientation r = ScoreNormalizer.resolveScoreOrientation(
                1L, "Alice", 2L, "Bob",
                2L, "Bob", 1L, "Alice");
        assertEquals(ScoreNormalizer.ScoreOrientation.REVERSED, r);
    }

    @Test
    void resolveOrientation_returnsUnknownWhenAnyTokenBlank() {
        ScoreNormalizer.ScoreOrientation r = ScoreNormalizer.resolveScoreOrientation(
                null, null, null, null,
                1L, "Alice", 2L, "Bob");
        assertEquals(ScoreNormalizer.ScoreOrientation.UNKNOWN, r);
    }

    @Test
    void resolveOrientation_returnsUnknownWhenNoMatch() {
        ScoreNormalizer.ScoreOrientation r = ScoreNormalizer.resolveScoreOrientation(
                1L, "Alice", 2L, "Bob",
                3L, "Carol", 4L, "Dave");
        assertEquals(ScoreNormalizer.ScoreOrientation.UNKNOWN, r);
    }

    @Test
    void normalizeScoreForBet_swapsWhenRowIsReversed() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setPlayer1Id(1L);
        bet.setPlayer1Name("Alice");
        bet.setPlayer2Id(2L);
        bet.setPlayer2Name("Bob");

        // Row swapped P1/P2 with Bob first → input scores need reversal.
        String normalised = ScoreNormalizer.normalizeScoreForBet(
                bet, "11-9 7-11 11-3",
                2L, "Bob", 1L, "Alice");
        assertEquals("9-11 11-7 3-11", normalised);
    }

    @Test
    void normalizeScoreForBet_keepsAsIsWhenAligned() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setPlayer1Id(1L);
        bet.setPlayer1Name("Alice");
        bet.setPlayer2Id(2L);
        bet.setPlayer2Name("Bob");

        String normalised = ScoreNormalizer.normalizeScoreForBet(
                bet, "11-9 7-11 11-3",
                1L, "Alice", 2L, "Bob");
        assertEquals("11-9 7-11 11-3", normalised);
    }

    @Test
    void normalizeScoreForBet_blankInputUnchanged() {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setPlayer1Id(1L);
        bet.setPlayer2Id(2L);

        assertEquals(null, ScoreNormalizer.normalizeScoreForBet(bet, null, 1L, "a", 2L, "b"));
        assertEquals("", ScoreNormalizer.normalizeScoreForBet(bet, "", 1L, "a", 2L, "b"));
    }

    @Test
    void normalizeScoreForBet_nullBetUnchanged() {
        assertEquals("11-9", ScoreNormalizer.normalizeScoreForBet(null, "11-9", 1L, "a", 2L, "b"));
    }
}
