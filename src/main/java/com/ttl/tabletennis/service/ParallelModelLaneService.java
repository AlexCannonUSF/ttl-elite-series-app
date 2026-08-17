package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.DecisionOpportunity;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.RunModelLaneDefinition;
import com.ttl.tabletennis.domain.RunModelLaneEvaluation;
import com.ttl.tabletennis.domain.RunPortfolioDecision;
import com.ttl.tabletennis.domain.RunPortfolioDefinition;
import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.repository.RunModelLaneDefinitionRepository;
import com.ttl.tabletennis.repository.RunModelLaneEvaluationRepository;
import com.ttl.tabletennis.repository.RunPortfolioDecisionRepository;
import com.ttl.tabletennis.repository.RunPortfolioDefinitionRepository;
import com.ttl.tabletennis.service.papertrade.PaperTradingHelpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates shadow models on the same point-in-time opportunity as Champion.
 * Shadow decisions never place bets; they are counterfactual research rows.
 */
@Service
public class ParallelModelLaneService {
    private static final Logger log = LoggerFactory.getLogger(ParallelModelLaneService.class);
    private static final int MAX_TOTAL_LANES = 5;

    private final PredictionFacade predictionFacade;
    private final ModelArtifactIdentityService identityService;
    private final RunModelLaneDefinitionRepository laneRepository;
    private final RunModelLaneEvaluationRepository evaluationRepository;
    private final RunPortfolioDefinitionRepository portfolioRepository;
    private final RunPortfolioDecisionRepository decisionRepository;

    @Value("${ttl.research.parallel.enabled:true}") private boolean enabled;
    @Value("${ttl.research.parallel.shadowFamilies:LOGISTIC,ENSEMBLE}") private String shadowFamilies;
    @Value("${ttl.research.portfolio.balanced.minEdge:0.018}") private double balancedMinEdge;
    @Value("${ttl.research.portfolio.balanced.minProbability:0.52}") private double balancedMinProbability;
    @Value("${ttl.research.portfolio.discovery.minEdge:-0.005}") private double discoveryMinEdge;
    @Value("${ttl.research.portfolio.discovery.samplePct:35}") private int discoverySamplePct;

    public ParallelModelLaneService(PredictionFacade predictionFacade,
                                    ModelArtifactIdentityService identityService,
                                    RunModelLaneDefinitionRepository laneRepository,
                                    RunModelLaneEvaluationRepository evaluationRepository,
                                    RunPortfolioDefinitionRepository portfolioRepository,
                                    RunPortfolioDecisionRepository decisionRepository) {
        this.predictionFacade = predictionFacade;
        this.identityService = identityService;
        this.laneRepository = laneRepository;
        this.evaluationRepository = evaluationRepository;
        this.portfolioRepository = portfolioRepository;
        this.decisionRepository = decisionRepository;
    }

    @Transactional
    public void captureShadows(PaperTradeSession session,
                               DecisionOpportunity opportunity,
                               PaperTradeModelCall championCall) {
        if (!enabled || session == null || opportunity == null || championCall == null
                || championCall.getPlayer1Id() == null || championCall.getPlayer2Id() == null) return;
        List<String> families = Arrays.stream(shadowFamilies.split(","))
                .map(String::trim).filter(StringUtils::hasText).map(value -> value.toUpperCase(Locale.ROOT))
                .distinct().limit(MAX_TOTAL_LANES - 1L).toList();
        int ordinal = 1;
        RunModelLaneEvaluation balancedLane = null;
        for (String requestedFamily : families) {
            try {
                RunModelLaneEvaluation evaluation = evaluate(session, opportunity, championCall, requestedFamily, ordinal++);
                if (balancedLane == null) balancedLane = evaluation;
            } catch (RuntimeException failure) {
                log.warn("[research-lane] shadow evaluation unavailable session={} event={} family={} reason={}",
                        session.getId(), championCall.getEventKey(), requestedFamily, failure.getMessage());
            }
        }
        if (balancedLane != null) {
            recordCounterfactualPortfolios(session, opportunity, championCall, balancedLane);
        }
    }

