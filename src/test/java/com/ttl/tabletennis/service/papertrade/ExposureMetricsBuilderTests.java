package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExposureMetricsBuilderTests {

    private static final double EPS = 1e-9;

    private static final ExposureMetricsBuilder.ExposureCaps DEFAULT_CAPS =
            new ExposureMetricsBuilder.ExposureCaps(16, 0.60, 0.18, 0.24);

    @Test
    void emptyOpenList_yieldsCleanEmptyMetrics() {
        PaperTradeSession session = session(1000.0);

        PaperTradingSessionDto.ExposureMetricsDto dto =
                ExposureMetricsBuilder.buildExposureMetrics(session, List.of(), DEFAULT_CAPS);

        assertEquals(0.0, dto.openExposure(), EPS);
        // Bankroll 1000 × 60% cap = 600 (capital base lower-clamped at 100)
        assertEquals(600.0, dto.openExposureCap(), 1e-6);
        assertEquals(0.0, dto.openExposureUsagePct(), EPS);
        assertEquals(600.0, dto.openExposureRemaining(), 1e-6);
        assertEquals(16, dto.maxConcurrentOpenBets());
        assertEquals(0.0, dto.concurrentOpenBetUsagePct(), EPS);
        assertNull(dto.mostExposedPlayerName());
        assertEquals(0.0, dto.mostExposedPlayerStake(), EPS);
        assertNull(dto.mostExposedTrigger());
    }

    @Test
    void summarisesPerPlayerAndPerTriggerExposure() {
        PaperTradeSession session = session(1000.0);
        // Two bets on player 1 ($120 total), one bet on player 2 ($30).
        // Triggers: Smash $90+$30 = $120; Topspin $30; Backspin $0.
        List<PaperTradeBet> open = List.of(
                openBet(1L, "Alice", "Smash", 90.0),
                openBet(1L, "Alice", "Topspin", 30.0),
                openBet(2L, "Bob", "Smash", 30.0)
        );

        PaperTradingSessionDto.ExposureMetricsDto dto =
                ExposureMetricsBuilder.buildExposureMetrics(session, open, DEFAULT_CAPS);

        // Total open exposure
        assertEquals(150.0, dto.openExposure(), 1e-6);
        // Most-exposed player = Alice ($120)
        assertEquals("Alice", dto.mostExposedPlayerName());
        assertEquals(120.0, dto.mostExposedPlayerStake(), 1e-6);
        // capitalBase = max(bankroll, round2(bankroll + openStake)) = max(1000, 1150) = 1150
        // Player cap = 1150 × 18% = 207. Usage = 120 / 207 ≈ 0.5797
        assertEquals(207.0, dto.mostExposedPlayerCap(), 1e-6);
        assertEquals(0.5797, dto.mostExposedPlayerCapUsagePct(), 1e-3);
        // Most-exposed trigger = "smash" ($120)
        assertEquals("smash", dto.mostExposedTrigger());
        assertEquals(120.0, dto.mostExposedTriggerStake(), 1e-6);
        // Trigger cap = 1150 × 24% = 276. Usage = 120 / 276 ≈ 0.4348
        assertEquals(276.0, dto.mostExposedTriggerCap(), 1e-6);
        assertEquals(0.4348, dto.mostExposedTriggerCapUsagePct(), 1e-3);
    }

    @Test
    void capitalBaseIncludesOpenStake_evenWhenBankrollIsLow() {
        // Bankroll = 50 (below the 100 floor), open stake = 300.
        // capitalBase = max(50, round2(50+300)) = 350, then max(100, 350) = 350.
        // openExposureCap = 350 × 0.60 = 210 → but openExposure = 300 → usage > 1.
        PaperTradeSession session = session(50.0);
        List<PaperTradeBet> open = List.of(openBet(1L, "Alice", "Smash", 300.0));

        PaperTradingSessionDto.ExposureMetricsDto dto =
                ExposureMetricsBuilder.buildExposureMetrics(session, open, DEFAULT_CAPS);

        assertEquals(300.0, dto.openExposure(), 1e-6);
        assertEquals(210.0, dto.openExposureCap(), 1e-6);
        // Usage clamps at 2.0
        assertTrue(dto.openExposureUsagePct() > 1.0);
        assertTrue(dto.openExposureUsagePct() <= 2.0);
        // remaining = max(0, cap-exposure) — exposure overran, so remaining = 0
        assertEquals(0.0, dto.openExposureRemaining(), EPS);
    }

    @Test
    void countsPlayersAndTriggersNearCap() {
        // capitalBase grows with open stake, so engineer the numbers to land
        // two players ≥ 80% of playerCap and no triggers near triggerCap.
        // bankroll = 1000, openStake = 375 → capitalBase = max(1000, 1375) = 1375.
        // playerCap = 1375 × 0.18 = 247.5; 80%-threshold = 198.
        // triggerCap = 1375 × 0.24 = 330; 80%-threshold = 264.
        PaperTradeSession session = session(1000.0);
        List<PaperTradeBet> open = List.of(
                openBet(1L, "Alice", "Smash", 200.0),    // 200 / 247.5 = 81% → near cap
                openBet(2L, "Bob", "Topspin", 170.0),    // 170 / 247.5 = 69%  → not near
                openBet(3L, "Carol", "Slice", 5.0)
        );
        // Alice barely scrapes near-cap; Bob doesn't. With one player near, this
        // doubles as a guard: the math doesn't accidentally drop everyone.

        PaperTradingSessionDto.ExposureMetricsDto dto =
                ExposureMetricsBuilder.buildExposureMetrics(session, open, DEFAULT_CAPS);

        assertEquals(1, dto.playerNearCapCount(), "only Alice clears the 80% threshold");
        // Triggers: Smash 200/330 = 61%, Topspin 170/330 = 52%, Slice 5/330 = 2%. None near.
        assertEquals(0, dto.triggerNearCapCount());
    }

    @Test
    void unknownPlayerName_fallsBackToPlayerIdLabel() {
        // sidePlayerId set but sideName blank → "Player <id>" label.
        PaperTradeSession session = session(500.0);
        PaperTradeBet anon = new PaperTradeBet();
        anon.setStatus(PaperTradeBet.STATUS_OPEN);
        anon.setSidePlayerId(42L);
        anon.setSideName("  ");
        anon.setTopTrigger("Smash");
        anon.setStake(10.0);

        PaperTradingSessionDto.ExposureMetricsDto dto =
                ExposureMetricsBuilder.buildExposureMetrics(session, List.of(anon), DEFAULT_CAPS);

        assertEquals("Player 42", dto.mostExposedPlayerName());
    }

    private static PaperTradeSession session(double bankroll) {
        PaperTradeSession session = new PaperTradeSession();
        session.setCurrentBankroll(bankroll);
        return session;
    }

    private static PaperTradeBet openBet(Long playerId, String playerName, String trigger, double stake) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        bet.setSidePlayerId(playerId);
        bet.setSideName(playerName);
        bet.setTopTrigger(trigger);
        bet.setStake(stake);
        return bet;
    }
}
