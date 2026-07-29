package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Counterfactual report for picks the staking policy considered but
 * <em>did not place</em>. For each such skipped pick we attempt to attribute
 * an outcome (was the suggested side the actual winner?) and compute the
 * profit-or-loss that <strong>would have</strong> accrued had the bet been
 * placed at the proposed stake.
 *
 * <p>This lets operators answer questions like:
 * <ul>
 *   <li>"Is our {@code EDGE_BELOW_THRESHOLD} cutoff costing us EV or saving
 *   us from variance?"</li>
 *   <li>"Are picks the staking policy passed on
 *   {@code CONFIDENCE_TOO_WIDE} actually winning more often than the placed
 *   ones?"</li>
 *   <li>"What is the total counterfactual upside left on the table by our
 *   exposure-cap discipline?"</li>
 * </ul>
 *
 * <p>Outcomes are decidable only for picks whose match has already been
 * scraped into the {@code matches} table by {@code TtSeriesScraper}; the
 * {@code undecided} bucket counts skipped picks whose matches are not yet
 * resolved (or whose player ids did not match a recorded match).
 */
public record CounterfactualSkippedReportDto(
        int lookbackDays,
        LocalDateTime generatedAt,
        int totalSkipped,
        int decided,
        int undecided,
        int wins,
        int losses,
        double counterfactualPnL,
        double counterfactualRoiPct,
        List<ReasonBreakdownDto> byReason) {

    public record ReasonBreakdownDto(
            String reason,
            int totalSkipped,
            int decided,
            int wins,
            int losses,
            double counterfactualPnL,
            double counterfactualRoiPct,
            double winRatePct,
            double avgEdgePct,
            double totalProposedStake) { }
}
