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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    static final String MARKET_ANCHOR_LANE_KEY = "SHADOW_MARKET_ANCHORED_R4";
    static final String MARKET_ANCHOR_VERSION = "market-anchor-residual-r4-20260817";
    private static final String MARKET_ANCHOR_SCHEMA =
            "market-no-vig-logit+champion-residual+adaptive-disagreement-v1";
    static final String MARKET_CALIBRATED_R5_LANE_KEY = "SHADOW_MARKET_CALIBRATED_R5";
    static final String MARKET_CALIBRATED_R5_VERSION = "factor-aware-market-calibrator-r5-20260818";
    private static final String MARKET_CALIBRATED_R5_SCHEMA =
            "market-no-vig-logit+champion-residual+signal-quality+rating-agreement+weak-factor-shrink-v2";
    private static final List<Double> R5_EXPECTED_RETURN_LADDER = List.of(0.00, 0.01, 0.02, 0.03, 0.05);

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
    @Value("${ttl.research.marketAnchor.enabled:true}") private boolean marketAnchorEnabled;
    @Value("${ttl.research.marketAnchor.baseModelWeight:0.21}") private double marketAnchorBaseModelWeight;
    @Value("${ttl.research.marketAnchor.disagreementScale:0.50}") private double marketAnchorDisagreementScale;
    @Value("${ttl.research.marketAnchor.minExpectedValue:0.005}") private double marketAnchorMinExpectedValue;
    @Value("${ttl.research.marketAnchor.minNoVigEdge:0.002}") private double marketAnchorMinNoVigEdge;
    @Value("${ttl.research.marketCalibrator.enabled:true}") private boolean marketCalibratorEnabled;
    @Value("${ttl.research.marketCalibrator.baseModelWeight:0.40}") private double marketCalibratorBaseModelWeight;
    @Value("${ttl.research.marketCalibrator.disagreementScale:0.50}") private double marketCalibratorDisagreementScale;
    @Value("${ttl.research.marketCalibrator.weakFactorMultiplier:0.70}") private double marketCalibratorWeakFactorMultiplier;
    @Value("${ttl.research.marketCalibrator.accuracyAudit.minChampionProbability:0.52}") private double accuracyAuditMinChampionProbability;
    @Value("${ttl.research.marketCalibrator.accuracyAudit.minSignalQuality:0.62}") private double accuracyAuditMinSignalQuality;
    @Value("${ttl.research.marketCalibrator.accuracyAudit.minRatingAgreement:0.50}") private double accuracyAuditMinRatingAgreement;

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
        long reservedLanes = 1L + (marketAnchorEnabled ? 1L : 0L) + (marketCalibratorEnabled ? 1L : 0L);
        long familyLimit = Math.max(0, MAX_TOTAL_LANES - reservedLanes);
        List<String> families = Arrays.stream(shadowFamilies.split(","))
                .map(String::trim).filter(StringUtils::hasText).map(value -> value.toUpperCase(Locale.ROOT))
                .distinct().limit(familyLimit).toList();
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
        if (marketAnchorEnabled) {
            try {
                RunModelLaneEvaluation marketAnchor = evaluateMarketAnchor(
                        session, opportunity, championCall, ordinal);
                if (marketAnchor != null) {
                    recordMarketAnchorPortfolio(session, opportunity, championCall, marketAnchor);
                }
            } catch (RuntimeException failure) {
                log.warn("[research-lane] market anchor unavailable session={} event={} reason={}",
                        session.getId(), championCall.getEventKey(), failure.getMessage());
            }
        }
        if (marketCalibratorEnabled) {
            try {
                RunModelLaneEvaluation marketCalibrator = evaluateMarketCalibratedR5(
                        session, opportunity, championCall, ordinal);
                if (marketCalibrator != null) {
                    recordMarketCalibratedR5Portfolios(session, opportunity, championCall, marketCalibrator);
                }
            } catch (RuntimeException failure) {
                log.warn("[research-lane] R5 market calibrator unavailable session={} event={} reason={}",
                        session.getId(), championCall.getEventKey(), failure.getMessage());
            }
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

    /**
     * Research-only R4 lane. Hard Rock's timestamp-correct no-vig probability
     * is the logit offset. Champion may move that anchor, but its influence
     * decays exponentially as disagreement grows. Prior runs showed that the
     * largest model/market gaps were the least reliable segment.
     */
    private RunModelLaneEvaluation evaluateMarketAnchor(PaperTradeSession session,
                                                        DecisionOpportunity opportunity,
                                                        PaperTradeModelCall call,
                                                        int ordinal) {
        MarketPair market = marketPair(call);
        if (call.getModelProbability() == null
                || call.getPredictedWinnerPlayerId() == null
                || market.p1Probability() == null
                || market.p2Probability() == null) return null;
        boolean championSelectedP1 = call.getPredictedWinnerPlayerId().equals(call.getPlayer1Id());
        double championP1 = championSelectedP1
                ? clampProbability(call.getModelProbability())
                : 1.0 - clampProbability(call.getModelProbability());
        double marketP1 = clampProbability(market.p1Probability());
        double disagreement = Math.abs(championP1 - marketP1);
        double effectiveWeight = adaptiveModelWeight(
                marketAnchorBaseModelWeight, disagreement, marketAnchorDisagreementScale);
        double anchoredP1 = anchoredProbability(marketP1, championP1, effectiveWeight);
        double anchoredP2 = 1.0 - anchoredP1;

        RunModelLaneDefinition lane = laneRepository.findBySessionIdAndLaneKey(
                        session.getId(), MARKET_ANCHOR_LANE_KEY)
                .orElseGet(RunModelLaneDefinition::new);
        if (lane.getId() != null && !MARKET_ANCHOR_VERSION.equals(lane.getModelVersion())) {
            throw new IllegalStateException("market-anchor lane artifact drift");
        }
        lane.setSessionId(session.getId());
        lane.setLaneKey(MARKET_ANCHOR_LANE_KEY);
        lane.setDisplayName("Market-Anchored Residual R4");
        lane.setLaneRole("SHADOW");
        lane.setOrdinalPosition(ordinal);
        lane.setModelFamily("MARKET_ANCHORED_RESIDUAL");
        lane.setModelVersion(MARKET_ANCHOR_VERSION);
        lane.setArtifactChecksum(sha256(MARKET_ANCHOR_VERSION + "|" + MARKET_ANCHOR_SCHEMA
                + "|baseWeight=" + marketAnchorBaseModelWeight
                + "|scale=" + marketAnchorDisagreementScale));
        lane.setFeatureSchemaChecksum(sha256(MARKET_ANCHOR_SCHEMA));
        lane.setCalibrationId("NO_VIG_LOGIT_OFFSET_ADAPTIVE_SHRINK_V1");
        lane.setEnabled(true);
        lane.setPrimaryLane(false);
        lane = laneRepository.save(lane);

        RunModelLaneEvaluation evaluation = evaluationRepository
                .findByOpportunityIdAndLaneDefinitionId(opportunity.getId(), lane.getId())
                .orElseGet(RunModelLaneEvaluation::new);
        boolean player1 = anchoredP1 >= anchoredP2;
        double winnerProbability = player1 ? anchoredP1 : anchoredP2;
        evaluation.setOpportunityId(opportunity.getId());
        evaluation.setLaneDefinitionId(lane.getId());
        evaluation.setSourceModelCallId(call.getId());
        evaluation.setCapturedAt(call.getCapturedAt());
        evaluation.setPlayer1Probability(anchoredP1);
        evaluation.setPlayer2Probability(anchoredP2);
        evaluation.setPredictedWinnerPlayerId(player1 ? call.getPlayer1Id() : call.getPlayer2Id());
        evaluation.setPredictedWinnerName(player1 ? call.getPlayer1Name() : call.getPlayer2Name());
        evaluation.setRawProbability(player1 ? championP1 : 1.0 - championP1);
        evaluation.setFairAmericanOdds(toAmerican(winnerProbability));
        evaluation.setSelectionScore(effectiveWeight);
        evaluation.setSignalQuality(1.0 - Math.min(1.0, disagreement / 0.25));
        evaluation.setTopTrigger("MARKET_LOGIT_ANCHOR");
        evaluation.setFeatureContributions("market_no_vig_anchor=" + round4(marketP1)
                + ";champion_probability=" + round4(championP1)
                + ";absolute_disagreement=" + round4(disagreement)
                + ";effective_model_weight=" + round4(effectiveWeight)
                + ";anchored_probability=" + round4(anchoredP1));
        return evaluationRepository.save(evaluation);
    }

    /**
     * Forward-only R5 challenger. R4 established that the timestamp-correct
     * no-vig market is a materially better probability anchor than the raw
     * Champion in the latest run, while large anti-market residuals lost.
     * R5 therefore keeps the market logit as the prior and lets the Champion
     * move it only in proportion to predictor quality, rating agreement, and
     * the historical stability of the leading factor family.
     */
    private RunModelLaneEvaluation evaluateMarketCalibratedR5(PaperTradeSession session,
                                                               DecisionOpportunity opportunity,
                                                               PaperTradeModelCall call,
                                                               int ordinal) {
        MarketPair market = marketPair(call);
        if (call.getModelProbability() == null
                || call.getPredictedWinnerPlayerId() == null
                || market.p1Probability() == null
                || market.p2Probability() == null) return null;
        boolean championSelectedP1 = call.getPredictedWinnerPlayerId().equals(call.getPlayer1Id());
        double championP1 = championSelectedP1
                ? clampProbability(call.getModelProbability())
                : 1.0 - clampProbability(call.getModelProbability());
        double marketP1 = clampProbability(market.p1Probability());
        double disagreement = Math.abs(championP1 - marketP1);
        double evidenceMultiplier = factorAwareEvidenceMultiplier(
                call.getSignalQuality(),
                call.getRatingAgreement(),
                call.getTopTrigger(),
                marketCalibratorWeakFactorMultiplier);
        double effectiveWeight = adaptiveModelWeight(
                marketCalibratorBaseModelWeight,
                disagreement,
                marketCalibratorDisagreementScale) * evidenceMultiplier;
        double anchoredP1 = anchoredProbability(marketP1, championP1, effectiveWeight);
        double anchoredP2 = 1.0 - anchoredP1;

        RunModelLaneDefinition lane = laneRepository.findBySessionIdAndLaneKey(
                        session.getId(), MARKET_CALIBRATED_R5_LANE_KEY)
                .orElseGet(RunModelLaneDefinition::new);
        if (lane.getId() != null && !MARKET_CALIBRATED_R5_VERSION.equals(lane.getModelVersion())) {
            throw new IllegalStateException("R5 market-calibrator lane artifact drift");
        }
        lane.setSessionId(session.getId());
        lane.setLaneKey(MARKET_CALIBRATED_R5_LANE_KEY);
        lane.setDisplayName("Factor-Aware Market Calibrator R5");
        lane.setLaneRole("SHADOW");
        lane.setOrdinalPosition(ordinal);
        lane.setModelFamily("FACTOR_AWARE_MARKET_CALIBRATOR");
        lane.setModelVersion(MARKET_CALIBRATED_R5_VERSION);
        lane.setArtifactChecksum(sha256(MARKET_CALIBRATED_R5_VERSION + "|" + MARKET_CALIBRATED_R5_SCHEMA
                + "|baseWeight=" + marketCalibratorBaseModelWeight
                + "|scale=" + marketCalibratorDisagreementScale
                + "|weakFactorMultiplier=" + marketCalibratorWeakFactorMultiplier));
        lane.setFeatureSchemaChecksum(sha256(MARKET_CALIBRATED_R5_SCHEMA));
        lane.setCalibrationId("NO_VIG_LOGIT_FACTOR_AWARE_RESIDUAL_V2");
        lane.setEnabled(true);
        lane.setPrimaryLane(false);
        lane = laneRepository.save(lane);

        RunModelLaneEvaluation evaluation = evaluationRepository
                .findByOpportunityIdAndLaneDefinitionId(opportunity.getId(), lane.getId())
                .orElseGet(RunModelLaneEvaluation::new);
        boolean player1 = anchoredP1 >= anchoredP2;
        double winnerProbability = player1 ? anchoredP1 : anchoredP2;
        evaluation.setOpportunityId(opportunity.getId());
        evaluation.setLaneDefinitionId(lane.getId());
        evaluation.setSourceModelCallId(call.getId());
        evaluation.setCapturedAt(call.getCapturedAt());
        evaluation.setPlayer1Probability(anchoredP1);
        evaluation.setPlayer2Probability(anchoredP2);
        evaluation.setPredictedWinnerPlayerId(player1 ? call.getPlayer1Id() : call.getPlayer2Id());
        evaluation.setPredictedWinnerName(player1 ? call.getPlayer1Name() : call.getPlayer2Name());
        evaluation.setRawProbability(player1 ? championP1 : 1.0 - championP1);
        evaluation.setFairAmericanOdds(toAmerican(winnerProbability));
        evaluation.setSelectionScore(effectiveWeight);
        evaluation.setSignalQuality(evidenceMultiplier);
        evaluation.setTopTrigger("FACTOR_AWARE_MARKET_CALIBRATION");
        evaluation.setFeatureContributions("market_no_vig_anchor=" + round4(marketP1)
                + ";champion_probability=" + round4(championP1)
                + ";absolute_disagreement=" + round4(disagreement)
                + ";evidence_multiplier=" + round4(evidenceMultiplier)
                + ";effective_model_weight=" + round4(effectiveWeight)
                + ";anchored_probability=" + round4(anchoredP1)
                + ";champion_top_trigger=" + safeText(call.getTopTrigger()));
        return evaluationRepository.save(evaluation);
    }

    private void recordMarketCalibratedR5Portfolios(PaperTradeSession session,
                                                     DecisionOpportunity opportunity,
                                                     PaperTradeModelCall call,
                                                     RunModelLaneEvaluation lane) {
        MarketPair market = marketPair(call);
        Candidate valueCandidate = bestEconomicValueCandidate(call, lane, market);
        double expectedValue = valueCandidate == null ? Double.NEGATIVE_INFINITY
                : expectedValue(valueCandidate.probability(), valueCandidate.americanOdds());
        for (double threshold : R5_EXPECTED_RETURN_LADDER) {
            String basisPoints = String.valueOf((int) Math.round(threshold * 100));
            RunPortfolioDefinition portfolio = portfolio(session,
                    "R5_VALUE_EV_" + basisPoints + "PP",
                    "R5 value · EV ≥ " + basisPoints + "%",
                    "COUNTERFACTUAL",
                    lane,
                    false,
                    "{\"model\":\"FACTOR_AWARE_MARKET_CALIBRATOR_R5\","
                            + "\"edgeBasis\":\"ACTUAL_HARD_ROCK_PRICE_AFTER_VIG\","
                            + "\"minimumExpectedReturn\":" + threshold
                            + ",\"stake\":1.0,\"mode\":\"SHADOW_ONLY\"}");
            boolean tracked = valueCandidate != null && expectedValue >= threshold;
            decision(opportunity, portfolio, lane, valueCandidate,
                    tracked ? "TRACKED" : "SKIPPED",
                    tracked ? "EXPECTED_RETURN_LADDER_PASS"
                            : valueCandidate == null ? "PRICE_OR_MODEL_MISSING" : "EXPECTED_RETURN_BELOW_LADDER",
                    tracked ? 1.0 : 0.0);
        }

        Candidate accuracyCandidate = predictedWinnerCandidate(call, lane, market);
        boolean modelAndMarketAgree = accuracyCandidate != null
                && accuracyCandidate.marketProbability() >= 0.50;
        boolean accuracyTracked = modelAndMarketAgree
                && call.getModelProbability() != null
                && call.getModelProbability() >= accuracyAuditMinChampionProbability
                && valueOr(call.getSignalQuality(), 0.0) >= accuracyAuditMinSignalQuality
                && valueOr(call.getRatingAgreement(), 0.0) >= accuracyAuditMinRatingAgreement
                && accuracyCandidate.americanOdds() != null
                && accuracyCandidate.americanOdds() <= 0;
        RunPortfolioDefinition accuracyPortfolio = portfolio(session,
                "R5_MARKET_AGREEMENT_ACCURACY",
                "R5 market-agreement accuracy audit",
                "RESEARCH",
                lane,
                false,
                "{\"purpose\":\"FORECAST_ACCURACY_AND_FLAT_DOLLAR_AUDIT\","
                        + "\"modelMarketAgreement\":true,"
                        + "\"minChampionProbability\":" + accuracyAuditMinChampionProbability + ","
                        + "\"minSignalQuality\":" + accuracyAuditMinSignalQuality + ","
                        + "\"minRatingAgreement\":" + accuracyAuditMinRatingAgreement + ","
                        + "\"positiveOddsAllowed\":false,\"stake\":1.0,\"mode\":\"SHADOW_ONLY\"}");
        decision(opportunity, accuracyPortfolio, lane, accuracyCandidate,
                accuracyTracked ? "TRACKED" : "SKIPPED",
                accuracyTracked ? "MARKET_AGREEMENT_ACCURACY_PASS" : "ACCURACY_AUDIT_GATE",
                accuracyTracked ? 1.0 : 0.0);
    }

    private void recordMarketAnchorPortfolio(PaperTradeSession session,
                                             DecisionOpportunity opportunity,
                                             PaperTradeModelCall call,
                                             RunModelLaneEvaluation lane) {
        Candidate candidate = bestValueCandidate(call, lane, marketPair(call));
        double expectedValue = candidate == null ? Double.NEGATIVE_INFINITY
                : expectedValue(candidate.probability(), candidate.americanOdds());
        boolean action = candidate != null
                && candidate.edge() >= marketAnchorMinNoVigEdge
                && expectedValue >= marketAnchorMinExpectedValue;
        RunPortfolioDefinition portfolio = portfolio(session, "MARKET_ANCHORED_R4",
                "Market-Anchored R4", "COUNTERFACTUAL", lane, false,
                "{\"anchor\":\"HARD_ROCK_NO_VIG\",\"transform\":\"LOGIT_RESIDUAL\","
                        + "\"baseModelWeight\":" + marketAnchorBaseModelWeight
                        + ",\"disagreementScale\":" + marketAnchorDisagreementScale
                        + ",\"minExpectedValue\":" + marketAnchorMinExpectedValue
                        + ",\"minNoVigEdge\":" + marketAnchorMinNoVigEdge
                        + ",\"stake\":1.0,\"mode\":\"SHADOW_ONLY\"}");
        decision(opportunity, portfolio, lane, candidate,
                action ? "TRACKED" : "SKIPPED",
                action ? "MARKET_ANCHORED_EV_PASS"
                        : candidate == null ? "PRICE_OR_MODEL_MISSING"
                        : candidate.edge() < marketAnchorMinNoVigEdge
                        ? "RESIDUAL_EDGE_TOO_SMALL" : "BOOK_VIG_NOT_COVERED",
                action ? 1.0 : 0.0);
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

    private static Candidate bestEconomicValueCandidate(PaperTradeModelCall call,
                                                         RunModelLaneEvaluation lane,
                                                         MarketPair market) {
        if (lane.getPlayer1Probability() == null || lane.getPlayer2Probability() == null
                || market.p1Probability() == null || market.p2Probability() == null
                || market.p1Odds() == null || market.p2Odds() == null) return null;
        double ev1 = expectedValue(lane.getPlayer1Probability(), market.p1Odds());
        double ev2 = expectedValue(lane.getPlayer2Probability(), market.p2Odds());
        return ev1 >= ev2
                ? new Candidate(call.getPlayer1Id(), call.getPlayer1Name(), lane.getPlayer1Probability(), market.p1Probability(), lane.getPlayer1Probability() - market.p1Probability(), market.p1Odds())
                : new Candidate(call.getPlayer2Id(), call.getPlayer2Name(), lane.getPlayer2Probability(), market.p2Probability(), lane.getPlayer2Probability() - market.p2Probability(), market.p2Odds());
    }

    private static Candidate predictedWinnerCandidate(PaperTradeModelCall call,
                                                       RunModelLaneEvaluation lane,
                                                       MarketPair market) {
        if (lane.getPredictedWinnerPlayerId() == null
                || lane.getPlayer1Probability() == null || lane.getPlayer2Probability() == null
                || market.p1Probability() == null || market.p2Probability() == null) return null;
        boolean player1 = lane.getPredictedWinnerPlayerId().equals(call.getPlayer1Id());
        double probability = player1 ? lane.getPlayer1Probability() : lane.getPlayer2Probability();
        double marketProbability = player1 ? market.p1Probability() : market.p2Probability();
        return new Candidate(
                player1 ? call.getPlayer1Id() : call.getPlayer2Id(),
                player1 ? call.getPlayer1Name() : call.getPlayer2Name(),
                probability,
                marketProbability,
                probability - marketProbability,
                player1 ? market.p1Odds() : market.p2Odds());
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
    static double adaptiveModelWeight(double baseWeight, double disagreement, double scale) {
        double safeBase = Math.max(0.0, Math.min(1.0, baseWeight));
        double safeScale = Math.max(0.01, scale);
        return safeBase * Math.exp(-Math.max(0.0, disagreement) / safeScale);
    }
    static double anchoredProbability(double marketProbability, double modelProbability, double modelWeight) {
        double market = clampProbability(marketProbability);
        double model = clampProbability(modelProbability);
        double weight = Math.max(0.0, Math.min(1.0, modelWeight));
        double anchoredLogit = logit(market) + weight * (logit(model) - logit(market));
        return clampProbability(1.0 / (1.0 + Math.exp(-anchoredLogit)));
    }

    static double factorAwareEvidenceMultiplier(Double signalQuality,
                                                Double ratingAgreement,
                                                String topTrigger,
                                                double weakFactorMultiplier) {
        double signal = clamp01(valueOr(signalQuality, 0.65));
        double agreement = clamp01(valueOr(ratingAgreement, 0.50));
        double normalizedSignal = clamp01((signal - 0.50) / 0.40);
        double evidence = clamp01(0.50 + (0.30 * normalizedSignal) + (0.20 * agreement));
        if (isWeakOrUnstableFactor(topTrigger)) {
            evidence *= Math.max(0.20, Math.min(1.0, weakFactorMultiplier));
        }
        return clamp01(evidence);
    }

    private static boolean isWeakOrUnstableFactor(String trigger) {
        if (!StringUtils.hasText(trigger)) return false;
        String normalized = trigger.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("SCHEDULE")
                || normalized.contains("HEAD-TO-HEAD")
                || normalized.contains("HEAD_TO_HEAD")
                || normalized.contains("OPPONENT-ADJUSTED")
                || normalized.contains("OPPONENT_ADJUSTED")
                || normalized.contains("VOLATILITY")
                || normalized.contains("GLICKO RD");
    }

    private static double valueOr(Double value, double fallback) {
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String safeText(String value) {
        return StringUtils.hasText(value) ? value.trim().replace(';', ',') : "UNKNOWN";
    }
    private static double expectedValue(double probability, Integer americanOdds) {
        if (americanOdds == null || americanOdds == 0) return Double.NEGATIVE_INFINITY;
        double decimalOdds = americanOdds > 0
                ? 1.0 + americanOdds / 100.0
                : 1.0 + 100.0 / Math.abs(americanOdds);
        return probability * decimalOdds - 1.0;
    }
    private static double logit(double probability) { return Math.log(probability / (1.0 - probability)); }
    private static double clampProbability(double value) { return Math.max(0.001, Math.min(0.999, value)); }
    private static double round4(double value) { return Math.round(value * 10_000.0) / 10_000.0; }
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private static int deterministicBucket(String key) { return Math.floorMod(key == null ? 0 : key.hashCode(), 100); }
    private static int toAmerican(double probability) { double p = Math.max(0.0001, Math.min(0.9999, probability)); return p >= 0.5 ? (int) Math.round(-100.0 * p / (1.0 - p)) : (int) Math.round(100.0 * (1.0 - p) / p); }
    private static String prettyFamily(String value) { return Arrays.stream(value.toLowerCase(Locale.ROOT).split("_" )).map(part -> part.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1)).reduce((a, b) -> a + " " + b).orElse(value); }
    private static String topTrigger(List<MatchupAnalysisDto.FeatureContributionDto> rows) { return rows == null ? null : rows.stream().max(java.util.Comparator.comparingDouble(row -> Math.abs(row.contribution()))).map(MatchupAnalysisDto.FeatureContributionDto::feature).orElse(null); }
    private static String serialize(List<MatchupAnalysisDto.FeatureContributionDto> rows) { if (rows == null) return null; String value = rows.stream().limit(30).map(row -> row.feature() + "=" + Math.round(row.contribution() * 10000.0) / 10000.0).reduce((a, b) -> a + ";" + b).orElse(""); return value.length() <= 2400 ? value : value.substring(0, 2400); }

    private record MarketPair(Double p1Probability, Double p2Probability, Integer p1Odds, Integer p2Odds) { }
    private record Candidate(Long playerId, String playerName, double probability, double marketProbability,
                             double edge, Integer americanOdds) { }
}
