package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.round4;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.safeText;

/**
 * Read-only aggregator for the session-snapshot's decision-telemetry block:
 * how many bets the placement loop considered, how many it placed vs.
 * skipped, the top skip reasons, and a few averages over selection score
 * and edge.
 *
 * <p>Fifth §4 SessionService slice — same service-shape as
 * {@link ClvMetricsBuilder}: depends on a repository
 * ({@link PaperTradeDecisionSampleRepository}) so it lands as a Spring
 * {@code @Service} rather than a static utility.
 *
 * <p>The repository returns compact grouped projections rather than all
 * decision entities. This matters on the live path: a long-running session
 * can contain thousands of samples and the user UI polls this snapshot every
 * few seconds.
 */
@Service
public class DecisionTelemetryBuilder {

    private static final int MAX_SKIP_REASONS = 5;

    /** Empty-result sentinel — keeps the null-session and empty-rows branches identical. */
    private static final PaperTradingSessionDto.DecisionTelemetryDto EMPTY =
            new PaperTradingSessionDto.DecisionTelemetryDto(0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, List.of());

    private final PaperTradeDecisionSampleRepository decisionSampleRepository;

    public DecisionTelemetryBuilder(PaperTradeDecisionSampleRepository decisionSampleRepository) {
        this.decisionSampleRepository = decisionSampleRepository;
    }

    @Transactional(readOnly = true)
    public PaperTradingSessionDto.DecisionTelemetryDto buildDecisionTelemetry(Long sessionId) {
        if (sessionId == null) {
            return EMPTY;
        }
        List<PaperTradeDecisionSampleRepository.DecisionSummary> summaries =
                decisionSampleRepository.summarizeBySessionId(sessionId);
        if (summaries == null || summaries.isEmpty()) {
            return EMPTY;
        }

        long consideredCount = 0L;
        long placedCount = 0L;
        long skippedCount = 0L;
        long fallbackPlacedCount = 0L;
        long selectionScoreCount = 0L;
        double selectionScoreSum = 0.0;
        long signalQualityCount = 0L;
        double signalQualitySum = 0.0;
        long placedEdgeCount = 0L;
        double placedEdgeSum = 0.0;
        long skippedEdgeCount = 0L;
        double skippedEdgeSum = 0.0;

        for (PaperTradeDecisionSampleRepository.DecisionSummary summary : summaries) {
            if (summary == null) {
                continue;
            }
            long rowCount = Math.max(0L, summary.getRowCount());
            String status = safeText(summary.getDecisionStatus(), "UNKNOWN");
            consideredCount += rowCount;
            selectionScoreCount += Math.max(0L, summary.getSelectionScoreCount());
            selectionScoreSum += valueOrZero(summary.getSelectionScoreSum());
            signalQualityCount += Math.max(0L, summary.getSignalQualityCount());
            signalQualitySum += valueOrZero(summary.getSignalQualitySum());

            if ("PLACED".equalsIgnoreCase(status)) {
                placedCount += rowCount;
                if (Boolean.TRUE.equals(summary.getFallbackPick())) {
                    fallbackPlacedCount += rowCount;
                }
                placedEdgeCount += Math.max(0L, summary.getSuggestedEdgeCount());
                placedEdgeSum += valueOrZero(summary.getSuggestedEdgeSum());
            } else if ("SKIPPED".equalsIgnoreCase(status)) {
                skippedCount += rowCount;
                skippedEdgeCount += Math.max(0L, summary.getSuggestedEdgeCount());
                skippedEdgeSum += valueOrZero(summary.getSuggestedEdgeSum());
            }
        }

        double placementRatePct = consideredCount == 0 ? 0.0 : round4((placedCount * 100.0) / consideredCount);
        double avgSelectionScore = average(selectionScoreSum, selectionScoreCount);
        double avgSignalQualityPct = average(signalQualitySum, signalQualityCount) * 100.0;
        double avgPlacedEdgePct = average(placedEdgeSum, placedEdgeCount) * 100.0;
        double avgSkippedEdgePct = average(skippedEdgeSum, skippedEdgeCount) * 100.0;

        Map<String, Integer> skipReasons = new HashMap<>();
        List<PaperTradeDecisionSampleRepository.SkipReasonSummary> reasonSummaries =
                decisionSampleRepository.summarizeSkipReasonsBySessionId(sessionId);
        for (PaperTradeDecisionSampleRepository.SkipReasonSummary summary :
                reasonSummaries == null ? List.<PaperTradeDecisionSampleRepository.SkipReasonSummary>of() : reasonSummaries) {
            if (summary == null) {
                continue;
            }
            String reason = safeText(summary.getDecisionReason(), "UNKNOWN");
            int count = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, summary.getRowCount()));
            skipReasons.merge(reason, count, Integer::sum);
        }
        List<PaperTradingSessionDto.DecisionReasonDto> topSkipReasons = skipReasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_SKIP_REASONS)
                .map(entry -> new PaperTradingSessionDto.DecisionReasonDto(entry.getKey(), entry.getValue()))
                .toList();

        return new PaperTradingSessionDto.DecisionTelemetryDto(
                consideredCount,
                placedCount,
                skippedCount,
                fallbackPlacedCount,
                placementRatePct,
                round4(avgSelectionScore),
                round4(avgSignalQualityPct),
                round4(avgPlacedEdgePct),
                round4(avgSkippedEdgePct),
                topSkipReasons
        );
    }

    private static double average(double sum, long count) {
        return count <= 0L ? 0.0 : sum / count;
    }

    private static double valueOrZero(Double value) {
        return value == null || !Double.isFinite(value) ? 0.0 : value;
    }
}
