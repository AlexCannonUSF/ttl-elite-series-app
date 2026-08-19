package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.DecisionOpportunity;
import com.ttl.tabletennis.domain.RunAnnotation;
import com.ttl.tabletennis.domain.RunBenchmarkEvaluation;
import com.ttl.tabletennis.domain.RunModelLaneDefinition;
import com.ttl.tabletennis.domain.RunPortfolioDecision;
import com.ttl.tabletennis.domain.RunPortfolioDefinition;
import com.ttl.tabletennis.dto.ResearchRunFoundationDto;
import com.ttl.tabletennis.dto.RunAnnotationRequest;
import com.ttl.tabletennis.dto.ModelCallTrackingDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.DecisionOpportunityRepository;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.RunAnnotationRepository;
import com.ttl.tabletennis.repository.RunBenchmarkEvaluationRepository;
import com.ttl.tabletennis.repository.RunModelLaneDefinitionRepository;
import com.ttl.tabletennis.repository.RunModelLaneEvaluationRepository;
import com.ttl.tabletennis.repository.RunPortfolioDecisionRepository;
import com.ttl.tabletennis.repository.RunPortfolioDefinitionRepository;
import com.ttl.tabletennis.service.papertrade.ModelCallLedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResearchFoundationQueryService {
    private final PaperTradeSessionRepository sessionRepository;
    private final PaperTradeModelCallRepository callRepository;
    private final DecisionOpportunityRepository opportunityRepository;
    private final RunModelLaneDefinitionRepository laneRepository;
    private final RunModelLaneEvaluationRepository evaluationRepository;
    private final RunPortfolioDefinitionRepository portfolioRepository;
    private final RunPortfolioDecisionRepository decisionRepository;
    private final RunBenchmarkEvaluationRepository benchmarkRepository;
    private final RunAnnotationRepository annotationRepository;
    private final ModelCallLedgerService ledgerService;

    public ResearchFoundationQueryService(PaperTradeSessionRepository sessionRepository,
                                          PaperTradeModelCallRepository callRepository,
                                          DecisionOpportunityRepository opportunityRepository,
                                          RunModelLaneDefinitionRepository laneRepository,
                                          RunModelLaneEvaluationRepository evaluationRepository,
                                          RunPortfolioDefinitionRepository portfolioRepository,
                                          RunPortfolioDecisionRepository decisionRepository,
                                          RunBenchmarkEvaluationRepository benchmarkRepository,
                                          RunAnnotationRepository annotationRepository,
                                          ModelCallLedgerService ledgerService) {
        this.sessionRepository = sessionRepository;
        this.callRepository = callRepository;
        this.opportunityRepository = opportunityRepository;
        this.laneRepository = laneRepository;
        this.evaluationRepository = evaluationRepository;
        this.portfolioRepository = portfolioRepository;
        this.decisionRepository = decisionRepository;
        this.benchmarkRepository = benchmarkRepository;
        this.annotationRepository = annotationRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true)
    public ResearchRunFoundationDto forRun(long runId) {
        requireRun(runId);
        List<DecisionOpportunity> opportunities = opportunityRepository.findBySessionIdOrderByFirstObservedAtAsc(runId);
        long opportunityCount = opportunities.size();
        long calls = callRepository.countBySessionId(runId);
        EvaluationIndex index = evaluationIndex(runId, opportunities);
        List<ResearchRunFoundationDto.ModelLane> lanes = laneRepository
                .findBySessionIdOrderByOrdinalPositionAsc(runId).stream()
                .map(lane -> laneDto(lane, opportunityCount, index))
                .toList();
        List<ResearchRunFoundationDto.Portfolio> portfolios = portfolioRepository
                .findBySessionIdOrderByIdAsc(runId).stream()
                .map(portfolio -> portfolioDto(portfolio, opportunityCount, index))
                .toList();
        List<Long> opportunityIds = opportunities.stream().map(DecisionOpportunity::getId).toList();
        List<RunBenchmarkEvaluation> benchmarkRows = opportunityIds.isEmpty()
                ? List.of()
                : benchmarkRepository.findByOpportunityIdIn(opportunityIds);
        Map<String, List<RunBenchmarkEvaluation>> benchmarkGroups = benchmarkRows.stream()
                .collect(java.util.stream.Collectors.groupingBy(RunBenchmarkEvaluation::getBenchmarkKey,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<ResearchRunFoundationDto.Benchmark> benchmarks = benchmarkGroups.entrySet().stream()
                .map(entry -> benchmarkDto(entry.getKey(), entry.getValue(), opportunityCount, index))
                .toList();
        List<ResearchRunFoundationDto.Annotation> annotations = annotationRepository
                .findBySessionIdOrderByCreatedAtDesc(runId).stream().map(ResearchFoundationQueryService::annotationDto).toList();
        long synchronizedCount = lanes.stream().filter(ResearchRunFoundationDto.ModelLane::primary)
                .mapToLong(ResearchRunFoundationDto.ModelLane::evaluations).max().orElse(0L);
        double telemetryCompleteness = calls == 0 ? 0.0 : coverage(synchronizedCount, calls);
        return new ResearchRunFoundationDto(runId, opportunityCount, calls, synchronizedCount,
                telemetryCompleteness, lanes, portfolios, benchmarks, annotations);
    }

    @Transactional
    public ResearchRunFoundationDto.Annotation annotate(long runId, RunAnnotationRequest request) {
        requireRun(runId);
        RunAnnotation annotation = new RunAnnotation();
        annotation.setSessionId(runId);
        annotation.setTargetType(normalize(request.targetType(), "RUN"));
        annotation.setTargetId(trimToNull(request.targetId()));
        annotation.setAnnotationText(request.text().trim());
        annotation.setTags(request.tags() == null ? null : request.tags().stream()
                .filter(StringUtils::hasText).map(String::trim).distinct().limit(12)
                .reduce((left, right) -> left + "," + right).orElse(null));
        annotation.setAuthor(normalize(request.author(), "OPERATOR"));
        return annotationDto(annotationRepository.save(annotation));
    }

    private ResearchRunFoundationDto.ModelLane laneDto(RunModelLaneDefinition lane, long opportunities,
                                                        EvaluationIndex index) {
        List<com.ttl.tabletennis.domain.RunModelLaneEvaluation> rows = evaluationRepository
                .findByLaneDefinitionIdOrderByCapturedAtAsc(lane.getId());
        long evaluations = rows.size();
        Performance performance = performance(rows.stream().map(row -> {
            DecisionOpportunity opportunity = index.opportunitiesById().get(row.getOpportunityId());
            ModelCallTrackingDto call = opportunity == null ? null : index.callsByEventKey().get(opportunity.getEventKey());
            Integer price = executionPrice(call, row.getPredictedWinnerPlayerId());
            return new Selection(row.getOpportunityId(), row.getPredictedWinnerPlayerId(), price,
                    opportunity == null ? null : opportunity.getPlayer1Id(), row.getPlayer1Probability());
        }).toList(), index);
        return new ResearchRunFoundationDto.ModelLane(lane.getId(), lane.getLaneKey(), lane.getDisplayName(),
                lane.getLaneRole(), lane.getOrdinalPosition(), lane.getModelFamily(), lane.getModelVersion(),
                lane.getArtifactChecksum(), lane.getFeatureSchemaChecksum(), lane.getCalibrationId(),
                lane.isEnabled(), lane.isPrimaryLane(), evaluations, coverage(evaluations, opportunities),
                performance.resolved(), performance.correct(), performance.accuracyPct(), performance.brierScore(),
                performance.pricedResolved(), performance.flatStakePnl(), performance.flatStakeRoiPct());
    }

    private ResearchRunFoundationDto.Portfolio portfolioDto(RunPortfolioDefinition portfolio, long opportunities,
                                                              EvaluationIndex index) {
        List<RunPortfolioDecision> decisions = decisionRepository.findByPortfolioDefinitionIdOrderByCapturedAtAsc(portfolio.getId());
        long actioned = decisions.stream().filter(row -> isActioned(row.getDecisionStatus())).count();
        long passed = decisions.stream().filter(row -> "SKIPPED".equalsIgnoreCase(row.getDecisionStatus())
                || "NO_LEAN".equalsIgnoreCase(row.getDecisionStatus())).count();
        List<Selection> actionedSelections = decisions.stream()
                .filter(row -> isActioned(row.getDecisionStatus()))
                .map(row -> new Selection(row.getOpportunityId(), row.getSelectedPlayerId(), row.getAmericanOdds(),
                        null, null)).toList();
        Performance performance = performance(actionedSelections, index);
        return new ResearchRunFoundationDto.Portfolio(portfolio.getId(), portfolio.getPortfolioKey(),
                portfolio.getDisplayName(), portfolio.getPortfolioType(), portfolio.getModelLaneKey(),
                portfolio.getPolicyVersion(), portfolio.isEnabled(), portfolio.isPrimaryPortfolio(),
                decisions.size(), actioned, passed, coverage(decisions.size(), opportunities),
                performance.resolved(), performance.correct(), performance.accuracyPct(),
                performance.pricedResolved(), performance.flatStakePnl(), performance.flatStakeRoiPct());
    }

    private ResearchRunFoundationDto.Benchmark benchmarkDto(String key, List<RunBenchmarkEvaluation> rows,
                                                             long opportunities, EvaluationIndex index) {
        Performance performance = performance(rows.stream()
                .map(row -> new Selection(row.getOpportunityId(), row.getSelectedPlayerId(),
                        row.getAmericanOdds(), null, null)).toList(), index);
        return new ResearchRunFoundationDto.Benchmark(key, rows.size(), coverage(rows.size(), opportunities),
                performance.resolved(), performance.correct(), performance.accuracyPct(),
                performance.pricedResolved(), performance.flatStakePnl(), performance.flatStakeRoiPct());
    }

    private EvaluationIndex evaluationIndex(long runId, List<DecisionOpportunity> opportunities) {
        Map<Long, DecisionOpportunity> byId = opportunities.stream().collect(java.util.stream.Collectors.toMap(
                DecisionOpportunity::getId, opportunity -> opportunity, (left, right) -> left, LinkedHashMap::new));
        Map<String, ModelCallTrackingDto> byEvent = ledgerService.monitorAllForResearch(runId).calls().stream()
                .filter(call -> StringUtils.hasText(call.eventKey()))
                .collect(java.util.stream.Collectors.toMap(ModelCallTrackingDto::eventKey, call -> call,
                        ResearchFoundationQueryService::preferTrusted, LinkedHashMap::new));
        return new EvaluationIndex(byId, byEvent);
    }

    private static ModelCallTrackingDto preferTrusted(ModelCallTrackingDto left, ModelCallTrackingDto right) {
        if (left.systemWinnerPlayerId() == null && right.systemWinnerPlayerId() != null) return right;
        return left;
    }

    private static Performance performance(List<Selection> selections, EvaluationIndex index) {
        long resolved = 0;
        long correct = 0;
        long pricedResolved = 0;
        double pnl = 0.0;
        double brierTotal = 0.0;
        long brierCount = 0;
        for (Selection selection : selections) {
            DecisionOpportunity opportunity = index.opportunitiesById().get(selection.opportunityId());
            ModelCallTrackingDto call = opportunity == null ? null : index.callsByEventKey().get(opportunity.getEventKey());
            Long winnerId = call == null ? null : call.systemWinnerPlayerId();
            if (winnerId == null || selection.playerId() == null) continue;
            resolved++;
            boolean won = winnerId.equals(selection.playerId());
            if (won) correct++;
            if (selection.americanOdds() != null && selection.americanOdds() != 0) {
                pricedResolved++;
                pnl += won ? profit(selection.americanOdds()) : -1.0;
            }
            if (selection.player1Probability() != null && selection.player1Id() != null) {
                double outcome = winnerId.equals(selection.player1Id()) ? 1.0 : 0.0;
                double error = selection.player1Probability() - outcome;
                brierTotal += error * error;
                brierCount++;
            }
        }
        double accuracy = resolved == 0 ? 0.0 : round2(correct * 100.0 / resolved);
        double roundedPnl = round4(pnl);
        double roi = pricedResolved == 0 ? 0.0 : round2(roundedPnl * 100.0 / pricedResolved);
        Double brier = brierCount == 0 ? null : round4(brierTotal / brierCount);
        return new Performance(resolved, correct, accuracy, brier, pricedResolved, roundedPnl, roi);
    }

    private static Integer executionPrice(ModelCallTrackingDto call, Long selectedPlayerId) {
        if (call == null || selectedPlayerId == null || call.predictedWinnerPlayerId() == null) return null;
        return selectedPlayerId.equals(call.predictedWinnerPlayerId())
                ? call.hardRockAmericanOdds() : call.opponentHardRockAmericanOdds();
    }

    private static boolean isActioned(String status) {
        return "PLACED".equalsIgnoreCase(status) || "TRACKED".equalsIgnoreCase(status)
                || "DISCOVERY".equalsIgnoreCase(status);
    }

    private static double profit(int americanOdds) {
        return americanOdds > 0 ? americanOdds / 100.0 : 100.0 / Math.abs(americanOdds);
    }

    private static double round2(double value) { return Math.round(value * 100.0) / 100.0; }
    private static double round4(double value) { return Math.round(value * 10_000.0) / 10_000.0; }

    private record EvaluationIndex(Map<Long, DecisionOpportunity> opportunitiesById,
                                   Map<String, ModelCallTrackingDto> callsByEventKey) { }
    private record Selection(Long opportunityId, Long playerId, Integer americanOdds,
                             Long player1Id, Double player1Probability) { }
    private record Performance(long resolved, long correct, double accuracyPct, Double brierScore,
                               long pricedResolved, double flatStakePnl, double flatStakeRoiPct) { }

    private void requireRun(long runId) {
        if (runId <= 0 || !sessionRepository.existsById(runId)) {
            throw new ResourceNotFoundException("Run " + runId + " was not found");
        }
    }

    private static ResearchRunFoundationDto.Annotation annotationDto(RunAnnotation row) {
        List<String> tags = !StringUtils.hasText(row.getTags()) ? List.of()
                : Arrays.stream(row.getTags().split(",")).map(String::trim).filter(StringUtils::hasText).toList();
        return new ResearchRunFoundationDto.Annotation(row.getId(), row.getTargetType(), row.getTargetId(),
                row.getAnnotationText(), tags, row.getAuthor(), row.getCreatedAt());
    }

    private static double coverage(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : Math.round(numerator * 10_000.0 / denominator) / 100.0;
    }

    private static String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private static String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
}