    private RunModelLaneEvaluation evaluate(PaperTradeSession session,
                                            DecisionOpportunity opportunity,
                                            PaperTradeModelCall call,
                                            String requestedFamily,
                                            int ordinal) {
        LocalDate asOf = PaperTradingHelpers.parseStartDateTime(call.getStartTimeIso())
                .map(LocalDateTime::toLocalDate).orElse(LocalDate.now());
        PredictionModelService.PredictionSnapshot snapshot = predictionFacade.predict(
                call.getPlayer1Id(), call.getPlayer2Id(), asOf, requestedFamily);
        if (snapshot == null || !StringUtils.hasText(snapshot.modelVersion())) {
            throw new IllegalStateException("no frozen model snapshot");
        }
        ModelArtifactIdentityService.ModelArtifactIdentity identity = identityService.resolve(snapshot.modelVersion());
        if (!identity.complete()) {
            throw new IllegalStateException("model artifact identity is incomplete");
        }
        if (snapshot.modelVersion().equals(call.getModelVersion())) {
            throw new IllegalStateException("shadow resolves to the Champion artifact");
        }
        String laneKey = "SHADOW_" + requestedFamily;
        RunModelLaneDefinition lane = laneRepository.findBySessionIdAndLaneKey(session.getId(), laneKey)
                .orElseGet(RunModelLaneDefinition::new);
        if (lane.getId() != null && !snapshot.modelVersion().equals(lane.getModelVersion())) {
            throw new IllegalStateException("lane artifact drift: pinned=" + lane.getModelVersion()
                    + " incoming=" + snapshot.modelVersion());
        }
        lane.setSessionId(session.getId());
        lane.setLaneKey(laneKey);
        lane.setDisplayName(prettyFamily(requestedFamily) + " Shadow");
        lane.setLaneRole("SHADOW");
        lane.setOrdinalPosition(ordinal);
        lane.setModelFamily(snapshot.modelFamily());
        lane.setModelVersion(snapshot.modelVersion());
        lane.setArtifactChecksum(identity.artifactChecksum());
        lane.setFeatureSchemaChecksum(identity.featureSchemaChecksum());
        lane.setCalibrationId(identity.calibrationId());
        lane.setEnabled(true);
        lane.setPrimaryLane(false);
        lane = laneRepository.save(lane);

        RunModelLaneEvaluation evaluation = evaluationRepository
                .findByOpportunityIdAndLaneDefinitionId(opportunity.getId(), lane.getId())
                .orElseGet(RunModelLaneEvaluation::new);
        boolean player1 = snapshot.player1Probability() >= snapshot.player2Probability();
        double winnerProbability = player1 ? snapshot.player1Probability() : snapshot.player2Probability();
        double rawWinnerProbability = player1 ? snapshot.rawPlayer1Probability() : 1.0 - snapshot.rawPlayer1Probability();
        evaluation.setOpportunityId(opportunity.getId());
        evaluation.setLaneDefinitionId(lane.getId());
        evaluation.setCapturedAt(call.getCapturedAt());
        evaluation.setPlayer1Probability(snapshot.player1Probability());
        evaluation.setPlayer2Probability(snapshot.player2Probability());
        evaluation.setPredictedWinnerPlayerId(player1 ? call.getPlayer1Id() : call.getPlayer2Id());
        evaluation.setPredictedWinnerName(player1 ? call.getPlayer1Name() : call.getPlayer2Name());
        evaluation.setRawProbability(rawWinnerProbability);
        evaluation.setConfidenceLow(player1 ? snapshot.player1ConfidenceLow() : 1.0 - snapshot.player1ConfidenceHigh());
        evaluation.setConfidenceHigh(player1 ? snapshot.player1ConfidenceHigh() : 1.0 - snapshot.player1ConfidenceLow());
        evaluation.setFairAmericanOdds(toAmerican(winnerProbability));
        evaluation.setTopTrigger(topTrigger(snapshot.featureContributions()));
        evaluation.setFeatureContributions(serialize(snapshot.featureContributions()));
        return evaluationRepository.save(evaluation);
    }

