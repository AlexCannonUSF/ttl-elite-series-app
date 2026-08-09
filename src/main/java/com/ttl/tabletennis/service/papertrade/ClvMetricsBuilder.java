package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;

/**
 * Read-only CLV (closing-line value) aggregator for the session snapshot.
 *
 * <p>Third §4 SessionService slice (see {@code docs/.../runbooks/paper-trading-service-decomposition.md}).
 * Sibling to {@link TriggerInsightsBuilder} and {@link EquityCurveBuilder};
 * this one carries state because it has to ask the
 * {@link OddsSnapshotRepository} for closing-line candidates within a 7-day
 * window — so it's a Spring {@code @Service} rather than a static utility.
 *
 * <p><b>Behaviour preservation:</b> the logic here is a verbatim move from
 * {@code PaperTradingService.buildClvMetrics}. The slight overlap with
 * {@link com.ttl.tabletennis.prediction.staking.ClosingLineLookupService}
 * (which targets {@code TOP}/{@code BOT} side codes and a different window-
 * end strategy) is intentional and documented in the runbook: deduplication
 * belongs to the future {@code PlacementService} extract, not to a refactor
 * step that is meant to be behaviourally inert.
 */
@Service
public class ClvMetricsBuilder {

    private static final Logger log = LoggerFactory.getLogger(ClvMetricsBuilder.class);

    /** Empty-result sentinel returned when no repository is wired (legacy fallback). */
    private static final PaperTradingSessionDto.ClvMetricsDto EMPTY =
            new PaperTradingSessionDto.ClvMetricsDto(0, 0, 0.0, 0.0, 0.0, 0.0, null);

    private final OddsSnapshotRepository oddsSnapshotRepository;

    public ClvMetricsBuilder(OddsSnapshotRepository oddsSnapshotRepository) {
        this.oddsSnapshotRepository = oddsSnapshotRepository;
    }

    @Transactional(readOnly = true)
    public PaperTradingSessionDto.ClvMetricsDto buildClvMetrics(List<PaperTradeBet> recentRows) {
        if (oddsSnapshotRepository == null) {
            return EMPTY;
        }
        List<PaperTradeBet> rows = recentRows == null ? List.of() : recentRows;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusDays(7);
        int betsInWindow = 0;
        int betsWithClosingSnapshot = 0;
        double clvSum = 0.0;
        double placedImpliedSum = 0.0;
        double closingImpliedSum = 0.0;
        LocalDateTime lastClosingSnapshotAt = null;

        for (PaperTradeBet bet : rows) {
            if (bet == null || bet.getPlacedAt() == null || bet.getPlacedAt().isBefore(cutoff)) {
                continue;
            }
            String bookerEventId = firstNonBlank(bet.getLockedExternalEventId(), bet.getExternalEventId());
            String side = snapshotSide(bet);
            if (!StringUtils.hasText(bookerEventId) || !StringUtils.hasText(side)) {
                continue;
            }
            betsInWindow++;
            LocalDateTime placedAtUtc = com.ttl.tabletennis.prediction.staking.ClosingLineLookupService
                    .localDatabaseTimeToUtc(bet.getPlacedAt());
            LocalDateTime until = com.ttl.tabletennis.prediction.staking.ClosingLineLookupService
                    .closingCutoffUtc(bet);
            LocalDateTime nowUtc = com.ttl.tabletennis.prediction.staking.ClosingLineLookupService
                    .localDatabaseTimeToUtc(now);
            if (until.isAfter(nowUtc)) until = nowUtc;
            if (until.isBefore(placedAtUtc)) until = placedAtUtc;
            try {
                List<OddsSnapshot> closingCandidates = oddsSnapshotRepository.findClosingCandidates(
                        bookerEventId,
                        side,
                        placedAtUtc,
                        until,
                        PageRequest.of(0, 1)
                );
                if (closingCandidates.isEmpty()) {
                    continue;
                }
                OddsSnapshot closing = closingCandidates.get(0);
                double placedImplied = clamp(bet.getImpliedProbability(), 0.0, 1.0);
                double closingImplied = clamp(closing.getImpliedProb(), 0.0, 1.0);
                betsWithClosingSnapshot++;
                clvSum += closingImplied - placedImplied;
                placedImpliedSum += placedImplied;
                closingImpliedSum += closingImplied;
                if (closing.getObservedAt() != null && (lastClosingSnapshotAt == null
                        || closing.getObservedAt().isAfter(lastClosingSnapshotAt))) {
                    lastClosingSnapshotAt = closing.getObservedAt();
                }
            } catch (RuntimeException ex) {
                log.warn("[paper] unable to calculate CLV for bet {}: {}", bet.getId(), ex.getMessage());
            }
        }

        double coverage = betsInWindow == 0 ? 0.0 : betsWithClosingSnapshot / (double) betsInWindow;
        double avgClvPct = betsWithClosingSnapshot == 0 ? 0.0 : (clvSum / betsWithClosingSnapshot) * 100.0;
        double avgPlacedImpliedPct = betsWithClosingSnapshot == 0 ? 0.0 : (placedImpliedSum / betsWithClosingSnapshot) * 100.0;
        double avgClosingImpliedPct = betsWithClosingSnapshot == 0 ? 0.0 : (closingImpliedSum / betsWithClosingSnapshot) * 100.0;
        return new PaperTradingSessionDto.ClvMetricsDto(
                betsInWindow,
                betsWithClosingSnapshot,
                round4(coverage),
                round4(avgClvPct),
                round4(avgPlacedImpliedPct),
                round4(avgClosingImpliedPct),
                lastClosingSnapshotAt
        );
    }

    /**
     * Side-code mapping ({@code P1}/{@code P2}) used by the {@code odds_snapshot}
     * query. Falls back to comparable-name matching when the {@code sidePlayerId}
     * is missing — preserves the legacy behaviour that lets old bets without
     * canonical ids still resolve to a snapshot side.
     */
    static String snapshotSide(PaperTradeBet bet) {
        if (bet == null) {
            return null;
        }
        if (bet.getSidePlayerId() != null && bet.getPlayer1Id() != null
                && Objects.equals(bet.getSidePlayerId(), bet.getPlayer1Id())) {
            return "P1";
        }
        if (bet.getSidePlayerId() != null && bet.getPlayer2Id() != null
                && Objects.equals(bet.getSidePlayerId(), bet.getPlayer2Id())) {
            return "P2";
        }
        String sideName = normalizeComparableName(bet.getSideName());
        if (StringUtils.hasText(sideName) && sideName.equals(normalizeComparableName(bet.getPlayer1Name()))) {
            return "P1";
        }
        if (StringUtils.hasText(sideName) && sideName.equals(normalizeComparableName(bet.getPlayer2Name()))) {
            return "P2";
        }
        return null;
    }

    private static String normalizeComparableName(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
