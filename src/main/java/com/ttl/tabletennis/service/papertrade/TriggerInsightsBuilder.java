package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.EPS;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round2;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;

/**
 * Pure-function aggregator: settled {@link PaperTradeBet} rows →
 * top-N {@link PaperTradingSessionDto.TriggerInsightDto}.
 *
 * <p>First slice of the §4 SessionService extract per
 * {@code docs/.../runbooks/paper-trading-service-decomposition.md}.
 * Picked because the function has a single call site
 * ({@code PaperTradingService.buildSessionDto}), zero shared state, and
 * zero repository touches — same shape as the earlier query-service
 * extracts but pure compute instead of repository-backed.
 *
 * <p>The "top 8" cap mirrors the original behaviour. The ordering is
 * primarily by row count, secondarily by absolute pnl so a high-impact
 * outlier trigger doesn't get masked by a high-volume trigger with near-
 * zero net pnl.
 */
public final class TriggerInsightsBuilder {

    private static final int MAX_INSIGHTS = 8;

    private TriggerInsightsBuilder() {
        // utility class — not instantiable
    }

    public static List<PaperTradingSessionDto.TriggerInsightDto> buildTopTriggers(List<PaperTradeBet> settledRows) {
        if (settledRows == null || settledRows.isEmpty()) {
            return List.of();
        }

        Map<String, TriggerAggregate> aggregateByTrigger = new LinkedHashMap<>();
        for (PaperTradeBet bet : settledRows) {
            if (!LearningSampleQuality.assess(bet).learningEligible()) {
                continue;
            }
            String trigger = StringUtils.hasText(bet.getTopTrigger()) ? bet.getTopTrigger().trim() : "Unknown Trigger";
            TriggerAggregate aggregate = aggregateByTrigger.get(trigger);
            if (aggregate == null) {
                aggregate = new TriggerAggregate(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0);
            }
            aggregateByTrigger.put(trigger, aggregate.add(bet));
        }

        List<PaperTradingSessionDto.TriggerInsightDto> out = new ArrayList<>();
        for (Map.Entry<String, TriggerAggregate> entry : aggregateByTrigger.entrySet()) {
            TriggerAggregate agg = entry.getValue();
            int decisions = agg.wins + agg.losses;
            double winRate = decisions == 0 ? 0.0 : agg.wins / (double) decisions;
            double avgEdgePct = agg.count == 0 ? 0.0 : (agg.edgeSum / agg.count) * 100.0;
            double avgModelProb = agg.count == 0 ? 0.0 : (agg.modelProbSum / agg.count);
            double avgImpliedProb = agg.count == 0 ? 0.0 : (agg.impliedProbSum / agg.count);
            double avgConfidenceWidthPct = agg.confidenceCount == 0 ? 0.0 : (agg.confidenceWidthSum / agg.confidenceCount) * 100.0;
            double calibrationDeltaPct = decisions == 0
                    ? 0.0
                    : ((agg.wins / (double) decisions) - avgModelProb) * 100.0;
            double roiPct = agg.stakeSum <= EPS ? 0.0 : (agg.pnl / agg.stakeSum) * 100.0;
            out.add(new PaperTradingSessionDto.TriggerInsightDto(
                    entry.getKey(),
                    agg.count,
                    agg.wins,
                    agg.losses,
                    winRate,
                    round2(agg.pnl),
                    round4(avgEdgePct),
                    round4(avgModelProb),
                    round4(avgImpliedProb),
                    round4(avgConfidenceWidthPct),
                    round4(calibrationDeltaPct),
                    round4(roiPct)
            ));
        }
        out.sort(Comparator
                .comparingInt(PaperTradingSessionDto.TriggerInsightDto::count).reversed()
                .thenComparing((a, b) -> Double.compare(Math.abs(b.pnl()), Math.abs(a.pnl()))));
        if (out.size() > MAX_INSIGHTS) {
            return out.subList(0, MAX_INSIGHTS);
        }
        return out;
    }

    /** Tuple aggregate accumulated as the loop walks the settled rows. */
    private record TriggerAggregate(int count,
                                    int wins,
                                    int losses,
                                    double pnl,
                                    double edgeSum,
                                    double modelProbSum,
                                    double impliedProbSum,
                                    double confidenceWidthSum,
                                    int confidenceCount,
                                    double stakeSum) {
        TriggerAggregate add(PaperTradeBet bet) {
            int addWins = PaperTradeBet.STATUS_WON.equals(bet.getStatus()) ? 1 : 0;
            int addLosses = PaperTradeBet.STATUS_LOST.equals(bet.getStatus()) ? 1 : 0;
            double addPnl = bet.getProfitLoss() == null ? 0.0 : bet.getProfitLoss();
            double width = 0.0;
            int widthCount = 0;
            if (bet.getConfidenceLow() != null && bet.getConfidenceHigh() != null) {
                width = Math.max(0.0, bet.getConfidenceHigh() - bet.getConfidenceLow());
                widthCount = 1;
            }
            return new TriggerAggregate(
                    count + 1,
                    wins + addWins,
                    losses + addLosses,
                    pnl + addPnl,
                    edgeSum + bet.getEdge(),
                    modelProbSum + bet.getModelProbability(),
                    impliedProbSum + bet.getImpliedProbability(),
                    confidenceWidthSum + width,
                    confidenceCount + widthCount,
                    stakeSum + bet.getStake()
            );
        }
    }
}
