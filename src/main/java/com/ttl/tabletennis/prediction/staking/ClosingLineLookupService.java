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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.parseStartDateTime;

/**
 * Resolves the closing-line snapshot for a settled paper-trade bet.
 *
 * <p>Bridges {@link PaperTradeBet} to {@link OddsSnapshotRepository}: derives
 * the bookmaker side ({@code "P1"} / {@code "P2"}) from the bet's
 * {@code sidePlayerId} relative to {@code player1Id}, then queries snapshots
 * after the placement until shortly after the bet settled, preferring
 * {@code CLOSED} state, then {@code SUSPENDED}, then the latest-pre-close
 * observation.
 *
 * <p>Lookup is best-effort: returns {@link Optional#empty()} when the bet is
 * missing identifiers, when no snapshots exist in the window, or when the
 * snapshot's price is not finite. Missing closing prices remain missing;
 * realized P&amp;L is never mislabeled as CLV.
 */
@Service
public class ClosingLineLookupService {

    static final String SIDE_TOP = "P1";
    static final String SIDE_BOT = "P2";
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
        if (placedAt == null) {
            return Optional.empty();
        }
        LocalDateTime placedAtUtc = localDatabaseTimeToUtc(placedAt);
        LocalDateTime closingCutoffUtc = closingCutoffUtc(bet);
        if (closingCutoffUtc.isBefore(placedAtUtc)) {
            closingCutoffUtc = localDatabaseTimeToUtc(
                    bet.getSettledAt() == null ? LocalDateTime.now() : bet.getSettledAt());
        }
        try {
            List<OddsSnapshot> candidates = repository.findClosingCandidates(
                    bookerEventId, side, placedAtUtc, closingCutoffUtc, PageRequest.of(0, 1));
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

    /**
     * Odds snapshots are timestamped in UTC while paper ledgers use the host's
     * local wall clock. Normalizing both bounds fixes the silent zero-coverage
     * CLV bug seen in the completed run.
     */
    public static LocalDateTime localDatabaseTimeToUtc(LocalDateTime localTime) {
        if (localTime == null) return null;
        return localTime.atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    public static LocalDateTime closingCutoffUtc(PaperTradeBet bet) {
        if (bet == null) return localDatabaseTimeToUtc(LocalDateTime.now());
        Optional<LocalDateTime> scheduledStart = parseStartDateTime(bet.getStartTimeIso());
        if (scheduledStart.isPresent()) {
            return localDatabaseTimeToUtc(scheduledStart.get());
        }
        LocalDateTime localCutoff = bet.getSettledAt() == null
                ? LocalDateTime.now()
                : bet.getSettledAt().plus(LOOKUP_BUFFER);
        return localDatabaseTimeToUtc(localCutoff);
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
