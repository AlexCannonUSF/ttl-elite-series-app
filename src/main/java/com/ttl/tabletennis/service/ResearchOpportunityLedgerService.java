package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.DecisionOpportunity;
import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.RunBenchmarkEvaluation;
import com.ttl.tabletennis.domain.RunModelLaneDefinition;
import com.ttl.tabletennis.domain.RunModelLaneEvaluation;
import com.ttl.tabletennis.domain.RunPortfolioDecision;
import com.ttl.tabletennis.domain.RunPortfolioDefinition;
import com.ttl.tabletennis.repository.DecisionOpportunityRepository;
import com.ttl.tabletennis.repository.RunBenchmarkEvaluationRepository;
import com.ttl.tabletennis.repository.RunModelLaneDefinitionRepository;
import com.ttl.tabletennis.repository.RunModelLaneEvaluationRepository;
import com.ttl.tabletennis.repository.RunPortfolioDecisionRepository;
import com.ttl.tabletennis.repository.RunPortfolioDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Mirrors the Champion call into the shared research identity graph. The
 * legacy model-call row remains the operational source of truth while every
 * lane, portfolio, and benchmark is anchored to one opportunity.
 */
@Service
public class ResearchOpportunityLedgerService {

    public static final String LANE_CHAMPION = "CHAMPION";
    public static final String PORTFOLIO_STRICT = "CHAMPION_STRICT";
    public static final String PORTFOLIO_ALL_CALLS = "ALL_CALLS";
    public static final String BENCHMARK_HARD_ROCK = "HARD_ROCK_FAVORITE";

    private final DecisionOpportunityRepository opportunityRepository;
    private final RunModelLaneDefinitionRepository laneRepository;
    private final RunModelLaneEvaluationRepository evaluationRepository;
    private final RunPortfolioDefinitionRepository portfolioRepository;
    private final RunPortfolioDecisionRepository decisionRepository;
    private final RunBenchmarkEvaluationRepository benchmarkRepository;
    private final ParallelModelLaneService parallelModelLaneService;

    public ResearchOpportunityLedgerService(DecisionOpportunityRepository opportunityRepository,
                                            RunModelLaneDefinitionRepository laneRepository,
                                            RunModelLaneEvaluationRepository evaluationRepository,
                                            RunPortfolioDefinitionRepository portfolioRepository,
                                            RunPortfolioDecisionRepository decisionRepository,
                                            RunBenchmarkEvaluationRepository benchmarkRepository,
                                            ParallelModelLaneService parallelModelLaneService) {
        this.opportunityRepository = opportunityRepository;
        this.laneRepository = laneRepository;
        this.evaluationRepository = evaluationRepository;
        this.portfolioRepository = portfolioRepository;
        this.decisionRepository = decisionRepository;
        this.benchmarkRepository = benchmarkRepository;
        this.parallelModelLaneService = parallelModelLaneService;
    }