    private void recordCounterfactualPortfolios(PaperTradeSession session,
                                                DecisionOpportunity opportunity,
                                                PaperTradeModelCall call,
                                                RunModelLaneEvaluation lane) {
        MarketPair market = marketPair(call);
        Candidate candidate = bestValueCandidate(call, lane, market);
        RunPortfolioDefinition balanced = portfolio(session, "CHALLENGER_BALANCED", "Challenger Balanced",
                "COUNTERFACTUAL", lane, false,
                "{\"minEdge\":" + balancedMinEdge + ",\"minProbability\":" + balancedMinProbability + ",\"stake\":1.0}");
        boolean balancedAction = candidate != null && candidate.edge() >= balancedMinEdge
                && candidate.probability() >= balancedMinProbability;
        decision(opportunity, balanced, lane, candidate,
                balancedAction ? "TRACKED" : "SKIPPED",
                balancedAction ? "BALANCED_POLICY_PASS" : candidate == null ? "PRICE_MISSING" : "BALANCED_THRESHOLD",
                balancedAction ? 1.0 : 0.0);

        RunPortfolioDefinition discovery = portfolio(session, "DISCOVERY", "Discovery Sample",
                "DISCOVERY", lane, false,
                "{\"minEdge\":" + discoveryMinEdge + ",\"samplePct\":" + discoverySamplePct + ",\"deterministic\":true,\"stake\":1.0}");
        boolean selected = !balancedAction && candidate != null && candidate.edge() >= discoveryMinEdge
                && deterministicBucket(opportunity.getEventKey()) < Math.max(0, Math.min(100, discoverySamplePct));
        decision(opportunity, discovery, lane, candidate,
                selected ? "DISCOVERY" : "SKIPPED",
                selected ? "DETERMINISTIC_NEAR_MISS_SAMPLE" : balancedAction ? "ALREADY_IN_BALANCED" : "DISCOVERY_NOT_SELECTED",
                selected ? 1.0 : 0.0);
    }

    private RunPortfolioDefinition portfolio(PaperTradeSession session,
                                             String key,
                                             String name,
                                             String type,
                                             RunModelLaneEvaluation lane,
                                             boolean primary,
                                             String policyJson) {
        RunModelLaneDefinition laneDefinition = laneRepository.findById(lane.getLaneDefinitionId())
                .orElseThrow(() -> new IllegalStateException("lane definition missing"));
        RunPortfolioDefinition definition = portfolioRepository.findBySessionIdAndPortfolioKey(session.getId(), key)
                .orElseGet(RunPortfolioDefinition::new);
        definition.setSessionId(session.getId());
        definition.setPortfolioKey(key);
        definition.setDisplayName(name);
        definition.setPortfolioType(type);
        definition.setModelLaneKey(laneDefinition.getLaneKey());
        definition.setPolicyVersion(session.getPolicyVersion());
        definition.setPolicyJson(policyJson);
        definition.setEnabled(true);
        definition.setPrimaryPortfolio(primary);
        return portfolioRepository.save(definition);
    }

