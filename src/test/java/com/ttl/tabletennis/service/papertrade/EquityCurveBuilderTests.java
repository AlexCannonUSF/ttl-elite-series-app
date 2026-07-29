package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquityCurveBuilderTests {

    private static final double EPS = 1e-9;

    @Test
    void buildsStartingPointOnly_whenNoSettledRows() {
        PaperTradeSession session = session(LocalDateTime.parse("2026-05-01T12:00:00"), 100.0);

        List<PaperTradingSessionDto.EquityPointDto> curve =
                EquityCurveBuilder.buildEquityCurve(session, List.of());

        assertEquals(1, curve.size(), "curve always seeds with the starting bankroll point");
        PaperTradingSessionDto.EquityPointDto seed = curve.get(0);
        assertEquals(100.0, seed.bankroll(), EPS);
        assertEquals(0.0, seed.cumulativePnl(), EPS);
        assertEquals(LocalDateTime.parse("2026-05-01T12:00:00"), seed.at());
    }

    @Test
    void buildsStartingPointOnly_whenSettledRowsIsNull() {
        PaperTradeSession session = session(LocalDateTime.parse("2026-05-01T12:00:00"), 50.0);

        List<PaperTradingSessionDto.EquityPointDto> curve =
                EquityCurveBuilder.buildEquityCurve(session, null);

        assertEquals(1, curve.size());
        assertEquals(50.0, curve.get(0).bankroll(), EPS);
    }

    @Test
    void walksCumulativePnlAcrossSettledRows() {
        PaperTradeSession session = session(LocalDateTime.parse("2026-05-01T12:00:00"), 100.0);
        List<PaperTradeBet> rows = List.of(
                settledBet(LocalDateTime.parse("2026-05-01T13:00:00"), 10.0),
                settledBet(LocalDateTime.parse("2026-05-01T14:00:00"), -5.0),
                settledBet(LocalDateTime.parse("2026-05-01T15:00:00"), 7.5)
        );

        List<PaperTradingSessionDto.EquityPointDto> curve =
                EquityCurveBuilder.buildEquityCurve(session, rows);

        assertEquals(4, curve.size(), "seed + one point per settled row");
        assertEquals(100.0, curve.get(0).bankroll(), EPS);
        assertEquals(0.0, curve.get(0).cumulativePnl(), EPS);
        assertEquals(110.0, curve.get(1).bankroll(), EPS);
        assertEquals(10.0, curve.get(1).cumulativePnl(), EPS);
        assertEquals(105.0, curve.get(2).bankroll(), EPS);
        assertEquals(5.0, curve.get(2).cumulativePnl(), EPS);
        assertEquals(112.5, curve.get(3).bankroll(), EPS);
        assertEquals(12.5, curve.get(3).cumulativePnl(), EPS);
    }

    @Test
    void skipsBetsWithNullProfitLoss() {
        PaperTradeSession session = session(LocalDateTime.parse("2026-05-01T12:00:00"), 100.0);
        PaperTradeBet nullPnl = new PaperTradeBet();
        nullPnl.setSettledAt(LocalDateTime.parse("2026-05-01T13:00:00"));
        nullPnl.setProfitLoss(null);

        List<PaperTradingSessionDto.EquityPointDto> curve = EquityCurveBuilder.buildEquityCurve(
                session,
                List.of(nullPnl, settledBet(LocalDateTime.parse("2026-05-01T14:00:00"), 4.0))
        );

        assertEquals(2, curve.size(), "null-pnl rows must not add a point");
        assertEquals(104.0, curve.get(1).bankroll(), EPS);
        assertEquals(4.0, curve.get(1).cumulativePnl(), EPS);
    }

    @Test
    void capsCurveAt250Points_keepingTail() {
        PaperTradeSession session = session(LocalDateTime.parse("2026-05-01T12:00:00"), 100.0);
        List<PaperTradeBet> rows = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            rows.add(settledBet(LocalDateTime.parse("2026-05-01T12:00:00").plusMinutes(i + 1), 0.10));
        }

        List<PaperTradingSessionDto.EquityPointDto> curve =
                EquityCurveBuilder.buildEquityCurve(session, rows);

        assertEquals(250, curve.size(), "MAX_POINTS = 250");
        PaperTradingSessionDto.EquityPointDto last = curve.get(curve.size() - 1);
        // Final cumulative after 300 bets of 0.10 each = 30.00 (rounded).
        assertEquals(30.0, last.cumulativePnl(), 1e-6);
        assertTrue(last.bankroll() > curve.get(0).bankroll(), "tail bankroll exceeds head bankroll");
    }

    private static PaperTradeSession session(LocalDateTime createdAt, double startingBankroll) {
        PaperTradeSession session = new PaperTradeSession();
        session.setCreatedAt(createdAt);
        session.setStartingBankroll(startingBankroll);
        return session;
    }

    private static PaperTradeBet settledBet(LocalDateTime settledAt, double profitLoss) {
        PaperTradeBet bet = new PaperTradeBet();
        bet.setSettledAt(settledAt);
        bet.setProfitLoss(profitLoss);
        return bet;
    }
}