    @Transactional
    public void capture(PaperTradeSession session,
                        PaperTradeModelCall call,
                        PaperTradeDecisionSample sample) {
        if (session == null || call == null || call.getId() == null
                || !PaperTradeSession.STATUS_ACTIVE.equals(session.getStatus())) {
            return;
        }
        DecisionOpportunity opportunity = opportunityRepository
                .findBySessionIdAndEventKey(session.getId(), call.getEventKey())
                .orElseGet(DecisionOpportunity::new);
        boolean newOpportunity = opportunity.getId() == null;
        opportunity.setSessionId(session.getId());
        opportunity.setEventKey(call.getEventKey());
        opportunity.setExternalEventId(call.getExternalEventId());
        opportunity.setSourceFeedEventId(call.getSourceFeedEventId());
        opportunity.setEventName(call.getEventName());
        opportunity.setCompetitionName(call.getCompetitionName());
        opportunity.setPlayer1Id(call.getPlayer1Id());
        opportunity.setPlayer2Id(call.getPlayer2Id());
        opportunity.setStartTimeIso(call.getStartTimeIso());
        opportunity.setCaptureType(call.getCaptureType());
        opportunity.setMatchIdHighWatermark(call.getMatchIdHighWatermark());
        if (newOpportunity) opportunity.setFirstObservedAt(call.getCapturedAt());
        if (PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE.equals(call.getCaptureType())) {
            opportunity.setFrozenAt(call.getCapturedAt());
        }
        opportunity = opportunityRepository.save(opportunity);

        RunModelLaneDefinition champion = championLane(session, call);
        RunModelLaneEvaluation evaluation = evaluationRepository
                .findByOpportunityIdAndLaneDefinitionId(opportunity.getId(), champion.getId())
                .orElseGet(RunModelLaneEvaluation::new);
        evaluation.setOpportunityId(opportunity.getId());
        evaluation.setLaneDefinitionId(champion.getId());
        evaluation.setSourceModelCallId(call.getId());
        evaluation.setCapturedAt(call.getCapturedAt());
        applyProbabilities(evaluation, call);
        evaluation.setPredictedWinnerPlayerId(call.getPredictedWinnerPlayerId());
        evaluation.setPredictedWinnerName(call.getPredictedWinnerName());
        evaluation.setRawProbability(call.getRawModelProbability());
        evaluation.setConfidenceLow(call.getConfidenceLow());
        evaluation.setConfidenceHigh(call.getConfidenceHigh());
        evaluation.setFairAmericanOdds(call.getModelFairAmericanOdds());
        evaluation.setSelectionScore(call.getSelectionScore());
        evaluation.setSignalQuality(call.getSignalQuality());
        evaluation.setTopTrigger(call.getTopTrigger());
        evaluation.setFeatureContributions(call.getFeatureContributions());
        evaluation = evaluationRepository.save(evaluation);

        RunPortfolioDefinition strict = portfolio(session, PORTFOLIO_STRICT, "Champion Strict", "POLICY",
                LANE_CHAMPION, true, true);
        upsertStrictDecision(opportunity, evaluation, strict, call, sample);
        RunPortfolioDefinition allCalls = portfolio(session, PORTFOLIO_ALL_CALLS, "All Model Leans", "RESEARCH",
                LANE_CHAMPION, true, false);
        upsertAllCallDecision(opportunity, evaluation, allCalls, call);
        upsertHardRockBenchmark(opportunity, call);
        parallelModelLaneService.captureShadows(session, opportunity, call);
    }

    private RunModelLaneDefinition championLane(PaperTradeSession session, PaperTradeModelCall call) {
        RunModelLaneDefinition lane = laneRepository.findBySessionIdAndLaneKey(session.getId(), LANE_CHAMPION)
                .orElseGet(RunModelLaneDefinition::new);
        lane.setSessionId(session.getId());
        lane.setLaneKey(LANE_CHAMPION);
        lane.setDisplayName("Champion");
        lane.setLaneRole("CHAMPION");
        lane.setOrdinalPosition(0);
        lane.setModelFamily(session.getEffectiveModelFamily());
        lane.setModelVersion(call.getModelVersion());
        lane.setArtifactChecksum(call.getArtifactChecksum());
        lane.setFeatureSchemaChecksum(call.getFeatureSchemaChecksum());
        lane.setCalibrationId(call.getCalibrationId());
        lane.setEnabled(true);
        lane.setPrimaryLane(true);
        return laneRepository.save(lane);
    }

    private RunPortfolioDefinition portfolio(PaperTradeSession session,
                                             String key,
                                             String name,
                                             String type,
                                             String laneKey,
                                             boolean enabled,
                                             boolean primary) {
        RunPortfolioDefinition definition = portfolioRepository.findBySessionIdAndPortfolioKey(session.getId(), key)
                .orElseGet(RunPortfolioDefinition::new);
        definition.setSessionId(session.getId());
        definition.setPortfolioKey(key);
        definition.setDisplayName(name);
        definition.setPortfolioType(type);
        definition.setModelLaneKey(laneKey);
        definition.setPolicyVersion(session.getPolicyVersion());
        definition.setPolicyJson("{\"frozen\":true,\"source\":\"paper-session-policy\"}");
        definition.setEnabled(enabled);
        definition.setPrimaryPortfolio(primary);
        return portfolioRepository.save(definition);
    }

    private void upsertStrictDecision(DecisionOpportunity opportunity,
                                      RunModelLaneEvaluation evaluation,
                                      RunPortfolioDefinition portfolio,
                                      PaperTradeModelCall call,
                                      PaperTradeDecisionSample sample) {
        RunPortfolioDecision decision = decisionRepository
                .findByOpportunityIdAndPortfolioDefinitionId(opportunity.getId(), portfolio.getId())
                .orElseGet(RunPortfolioDecision::new);
        decision.setOpportunityId(opportunity.getId());
        decision.setPortfolioDefinitionId(portfolio.getId());
        decision.setLaneEvaluationId(evaluation.getId());
        decision.setDecisionStatus(call.getDecisionStatus());
        decision.setDecisionReason(call.getDecisionReason());
        decision.setSelectedPlayerId(sample == null ? null : sample.getSidePlayerId());
        decision.setSelectedPlayerName(sample == null ? null : sample.getSideName());
        decision.setModelProbability(sample == null ? null : sample.getModelProbability());
        decision.setMarketProbability(sample == null ? null : sample.getImpliedProbability());
        decision.setEdgeValue(call.getSuggestedEdge());
        decision.setAmericanOdds(sample == null ? null : sample.getAmericanOdds());
        decision.setVirtualStake(sample == null ? null : sample.getCappedStake());
        decision.setCapturedAt(call.getCapturedAt());
        decisionRepository.save(decision);
    }

