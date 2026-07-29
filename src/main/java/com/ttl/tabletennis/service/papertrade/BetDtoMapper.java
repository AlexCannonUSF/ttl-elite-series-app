package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.PaperTradeBetDto;

/**
 * Pure DTO mapper for {@link PaperTradeBet} → {@link PaperTradeBetDto}.
 *
 * <p>Sixth §4 SessionService slice. The 50-line field-by-field constructor
 * call had no business living inside the god-object; it's pure data shuffling
 * with no dependencies beyond the bet itself and a precomputed tracking-state
 * string.
 *
 * <p>The tracking state is computed by the caller (today
 * {@code PaperTradingService.deriveTrackingState}) because that derivation
 * still has a repository hop ({@code preferredTrackedObservationForBet}).
 * Once the rest of {@code PaperTradingService} decomposes, the whole pair
 * can move together into the new {@code SessionService} — for now keeping
 * the tracking-state resolution at the caller preserves behaviour and
 * keeps the blast radius small.
 */
public final class BetDtoMapper {

    private BetDtoMapper() {
        // utility class — not instantiable
    }

    public static PaperTradeBetDto toDto(PaperTradeBet bet, String trackingState) {
        return new PaperTradeBetDto(
                bet.getId(),
                bet.getStatus(),
                bet.getSource(),
                bet.getStrategy(),
                bet.getModelVersion(),
                bet.getEventName(),
                bet.getCompetitionName(),
                bet.isLiveAtPlacement(),
                bet.getStartTimeIso(),
                bet.getExternalEventId(),
                bet.isIdentityLocked(),
                bet.getIdentityLockedAt(),
                bet.getLockedStartTimeIso(),
                bet.getLockedExternalEventId(),
                bet.getLockedSourceFeedEventId(),
                bet.getIdentityDriftCount(),
                bet.getLastIdentityDriftAt(),
                bet.getPlayer1Name(),
                bet.getPlayer2Name(),
                bet.getSideName(),
                bet.getAmericanOdds(),
                bet.getDecimalOdds(),
                bet.getStake(),
                bet.getPotentialPayout(),
                bet.getProfitLoss(),
                bet.getModelProbability(),
                bet.getImpliedProbability(),
                bet.getEdge(),
                bet.getConfidenceLow(),
                bet.getConfidenceHigh(),
                bet.getTopTrigger(),
                bet.getTopTriggerContribution(),
                bet.getGrade(),
                bet.getRationale(),
                bet.getLastObservedScore(),
                bet.getLastObservedPhase(),
                bet.getLastScoreSource(),
                bet.getLastScoreConfidence(),
                bet.isLastObservationDisplayed(),
                bet.isLastObservationResulted(),
                bet.isLastMatchCompleted(),
                bet.getLastSourceFeedCode(),
                bet.getLastSourceFeedEventId(),
                bet.getLastScoreDetail(),
                bet.isTrackedAfterClose(),
                trackingState,
                bet.getSettlementReason(),
                bet.getSettlementSource(),
                bet.getLastObservedAt(),
                bet.getPlacedAt(),
                bet.getSettledAt(),
                bet.getEventKey(),
                bet.getDedupeKey(),
                bet.getResultMatchId(),
                bet.getWinnerPlayerId()
        );
    }
}
