package com.ttl.tabletennis.util;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchResultParserTests {

    @Test
    void parseCompleteResult() {
        MatchResultParser.ParsedResult parsed = MatchResultParser.parse("3:1");

        assertEquals(3, parsed.player1SetsWon());
        assertEquals(1, parsed.player2SetsWon());
        assertTrue(parsed.complete());
        assertEquals(MatchResultParser.WinnerSide.PLAYER1, parsed.winnerSide());
    }

    @Test
    void parseIncompleteResult() {
        MatchResultParser.ParsedResult parsed = MatchResultParser.parse("1:1");

        assertEquals(1, parsed.player1SetsWon());
        assertEquals(1, parsed.player2SetsWon());
        assertFalse(parsed.complete());
        assertEquals(MatchResultParser.WinnerSide.NONE, parsed.winnerSide());
    }

    @Test
    void applyToMatchSetsStructuredFields() {
        Player p1 = new Player("Ada", "Lovelace");
        p1.setId(10L);
        Player p2 = new Player("Grace", "Hopper");
        p2.setId(11L);

        Match match = new Match();
        match.setPlayer1(p1);
        match.setPlayer2(p2);

        MatchResultParser.applyToMatch(match, "2-3");

        assertEquals("2-3", match.getResult());
        assertEquals(2, match.getPlayer1SetsWon());
        assertEquals(3, match.getPlayer2SetsWon());
        assertTrue(match.isComplete());
        assertEquals(11L, match.getWinnerPlayerId());
    }

    @Test
    void applyToMatchHandlesUnparseableResult() {
        Match match = new Match();
        MatchResultParser.applyToMatch(match, "walkover");

        assertFalse(match.isComplete());
        assertNull(match.getWinnerPlayerId());
    }

    @Test
    void applyToMatchNullsInvalidResultToken() {
        Match match = new Match();
        MatchResultParser.applyToMatch(match, "abandoned by referee");

        assertNull(match.getResult());
        assertFalse(match.isComplete());
        assertNull(match.getWinnerPlayerId());
    }

    @Test
    void parseExtractsScoreInsideDecoratedText() {
        MatchResultParser.ParsedResult parsed = MatchResultParser.parse("Final result: 3-0 (retired)");

        assertEquals(3, parsed.player1SetsWon());
        assertEquals(0, parsed.player2SetsWon());
        assertTrue(parsed.complete());
        assertEquals(MatchResultParser.WinnerSide.PLAYER1, parsed.winnerSide());
    }

    @Test
    void parseSupportsSlashSeparator() {
        MatchResultParser.ParsedResult parsed = MatchResultParser.parse("3/2");

        assertEquals(3, parsed.player1SetsWon());
        assertEquals(2, parsed.player2SetsWon());
        assertTrue(parsed.complete());
        assertEquals(MatchResultParser.WinnerSide.PLAYER1, parsed.winnerSide());
    }

    @Test
    void parseSupportsUnicodeDash() {
        MatchResultParser.ParsedResult parsed = MatchResultParser.parse("3–2");

        assertEquals(3, parsed.player1SetsWon());
        assertEquals(2, parsed.player2SetsWon());
        assertTrue(parsed.complete());
        assertEquals(MatchResultParser.WinnerSide.PLAYER1, parsed.winnerSide());
    }

    @Test
    void parseSupportsCompactSetScoreWithoutDelimiter() {
        MatchResultParser.ParsedResult parsed = MatchResultParser.parse("03");

        assertEquals(0, parsed.player1SetsWon());
        assertEquals(3, parsed.player2SetsWon());
        assertTrue(parsed.complete());
        assertEquals(MatchResultParser.WinnerSide.PLAYER2, parsed.winnerSide());
    }

    @Test
    void parseInfersSetsFromPerSetScores() {
        MatchResultParser.ParsedResult parsed = MatchResultParser.parse("11:7, 9:11, 11:9, 11:8");

        assertEquals(3, parsed.player1SetsWon());
        assertEquals(1, parsed.player2SetsWon());
        assertTrue(parsed.complete());
        assertEquals(MatchResultParser.WinnerSide.PLAYER1, parsed.winnerSide());
    }

    @Test
    void acceptedResultFormatSupportsWalkoverTokens() {
        assertTrue(MatchResultParser.isAcceptedResultFormat("W/O"));
        assertTrue(MatchResultParser.isAcceptedResultFormat("walk over"));
        assertTrue(MatchResultParser.isAcceptedResultFormat("3:2"));
        assertTrue(MatchResultParser.isAcceptedResultFormat("03"));
        assertFalse(MatchResultParser.isAcceptedResultFormat("abandoned by referee"));
    }
}