    private void upsertAllCallDecision(DecisionOpportunity opportunity,
                                       RunModelLaneEvaluation evaluation,
                                       RunPortfolioDefinition portfolio,
                                       PaperTradeModelCall call) {
        RunPortfolioDecision decision = decisionRepository
                .findByOpportunityIdAndPortfolioDefinitionId(opportunity.getId(), portfolio.getId())
                .orElseGet(RunPortfolioDecision::new);
        decision.setOpportunityId(opportunity.getId());
        decision.setPortfolioDefinitionId(portfolio.getId());
        decision.setLaneEvaluationId(evaluation.getId());
        decision.setDecisionStatus(call.getPredictedWinnerPlayerId() == null ? "NO_LEAN" : "TRACKED");
        decision.setDecisionReason("EVERY_FROZEN_MODEL_CALL");
        decision.setSelectedPlayerId(call.getPredictedWinnerPlayerId());
        decision.setSelectedPlayerName(call.getPredictedWinnerName());
        decision.setModelProbability(call.getModelProbability());
        decision.setMarketProbability(call.getHardRockNoVigProbability());
        decision.setEdgeValue(call.getSuggestedEdge());
        decision.setAmericanOdds(call.getHardRockAmericanOdds());
        decision.setVirtualStake(call.getPredictedWinnerPlayerId() == null ? 0.0 : 1.0);
        decision.setCapturedAt(call.getCapturedAt());
        decisionRepository.save(decision);
    }

    private void upsertHardRockBenchmark(DecisionOpportunity opportunity, PaperTradeModelCall call) {
        Integer selectedOdds = call.getHardRockAmericanOdds();
        Long selectedId = call.getPredictedWinnerPlayerId();
        String selectedName = call.getPredictedWinnerName();
        Double probability = call.getHardRockNoVigProbability();
        if (call.getHardRockAmericanOdds() != null && call.getOpponentHardRockAmericanOdds() != null
                && call.getOpponentHardRockAmericanOdds() < call.getHardRockAmericanOdds()) {
            selectedOdds = call.getOpponentHardRockAmericanOdds();
            if (call.getPredictedWinnerPlayerId() != null && call.getPredictedWinnerPlayerId().equals(call.getPlayer1Id())) {
                selectedId = call.getPlayer2Id();
                selectedName = call.getPlayer2Name();
            } else {
                selectedId = call.getPlayer1Id();
                selectedName = call.getPlayer1Name();
            }
            probability = probability == null ? null : 1.0 - probability;
        }
        RunBenchmarkEvaluation benchmark = benchmarkRepository
                .findByOpportunityIdAndBenchmarkKey(opportunity.getId(), BENCHMARK_HARD_ROCK)
                .orElseGet(RunBenchmarkEvaluation::new);
        benchmark.setOpportunityId(opportunity.getId());
        benchmark.setBenchmarkKey(BENCHMARK_HARD_ROCK);
        benchmark.setSelectedPlayerId(selectedId);
        benchmark.setSelectedPlayerName(selectedName);
        benchmark.setProbability(probability);
        benchmark.setAmericanOdds(selectedOdds);
        benchmark.setSourceCode("HARD_ROCK");
        benchmark.setCapturedAt(call.getCapturedAt() == null ? LocalDateTime.now() : call.getCapturedAt());
        benchmarkRepository.save(benchmark);
    }

    private static void applyProbabilities(RunModelLaneEvaluation evaluation, PaperTradeModelCall call) {
        Double probability = call.getModelProbability();
        if (probability == null || call.getPredictedWinnerPlayerId() == null) return;
        if (call.getPredictedWinnerPlayerId().equals(call.getPlayer1Id())) {
            evaluation.setPlayer1Probability(probability);
            evaluation.setPlayer2Probability(1.0 - probability);
        } else {
            evaluation.setPlayer1Probability(1.0 - probability);
            evaluation.setPlayer2Probability(probability);
        }
    }
}