    private void decision(DecisionOpportunity opportunity,
                          RunPortfolioDefinition portfolio,
                          RunModelLaneEvaluation lane,
                          Candidate candidate,
                          String status,
                          String reason,
                          double stake) {
        RunPortfolioDecision decision = decisionRepository
                .findByOpportunityIdAndPortfolioDefinitionId(opportunity.getId(), portfolio.getId())
                .orElseGet(RunPortfolioDecision::new);
        decision.setOpportunityId(opportunity.getId());
        decision.setPortfolioDefinitionId(portfolio.getId());
        decision.setLaneEvaluationId(lane.getId());
        decision.setDecisionStatus(status);
        decision.setDecisionReason(reason);
        if (candidate != null) {
            decision.setSelectedPlayerId(candidate.playerId());
            decision.setSelectedPlayerName(candidate.playerName());
            decision.setModelProbability(candidate.probability());
            decision.setMarketProbability(candidate.marketProbability());
            decision.setEdgeValue(candidate.edge());
            decision.setAmericanOdds(candidate.americanOdds());
        }
        decision.setVirtualStake(stake);
        decision.setCapturedAt(lane.getCapturedAt());
        decisionRepository.save(decision);
    }

    private static Candidate bestValueCandidate(PaperTradeModelCall call,
                                                RunModelLaneEvaluation lane,
                                                MarketPair market) {
        if (lane.getPlayer1Probability() == null || lane.getPlayer2Probability() == null
                || market.p1Probability() == null || market.p2Probability() == null) return null;
        double edge1 = lane.getPlayer1Probability() - market.p1Probability();
        double edge2 = lane.getPlayer2Probability() - market.p2Probability();
        return edge1 >= edge2
                ? new Candidate(call.getPlayer1Id(), call.getPlayer1Name(), lane.getPlayer1Probability(), market.p1Probability(), edge1, market.p1Odds())
                : new Candidate(call.getPlayer2Id(), call.getPlayer2Name(), lane.getPlayer2Probability(), market.p2Probability(), edge2, market.p2Odds());
    }

    private static MarketPair marketPair(PaperTradeModelCall call) {
        Double selectedProbability = call.getHardRockNoVigProbability();
        if (call.getPredictedWinnerPlayerId() == null) return new MarketPair(null, null, null, null);
        boolean championSelectedP1 = call.getPredictedWinnerPlayerId().equals(call.getPlayer1Id());
        return championSelectedP1
                ? new MarketPair(selectedProbability, invert(selectedProbability), call.getHardRockAmericanOdds(), call.getOpponentHardRockAmericanOdds())
                : new MarketPair(invert(selectedProbability), selectedProbability, call.getOpponentHardRockAmericanOdds(), call.getHardRockAmericanOdds());
    }

    private static Double invert(Double value) { return value == null ? null : 1.0 - value; }
    private static int deterministicBucket(String key) { return Math.floorMod(key == null ? 0 : key.hashCode(), 100); }
    private static int toAmerican(double probability) { double p = Math.max(0.0001, Math.min(0.9999, probability)); return p >= 0.5 ? (int) Math.round(-100.0 * p / (1.0 - p)) : (int) Math.round(100.0 * (1.0 - p) / p); }
    private static String prettyFamily(String value) { return Arrays.stream(value.toLowerCase(Locale.ROOT).split("_" )).map(part -> part.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1)).reduce((a, b) -> a + " " + b).orElse(value); }
    private static String topTrigger(List<MatchupAnalysisDto.FeatureContributionDto> rows) { return rows == null ? null : rows.stream().max(java.util.Comparator.comparingDouble(row -> Math.abs(row.contribution()))).map(MatchupAnalysisDto.FeatureContributionDto::feature).orElse(null); }
    private static String serialize(List<MatchupAnalysisDto.FeatureContributionDto> rows) { if (rows == null) return null; String value = rows.stream().limit(30).map(row -> row.feature() + "=" + Math.round(row.contribution() * 10000.0) / 10000.0).reduce((a, b) -> a + ";" + b).orElse(""); return value.length() <= 2400 ? value : value.substring(0, 2400); }

    private record MarketPair(Double p1Probability, Double p2Probability, Integer p1Odds, Integer p2Odds) { }
    private record Candidate(Long playerId, String playerName, double probability, double marketProbability,
                             double edge, Integer americanOdds) { }
}
