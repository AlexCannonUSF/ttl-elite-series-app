package com.ttl.tabletennis.prediction.staking;

import java.time.LocalDate;
import java.util.Objects;

public record OpenPosition(
        String eventKey,
        Long player1Id,
        Long player2Id,
        Long sidePlayerId,
        double stakeUnits,
        LocalDate exposureDate
) {

    public OpenPosition {
        eventKey = eventKey == null || eventKey.isBlank() ? "" : eventKey.trim();
        if (!Double.isFinite(stakeUnits) || stakeUnits < 0.0) {
            throw new IllegalArgumentException("stakeUnits must be finite and non-negative");
        }
    }

    public boolean sameEvent(String otherEventKey) {
        return !eventKey.isBlank()
                && otherEventKey != null
                && eventKey.equalsIgnoreCase(otherEventKey.trim());
    }

    public boolean oppositeSide(Long requestedSidePlayerId) {
        return requestedSidePlayerId != null
                && sidePlayerId != null
                && !Objects.equals(sidePlayerId, requestedSidePlayerId);
    }

    public boolean involvesPlayer(Long playerId) {
        return playerId != null
                && (Objects.equals(player1Id, playerId)
                || Objects.equals(player2Id, playerId)
                || Objects.equals(sidePlayerId, playerId));
    }

    public boolean sameExposureDate(LocalDate requestedDate) {
        return requestedDate == null || exposureDate == null || exposureDate.equals(requestedDate);
    }
}
