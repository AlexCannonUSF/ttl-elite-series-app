package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
 * <p>Behaviour is verbatim from {@code PaperTradingService.buildDecisionTelemetry}.
 * The {@code averageNonNull} helper was used only by this builder, so it
 * moves with its owner and stays package-private.
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
        List<PaperTradeDecisionSample> rows = decisionSampleRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (rows == null || rows.isEmpty()) {
            return EMPTY;
        }

        long consideredCount = rows.size();
        long placedCount = rows.stream()
                .filter(sample -> "PLACED".equalsIgnoreCase(sample.getDecisionStatus()))
                .count();
        long skippedCount = rows.stream()
                .filter(sample -> "SKIPPED".equalsIgnoreCase(sample.getDecisionStatus()))
                .count();
        long fallbackPlacedCount = rows.stream()
                .filter(PaperTradeDecisionSample::isFallbackPick)
                .filter(sample -> "PLACED".equalsIgnoreCase(sample.getDecisionStatus()))
                .count();
        double placementRatePct = consideredCount == 0 ? 0.0 : round4((placedCount * 100.0) / consideredCount);
        double avgSelectionScore = averageNonNull(rows, PaperTradeDecisionSample::getSelectionScore);
        double avgSignalQualityPct = averageNonNull(rows, PaperTradeDecisionSample::getSignalQuality) * 100.0;
        double avgPlacedEdgePct = averageNonNull(
                rows.stream().filter(sample -> "PLACED".equalsIgnoreCase(sample.getDecisionStatus())).toList(),
                PaperTradeDecisionSample::getSuggestedEdge
        ) * 100.0;
        double avgSkippedEdgePct = averageNonNull(
                rows.stream().filter(sample -> "SKIPPED".equalsIgnoreCase(sample.getDecisionStatus())).toList(),
                PaperTradeDecisionSample::getSuggestedEdge
        ) * 100.0;

        Map<String, Integer> skipReasons = new HashMap<>();
        for (PaperTradeDecisionSample row : rows) {
            if (row == null || !"SKIPPED".equalsIgnoreCase(row.getDecisionStatus())) {
                continue;
            }
            String reason = safeText(row.getDecisionReason(), "UNKNOWN");
            skipReasons.merge(reason, 1, Integer::sum);
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

    /**
     * Mean of the extractor over rows, skipping rows where the extractor
     * returns {@code null}. Returns 0 on an empty stream (legacy semantics).
     */
    static double averageNonNull(List<PaperTradeDecisionSample> rows,
                                 Function<PaperTradeDecisionSample, Double> extractor) {
        if (rows == null || rows.isEmpty() || extractor == null) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (PaperTradeDecisionSample row : rows) {
            if (row == null) {
                continue;
            }
            Double value = extractor.apply(row);
            if (value == null) {
                continue;
            }
            sum += value;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }
}
