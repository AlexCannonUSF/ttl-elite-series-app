package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;

/**
 * Pure-function builder: settled {@link PaperTradeBet} rows → equity curve points.
 *
 * <p>Second slice of the §4 SessionService extract per
 * {@code docs/.../runbooks/paper-trading-service-decomposition.md}.
 * Mirrors the shape of {@link TriggerInsightsBuilder}: single call site in
 * {@code PaperTradingService.buildSessionDto}, zero shared state, zero
 * repository touches, single bounded-size return.
 *
 * <p>The curve starts at the session's starting bankroll (cumulative pnl = 0)
 * and walks the settled rows in order, appending one point per settled bet.
 * The cap at {@link #MAX_POINTS} points keeps the JSON payload bounded; the
 * last N points are kept (the tail is the interesting bit for a live curve).
 */
public final class EquityCurveBuilder {

    private static final int MAX_POINTS = 250;

    private EquityCurveBuilder() {
        // utility class — not instantiable
    }

    public static List<PaperTradingSessionDto.EquityPointDto> buildEquityCurve(PaperTradeSession session,
                                                                                List<PaperTradeBet> settledRows) {
        List<PaperTradingSessionDto.EquityPointDto> curve = new ArrayList<>();
        LocalDateTime startAt = session.getCreatedAt() == null ? LocalDateTime.now() : session.getCreatedAt();
        double cumulative = 0.0;
        curve.add(new PaperTradingSessionDto.EquityPointDto(startAt, session.getStartingBankroll(), cumulative));
        if (settledRows == null || settledRows.isEmpty()) {
            return curve;
        }
        for (PaperTradeBet bet : settledRows) {
            if (bet.getProfitLoss() == null) {
                continue;
            }
            cumulative = round2(cumulative + bet.getProfitLoss());
            LocalDateTime at = bet.getSettledAt() == null ? bet.getPlacedAt() : bet.getSettledAt();
            curve.add(new PaperTradingSessionDto.EquityPointDto(
                    at,
                    round2(session.getStartingBankroll() + cumulative),
                    cumulative
            ));
        }
        if (curve.size() > MAX_POINTS) {
            return curve.subList(curve.size() - MAX_POINTS, curve.size());
        }
        return curve;
    }
}
