package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.dto.LiveRunAnalyticsDto;
import com.ttl.tabletennis.dto.ModelCallMonitorDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.dto.ModelRunHistoryDto;
import com.ttl.tabletennis.dto.ResearchRunComparisonDto;
import com.ttl.tabletennis.dto.ResearchRunDetailDto;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.service.papertrade.ModelCallLedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only research facade. This is deliberately session-addressable so a
 * historical page cannot combine an old run summary with active-run calls.
 */
@Service
public class ResearchRunService {

    private static final int DEFAULT_DETAIL_LIMIT = 500;

    private final ModelRunHistoryService runHistoryService;
    private final ModelCallLedgerService ledgerService;
    private final PaperTradeModelCallRepository callRepository;
    private final ResearchFoundationQueryService foundationQueryService;

    public ResearchRunService(ModelRunHistoryService runHistoryService,
                              ModelCallLedgerService ledgerService,
                              PaperTradeModelCallRepository callRepository,
                              ResearchFoundationQueryService foundationQueryService) {
        this.runHistoryService = runHistoryService;
        this.ledgerService = ledgerService;
        this.callRepository = callRepository;
        this.foundationQueryService = foundationQueryService;
    }

    @Transactional(readOnly = true)
    public ResearchRunDetailDto detail(long runId) {
        ModelRunHistoryDto.Run run = runHistoryService.run(runId);
        ModelCallScorecardDto scorecard = ledgerService.scorecard(runId, DEFAULT_DETAIL_LIMIT);
        LiveRunAnalyticsDto analytics = ledgerService.analytics(runId, DEFAULT_DETAIL_LIMIT);
        ModelCallMonitorDto pipeline = ledgerService.monitorAllForResearch(runId);
        List<PaperTradeModelCall> calls = callRepository.findBySessionIdOrderByCapturedAtDesc(runId);

        boolean modelIdentityComplete = hasText(run.effectiveModelVersion())
                && hasText(run.effectiveArtifactChecksum())
                && hasText(run.featureSchemaChecksum())
                && hasText(run.calibrationId())
                && hasText(run.policyVersion())
                && hasText(run.codeRevision());
        boolean datasetWindowKnown = !calls.isEmpty()
                && calls.stream().allMatch(call -> call.getMatchIdHighWatermark() != null);
        int postCloseCallCount = run.closedAt() == null
                ? 0
                : (int) calls.stream()
                .filter(call -> call.getCapturedAt() != null && call.getCapturedAt().isAfter(run.closedAt()))
                .count();
        boolean closedRunImmutable = postCloseCallCount == 0;
        boolean settlementComplete = analytics.totalCalls() > 0 && analytics.awaitingCalls() == 0;
        double coverage = analytics.totalCalls() == 0
                ? 0.0
                : round2(analytics.settledCalls() * 100.0 / analytics.totalCalls());
        String status = !closedRunImmutable
                ? "POST_CLOSE_MUTATION"
                : !modelIdentityComplete
                ? "IDENTITY_INCOMPLETE"
                : !datasetWindowKnown
                ? "DATASET_BOUNDARY_INCOMPLETE"
                : !settlementComplete
                ? "SETTLEMENT_IN_PROGRESS"
                : "REPRODUCIBLE";
        String explanation = switch (status) {
            case "POST_CLOSE_MUTATION" -> postCloseCallCount + " call(s) were captured after this run closed; exclude it from promotion decisions until reviewed.";
            case "IDENTITY_INCOMPLETE" -> "One or more model, feature, calibration, policy, or code identifiers are missing.";
            case "DATASET_BOUNDARY_INCOMPLETE" -> "At least one call lacks its point-in-time match high-watermark.";
            case "SETTLEMENT_IN_PROGRESS" -> "Some frozen calls still await a trusted terminal outcome.";
            default -> "Artifact identity, point-in-time boundaries, and settlement coverage are complete.";
        };

        return new ResearchRunDetailDto(
                LocalDateTime.now(),
                run,
                scorecard,
                analytics,
                pipeline,
                foundationQueryService.forRun(runId),
                new ResearchRunDetailDto.Integrity(
                        modelIdentityComplete,
                        datasetWindowKnown,
                        closedRunImmutable,
                        postCloseCallCount,
                        settlementComplete,
                        analytics.totalCalls(),
                        analytics.settledCalls(),
                        analytics.awaitingCalls(),
                        coverage,
                        status,
                        explanation));
    }

    @Transactional(readOnly = true)
    public ResearchRunComparisonDto compare(Collection<Long> requestedRunIds, Integer trendLimit) {
        List<Long> runIds = normalizeRunIds(requestedRunIds);
        int limit = trendLimit == null ? 250 : Math.max(20, Math.min(trendLimit, 500));
        List<RunInput> inputs = runIds.stream().map(runId -> {
            ModelRunHistoryDto.Run run = runHistoryService.run(runId);
            LiveRunAnalyticsDto analytics = ledgerService.analytics(runId, limit);
            Set<String> opportunities = callRepository.findBySessionIdOrderByCapturedAtDesc(runId).stream()
                    .map(ResearchRunService::opportunityKey)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return new RunInput(run, analytics, opportunities);
        }).toList();

        Set<String> shared = new LinkedHashSet<>();
        if (!inputs.isEmpty()) {
            shared.addAll(inputs.get(0).opportunities());
            inputs.stream().skip(1).forEach(input -> shared.retainAll(input.opportunities()));
        }

        List<ResearchRunComparisonDto.RunComparison> rows = inputs.stream()
                .map(input -> new ResearchRunComparisonDto.RunComparison(
                        input.run(),
                        input.analytics(),
                        input.opportunities().size(),
                        shared.size(),
                        input.opportunities().isEmpty()
                                ? 0.0
                                : round2(shared.size() * 100.0 / input.opportunities().size())))
                .toList();

        List<String> cautions = new ArrayList<>();
        cautions.add("Natural-cohort metrics use every eligible call in each run; opportunity counts show comparability but do not rewrite those metrics.");
        if (shared.isEmpty() && runIds.size() > 1) {
            cautions.add("The selected runs have no shared captured opportunities; direct performance differences may reflect different match cohorts.");
        }
        if (rows.stream().anyMatch(row -> row.naturalCohort().awaitingCalls() > 0)) {
            cautions.add("At least one selected run still contains unresolved calls.");
        }

        return new ResearchRunComparisonDto(
                LocalDateTime.now(),
                List.copyOf(runIds),
                shared.size(),
                rows,
                List.copyOf(cautions));
    }

    private static List<Long> normalizeRunIds(Collection<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one run");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : requested) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("Run ids must be positive");
            }
            ids.add(id);
        }
        if (ids.size() > 12) {
            throw new IllegalArgumentException("Compare at most 12 runs at once");
        }
        return List.copyOf(ids);
    }

    private static String opportunityKey(PaperTradeModelCall call) {
        if (hasText(call.getExternalEventId())) {
            return "external:" + normalize(call.getExternalEventId());
        }
        if (hasText(call.getSourceFeedEventId())) {
            return "feed:" + normalize(call.getSourceFeedEventId());
        }
        return "event:" + normalize(call.getEventKey());
    }

    private static String normalize(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record RunInput(ModelRunHistoryDto.Run run,
                            LiveRunAnalyticsDto analytics,
                            Set<String> opportunities) {
    }
}
