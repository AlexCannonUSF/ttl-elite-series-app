package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import com.ttl.tabletennis.dto.CounterfactualSkippedReportDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Counterfactual analyzer for picks the staking policy considered but
 * skipped. For each skipped row in {@code paper_trade_decision_sample} we
 * try to attribute the actual match outcome (joining to {@code matches}
 * by player pair and a small date window) and compute the profit-or-loss
 * that would have accrued at the proposed stake. Aggregating by
 * {@code decision_reason} surfaces whether each gate (edge threshold,
 * confidence width, exposure caps, …) is currently helping or hurting EV.
 *
 * <p><strong>Design notes:</strong>
 * <ul>
 *   <li>Read-only: never mutates the decision sample or match tables. The
 *   analysis is deterministic and idempotent — running it twice yields the
 *   same numbers.</li>
 *   <li>Player-pair + date window join: matches are looked up via
 *   {@link MatchRepository#findByPlayersAndDate} at the exact date first
 *   then ±1 and ±2 days. TT Elite Series matches occasionally land in the
 *   official scrape on the next calendar day in CE(S)T vs UTC.</li>
 *   <li>Counterfactual P/L uses {@code proposedStake} (not
 *   {@code cappedStake}) because the skip happens BEFORE any cap is
 *   applied. Decimal odds are reconstructed from
 *   {@code americanOdds}.</li>
 *   <li>Undecidable rows (no matching {@code Match} found, or
 *   {@code winnerPlayerId} not yet populated) are surfaced separately so
 *   the operator can see attribution coverage.</li>
 * </ul>
 */
@Service
public class CounterfactualSkippedPickAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CounterfactualSkippedPickAnalysisService.class);
    /** decisionStatus value used by {@code DecisionTelemetryBuilder} for passed-on picks. */
    static final String SKIPPED_STATUS = "SKIPPED";
    /** ± window (days) we search for the matching official match record. */
    private static final int MATCH_LOOKUP_WINDOW_DAYS = 2;

    private final PaperTradeDecisionSampleRepository decisionSampleRepository;
    private final MatchRepository matchRepository;
    private final Clock clock;

    @Autowired
    public CounterfactualSkippedPickAnalysisService(PaperTradeDecisionSampleRepository decisionSampleRepository,
                                                    MatchRepository matchRepository) {
        this(decisionSampleRepository, matchRepository, Clock.systemDefaultZone());
    }

    CounterfactualSkippedPickAnalysisService(PaperTradeDecisionSampleRepository decisionSampleRepository,
                                             MatchRepository matchRepository,
                                             Clock clock) {
        this.decisionSampleRepository = decisionSampleRepository;
        this.matchRepository = matchRepository;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional(readOnly = true)
    public CounterfactualSkippedReportDto analyze(int lookbackDays) {
        int days = Math.max(1, Math.min(lookbackDays, 90));
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(days);
        List<PaperTradeDecisionSample> skipped = decisionSampleRepository
                .findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc(SKIPPED_STATUS, cutoff);

        // SKIPPED rows have proposedStake=null when the skip happened
        // BEFORE the stake math ran (most reasons: DUPLICATE_OPEN_EVENT,
        // EVENT_NOT_UPCOMING, EDGE_BELOW_THRESHOLD, etc.). Without a
        // stake we'd report counterfactual P/L of $0 for every row,
        // which makes the whole analysis useless. Fall back to the
        // average stake of PLACED bets in the same lookback window —
        // that's the realistic answer to "what would I have made if
        // I'd staked this at typical size".
        double fallbackStake = estimateFallbackStake(cutoff);

        Map<String, ReasonAccumulator> byReason = new HashMap<>();
        int decided = 0;
        int undecided = 0;
        int wins = 0;
        int losses = 0;
        double totalCounterfactualPnL = 0.0;
        double totalProposedStake = 0.0;

        for (PaperTradeDecisionSample sample : skipped) {
            String reason = sample.getDecisionReason() == null ? "UNKNOWN" : sample.getDecisionReason();
            ReasonAccumulator acc = byReason.computeIfAbsent(reason, r -> new ReasonAccumulator());
            acc.totalSkipped++;

            double stake = sample.getProposedStake() == null || sample.getProposedStake() <= 0.0
                    ? fallbackStake
                    : sample.getProposedStake();
            Double edge = sample.getSuggestedEdge();
            if (edge != null) {
                acc.edgeSum += edge;
                acc.edgeSamples++;
            }

            Optional<Match> matchOpt = findCounterfactualMatch(
                    sample.getPlayer1Id(),
                    sample.getPlayer2Id(),
                    sample.getCreatedAt() == null ? LocalDate.now(clock) : sample.getCreatedAt().toLocalDate());
            if (matchOpt.isEmpty()) {
                acc.undecided++;
                undecided++;
                continue;
            }
            Long winnerId = matchOpt.get().getWinnerPlayerId();
            if (winnerId == null || sample.getSidePlayerId() == null) {
                acc.undecided++;
                undecided++;
                continue;
            }

            double decimalOdds = americanToDecimal(sample.getAmericanOdds());
            boolean sideWon = Objects.equals(sample.getSidePlayerId(), winnerId);
            double counterfactualPnL = sideWon ? stake * (decimalOdds - 1.0) : -stake;

            acc.decided++;
            if (sideWon) {
                acc.wins++;
                wins++;
            } else {
                acc.losses++;
                losses++;
            }
            acc.counterfactualPnL += counterfactualPnL;
            acc.totalProposedStake += stake;
            decided++;
            totalCounterfactualPnL += counterfactualPnL;
            totalProposedStake += stake;
        }

        List<CounterfactualSkippedReportDto.ReasonBreakdownDto> byReasonDto = new ArrayList<>();
        for (Map.Entry<String, ReasonAccumulator> e : byReason.entrySet()) {
            ReasonAccumulator a = e.getValue();
            double avgEdgePct = a.edgeSamples == 0 ? 0.0 : (a.edgeSum / a.edgeSamples) * 100.0;
            double roiPct = a.totalProposedStake <= 0.0 ? 0.0 : (a.counterfactualPnL / a.totalProposedStake) * 100.0;
            double winRatePct = a.decided == 0 ? 0.0 : (a.wins / (double) a.decided) * 100.0;
            byReasonDto.add(new CounterfactualSkippedReportDto.ReasonBreakdownDto(
                    e.getKey(),
                    a.totalSkipped,
                    a.decided,
                    a.wins,
                    a.losses,
                    round2(a.counterfactualPnL),
                    round2(roiPct),
                    round2(winRatePct),
                    round2(avgEdgePct),
                    round2(a.totalProposedStake)
            ));
        }
        // Sort by absolute counterfactual P/L so the most impactful gates surface first.
        byReasonDto.sort(Comparator
                .comparingDouble((CounterfactualSkippedReportDto.ReasonBreakdownDto r) -> Math.abs(r.counterfactualPnL()))
                .reversed());

        double overallRoi = totalProposedStake <= 0.0 ? 0.0 : (totalCounterfactualPnL / totalProposedStake) * 100.0;
        return new CounterfactualSkippedReportDto(
                days,
                LocalDateTime.now(clock),
                skipped.size(),
                decided,
                undecided,
                wins,
                losses,
                round2(totalCounterfactualPnL),
                round2(overallRoi),
                byReasonDto
        );
    }

    /**
     * Returns the average non-zero stake across PLACED bets in the same
     * lookback window. Used as the counterfactual stake when a skipped
     * pick was rejected before its stake was sized (most skip reasons fire
     * before that stage). Falls back to $25 if no placed bets exist — a
     * sensible default for a $1000 paper bankroll with the conservative
     * staking policy.
     */
    private double estimateFallbackStake(LocalDateTime cutoff) {
        List<PaperTradeDecisionSample> placed = decisionSampleRepository
                .findByDecisionStatusAndCreatedAtAfterOrderByCreatedAtAsc("PLACED", cutoff);
        double sum = 0.0;
        int n = 0;
        for (PaperTradeDecisionSample p : placed) {
            Double s = p.getCappedStake() != null ? p.getCappedStake() : p.getProposedStake();
            if (s != null && s > 0.0) {
                sum += s;
                n++;
            }
        }
        if (n == 0) {
            return 25.0;
        }
        return sum / n;
    }

    private Optional<Match> findCounterfactualMatch(Long p1, Long p2, LocalDate near) {
        if (p1 == null || p2 == null || near == null) {
            return Optional.empty();
        }
        // Try exact date first, then expand outward to catch CET/UTC drift.
        // {@link MatchRepository#findByPlayersAndDate} returns Optional which
        // throws NonUniqueResultException when the same pair played twice on
        // the same day (TT Elite Series groups can do best-of-X mini-matches).
        // findRecentMatchesByPlayers returns a list ordered DESC; filtering
        // for the target date in-memory and taking the first preserves the
        // most-recent-id-wins tie-break the rest of the system uses.
        for (int delta = 0; delta <= MATCH_LOOKUP_WINDOW_DAYS; delta++) {
            for (int sign : delta == 0 ? new int[]{0} : new int[]{1, -1}) {
                LocalDate candidate = near.plusDays((long) sign * delta);
                try {
                    Optional<Match> match = matchRepository.findByPlayersAndDate(p1, p2, candidate);
                    if (match.isPresent()) {
                        return match;
                    }
                } catch (org.springframework.dao.IncorrectResultSizeDataAccessException multipleHits) {
                    // Multiple matches on this date — query repo for the list
                    // and pick the most recent one (which is most likely the
                    // final / decisive round, and matches the convention used
                    // by the settlement path).
                    List<Match> all = matchRepository.findRecentMatchesByPlayers(p1, p2,
                            org.springframework.data.domain.PageRequest.of(0, 8));
                    for (Match m : all) {
                        if (candidate.equals(m.getDate())) {
                            return Optional.of(m);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static double americanToDecimal(Integer american) {
        if (american == null || american == 0) {
            return 1.0;
        }
        if (american > 0) {
            return 1.0 + american / 100.0;
        }
        return 1.0 + 100.0 / Math.abs(american);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static final class ReasonAccumulator {
        int totalSkipped;
        int decided;
        int undecided;
        int wins;
        int losses;
        double counterfactualPnL;
        double totalProposedStake;
        double edgeSum;
        int edgeSamples;
    }
}
