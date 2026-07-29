package com.ttl.tabletennis.prediction.staking;

import java.time.LocalDate;
import java.util.List;

public record StakingRequest(
        String eventKey,
        Long player1Id,
        Long player2Id,
        Long sidePlayerId,
        double modelProbability,
        double decimalOdds,
        double selectedEdge,
        double bankrollUnits,
        LocalDate exposureDate,
        List<OpenPosition> openPositions,
        List<SettledStake> recentSettledBets
) {

    public StakingRequest {
        eventKey = eventKey == null || eventKey.isBlank() ? "" : eventKey.trim();
        modelProbability = requireProbability(modelProbability, "modelProbability");
        if (!Double.isFinite(decimalOdds) || decimalOdds <= 1.0) {
            throw new IllegalArgumentException("decimalOdds must be finite and > 1.0");
        }
        if (!Double.isFinite(selectedEdge)) {
            throw new IllegalArgumentException("selectedEdge must be finite");
        }
        if (!Double.isFinite(bankrollUnits) || bankrollUnits <= 0.0) {
            throw new IllegalArgumentException("bankrollUnits must be finite and positive");
        }
        openPositions = openPositions == null ? List.of() : List.copyOf(openPositions);
        recentSettledBets = recentSettledBets == null ? List.of() : List.copyOf(recentSettledBets);
    }

    private static double requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must lie in [0,1]");
        }
        return value;
    }
}
