package com.ttl.tabletennis.prediction.staking;

import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the closing-line snapshot for a settled paper-trade bet.
 *
 * <p>Bridges {@link PaperTradeBet} to {@link OddsSnapshotRepository}: derives
 * the bookmaker side ({@code "TOP"} / {@code "BOT"}) from the bet's
 * {@code sidePlayerId} relative to {@code player1Id}, then queries snapshots
 * after the placement until shortly after the bet settled, preferring
 * {@code CLOSED} state, then {@code SUSPENDED}, then the latest-pre-close
 * observation.
 *
 * <p>Lookup is best-effort: returns {@link Optional#empty()} when the bet is
 * missing identifiers, when no snapshots exist in the window, or when the
 * snapshot's price is not finite. {@link StakingClvWatcher} treats absence as
 * "fall back to the PnL/stake proxy" so older rows continue to chart.
 */
@Service
public class ClosingLineLookupService {

    static final String SIDE_TOP = "TOP";
    static final String SIDE_BOT = "BOT";
    private static final java.time.Duration LOOKUP_BUFFER = java.time.Duration.ofHours(6);

    private static final Logger log = LoggerFactory.getLogger(ClosingLineLookupService.class);

    private final OddsSnapshotRepository repository;

    public ClosingLineLookupService(OddsSnapshotRepository repository) {
        this.repository = repository;
    }

    public Optional<ClosingLine> findFor(PaperTradeBet bet) {
        if (bet == null) {
            return Optional.empty();
        }
        String bookerEventId = pickBookerEventId(bet);
        if (!StringUtils.hasText(bookerEventId)) {
            return Optional.empty();
        }
        String side = resolveSide(bet);
        if (side == null) {
            return Optional.empty();
        }
        LocalDateTime placedAt = bet.getPlacedAt();
        LocalDateTime settledAt = bet.getSettledAt();
        if (placedAt == null || settledAt == null) {
            return Optional.empty();
        }
        LocalDateTime until = settledAt.plusHours(LOOKUP_BUFFER.toHours());
        try {
            List<OddsSnapshot> candidates = repository.findClosingCandidates(
                    bookerEventId, side, placedAt, until, PageRequest.of(0, 1));
            if (candidates == null || candidates.isEmpty()) {
                return Optional.empty();
            }
            OddsSnapshot top = candidates.get(0);
            if (top == null || !Double.isFinite(top.getPriceDecimal()) || top.getPriceDecimal() <= 1.0) {
                return Optional.empty();
            }
            return Optional.of(new ClosingLine(
                    top.getPriceDecimal(),
                    top.getObservedAt(),
                    top.getMarketState(),
                    top.getSourceId()));
        } catch (RuntimeException ex) {
            log.warn("[closing-line] lookup failed for bet {} side {}: {}",
                    bet.getId(), side, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String pickBookerEventId(PaperTradeBet bet) {
        if (StringUtils.hasText(bet.getLockedExternalEventId())) {
            return bet.getLockedExternalEventId().trim();
        }
        if (StringUtils.hasText(bet.getExternalEventId())) {
            return bet.getExternalEventId().trim();
        }
        return null;
    }

    /**
     * Side mapping: when the bet's {@code sidePlayerId} matches
     * {@code player1Id} we treat the bet as the {@code TOP} side; matching
     * {@code player2Id} maps to {@code BOT}. The {@code odds_snapshot} table
     * is populated in the same orientation by {@code OddsSnapshotFactory}.
     */
    private static String resolveSide(PaperTradeBet bet) {
        Long sidePlayerId = bet.getSidePlayerId();
        if (sidePlayerId == null) {
            return null;
        }
        if (Objects.equals(sidePlayerId, bet.getPlayer1Id())) {
            return SIDE_TOP;
        }
        if (Objects.equals(sidePlayerId, bet.getPlayer2Id())) {
            return SIDE_BOT;
        }
        return null;
    }

    public record ClosingLine(
            double decimalOdds,
            LocalDateTime observedAt,
            String marketState,
            String sourceId
    ) {
        public double impliedProbability() {
            if (!Double.isFinite(decimalOdds) || decimalOdds <= 1.0) {
                return Double.NaN;
            }
            return 1.0 / decimalOdds;
        }
    }
}
