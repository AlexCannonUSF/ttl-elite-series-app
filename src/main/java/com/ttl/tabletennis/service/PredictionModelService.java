package com.ttl.tabletennis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.domain.PredictionModelRegistryEntry;
import com.ttl.tabletennis.dto.AdaptiveRegimeProfileDto;
import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.dto.ModelRegistryEntryDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.PredictionModelRegistryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

@Service
@Transactional(readOnly = true)
public class PredictionModelService {

    public static final String FAMILY_BASELINE = "BASELINE";
    public static final String FAMILY_LOGISTIC = "LOGISTIC";
    public static final String FAMILY_GBT_LIKE = "GBT_LIKE";
    public static final String FAMILY_RF_LIKE = "RF_LIKE";
    public static final String FAMILY_ENSEMBLE = "ENSEMBLE";
    private static final List<String> TRAINED_FAMILIES = List.of(
            FAMILY_LOGISTIC,
            FAMILY_GBT_LIKE,
            FAMILY_RF_LIKE,
            FAMILY_ENSEMBLE
    );

    private static final double EPS = 1e-9;
    private static final double MIN_FEATURE_STD = 1.0e-3;
    private static final double MAX_STANDARDIZED_FEATURE = 6.0;
    private static final DateTimeFormatter VERSION_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final LocalDate MIN_REASONABLE_MATCH_DATE = LocalDate.of(1990, 1, 1);

    private static final String[] BASE_FEATURE_NAMES = new String[]{
            "Head-to-Head (Decayed)",
            "Recent Form Delta",
            "Opponent-Adjusted Form Delta",
            "Schedule Strength Delta",
            "Elo Probability Delta",
            "Glicko Probability Delta",
            "Glicko Rating Delta",
            "Glicko RD Advantage",
            "Volatility Advantage",
            "P1 Recent Form",
            "P2 Recent Form",
            "Form × H2H Interaction",
            "TrueSkill2 Probability Delta",
            "Weng-Lin Probability Delta",
            "Rater Ensemble Delta",
            "Rater Consensus Signal"
    };

    private final MatchRepository matchRepository;
    private final FeatureService featureService;
    private final PaperTradeLearningSampleRepository learningSampleRepository;
    private final PredictionModelRegistryRepository registryRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, TrainedModel> activeModels = new ConcurrentHashMap<>();
    private final AtomicReference<ModelTrainingReportDto> lastTrainingReport = new AtomicReference<>();
    private final AtomicReference<LiveLearningCalibrationCache> liveLearningCache = new AtomicReference<>();
    private final AtomicReference<AdaptiveRegimeProfileCache> adaptiveRegimeCache = new AtomicReference<>();
    private final Object trainLock = new Object();

    @Value("${ttl.prediction.minTrainingMatches:120}")
    private int minTrainingMatches;

    @Value("${ttl.prediction.absoluteMinSamples:12}")
    private int absoluteMinSamples;

    @Value("${ttl.prediction.cvFolds:5}")
    private int cvFolds;

    @Value("${ttl.prediction.lambdaCandidates:0.0,0.0005,0.001,0.003,0.01}")
    private String lambdaCandidatesRaw;

    @Value("${ttl.prediction.epochs:220}")
    private int trainEpochs;

    @Value("${ttl.prediction.learningRate:0.08}")
    private double learningRate;

    @Value("${ttl.prediction.rfTrees:60}")
    private int rfTrees;

    @Value("${ttl.prediction.holdoutRatio:0.2}")
    private double holdoutRatio;

    @Value("${ttl.prediction.minLiftForAdvanced:0.002}")
    private double minLiftForAdvanced;

    @Value("${ttl.prediction.probabilityFloor:0.12}")
    private double probabilityFloor;

    @Value("${ttl.prediction.probabilityCeiling:0.88}")
    private double probabilityCeiling;

    @Value("${ttl.prediction.uncertaintyShrink:0.55}")
    private double uncertaintyShrink;

    @Value("${ttl.prediction.probabilityTemperature:1.25}")
    private double probabilityTemperature;

    @Value("${ttl.prediction.consensusShrink:0.35}")
    private double consensusShrink;

    @Value("${ttl.prediction.disagreementCiBoost:0.9}")
    private double disagreementCiBoost;

    @Value("${ttl.prediction.sampleSupportTarget:10.0}")
    private double sampleSupportTarget;

    @Value("${ttl.prediction.sampleSupportExponent:1.15}")
    private double sampleSupportExponent;

    @Value("${ttl.prediction.h2hSupportTargetMultiplier:1.4}")
    private double h2hSupportTargetMultiplier;

    @Value("${ttl.prediction.h2hSupportExponent:1.35}")
    private double h2hSupportExponent;

    @Value("${ttl.prediction.sampleUncertaintyWeight:0.45}")
    private double sampleUncertaintyWeight;

    @Value("${ttl.prediction.sampleCiBoost:0.22}")
    private double sampleCiBoost;

    @Value("${ttl.prediction.liveLearningEnabled:true}")
    private boolean liveLearningEnabled;

    @Value("${ttl.prediction.liveLearningApplyEnabled:false}")
    private boolean liveLearningApplyEnabled;

    @Value("${ttl.prediction.liveLearningWindow:200}")
    private int liveLearningWindow;

    @Value("${ttl.prediction.liveLearningMinSamples:25}")
    private int liveLearningMinSamples;

    @Value("${ttl.prediction.liveLearningMinEffectiveSamples:50}")
    private int liveLearningMinEffectiveSamples;

    @Value("${ttl.prediction.liveLearningHalfLifeDays:21}")
    private double liveLearningHalfLifeDays;

    @Value("${ttl.prediction.liveLearningMaxScaleShift:0.10}")
    private double liveLearningMaxScaleShift;

    @Value("${ttl.prediction.liveLearningCacheTtlSeconds:90}")
    private int liveLearningCacheTtlSeconds;

    @Value("${ttl.prediction.regimeLearningWindow:240}")
    private int regimeLearningWindow;

    @Value("${ttl.prediction.regimeLearningMinSamples:18}")
    private int regimeLearningMinSamples;

    @Value("${ttl.prediction.regimeLearningMaxScaleShift:0.08}")
    private double regimeLearningMaxScaleShift;

    @Value("${ttl.prediction.regimeLearningApplyEnabled:false}")
    private boolean regimeLearningApplyEnabled;

    public PredictionModelService(MatchRepository matchRepository,
                                  FeatureService featureService,
                                  PaperTradeLearningSampleRepository learningSampleRepository,
                                  PredictionModelRegistryRepository registryRepository,
                                  ObjectMapper objectMapper) {
        this.matchRepository = matchRepository;
        this.featureService = featureService;
        this.learningSampleRepository = learningSampleRepository;
        this.registryRepository = registryRepository;
        this.objectMapper = objectMapper;
    }

    public PredictionSnapshot predict(Long player1Id,
                                      Long player2Id,
                                      LocalDate asOfDate,
                                      String requestedFamily) {
        ensureModelsReady();

        LocalDate asOf = asOfDate == null ? LocalDate.now() : asOfDate;
        MatchupFeatureVectorDto featureVector = featureService.buildMatchupFeatureVector(player1Id, player2Id, asOf);
        double[] baseFeatures = toBaseFeatures(featureVector);

        double baselineProbability = baselineProbability(baseFeatures);
        TrainedModel logistic = activeModels.get(FAMILY_LOGISTIC);
        TrainedModel gbtLike = activeModels.get(FAMILY_GBT_LIKE);
        TrainedModel rfLike = activeModels.get(FAMILY_RF_LIKE);
        TrainedModel ensemble = activeModels.get(FAMILY_ENSEMBLE);

        double logisticProbability = logistic == null ? baselineProbability : logistic.predict(baseFeatures);
        double glickoProbability = clamp01(featureVector.glickoProbabilityPlayer1());
        double gbtProbability = gbtLike == null ? logisticProbability : gbtLike.predict(baseFeatures);
        double rfProbability = rfLike == null ? logisticProbability : rfLike.predict(baseFeatures);
        double ensembleProbability = ensemble == null ? logisticProbability : ensemble.predict(baseFeatures);

        ModelSelection selection = selectRequestedModel(requestedFamily, logistic, gbtLike, rfLike, ensemble);
        if (selection.baseline()) {
            return snapshot(
                    FAMILY_BASELINE,
                    "baseline-runtime",
                    "NONE",
                    baselineProbability,
                    baselineContributions(baseFeatures),
                    featureVector,
                    baselineProbability,
                    logisticProbability,
                    glickoProbability,
                    gbtProbability,
                    rfProbability,
                    ensembleProbability
            );
        }

        TrainedModel selected = selection.model();
        if (selected == null) {
            selected = logistic;
        }
        if (selected == null) {
            return snapshot(
                    FAMILY_BASELINE,
                    "baseline-runtime",
                    "NONE",
                    baselineProbability,
                    baselineContributions(baseFeatures),
                    featureVector,
                    baselineProbability,
                    baselineProbability,
                    glickoProbability,
                    baselineProbability,
                    baselineProbability,
                    baselineProbability
            );
        }

        return snapshot(
                selection.family(),
                selected.version,
                selected.calibrationMethod,
                selected.predict(baseFeatures),
                selected.contributions(baseFeatures),
                featureVector,
                baselineProbability,
                logisticProbability,
                glickoProbability,
                gbtProbability,
                rfProbability,
                ensembleProbability
        );
    }

    public List<ModelRegistryEntryDto> recentRegistry(String family, int limit) {
        int take = Math.max(1, Math.min(limit, 200));
        String normalized = StringUtils.hasText(family) ? family.trim().toUpperCase(Locale.ROOT) : null;
        return registryRepository.findRecentByFamily(normalized, PageRequest.of(0, take))
                .stream()
                .map(this::toRegistryDto)
                .toList();
    }

    public ModelTrainingReportDto latestTrainingReport() {
        return lastTrainingReport.get();
    }

    public List<AdaptiveRegimeProfileDto> currentAdaptiveRegimeProfiles() {
        return loadAdaptiveRegimeProfiles().stream()
                .map(profile -> new AdaptiveRegimeProfileDto(
                        profile.label(),
                        profile.sampleSize(),
                        round4(profile.reliability()),
                        round2(profile.calibrationError() * 100.0),
                        round2(profile.roiSignal() * 100.0),
                        round4(profile.confidenceScale()),
                        round4(profile.ciBoost()),
                        profile.live(),
                        profile.phase(),
                        profile.sideType()
                ))
                .toList();
    }

    public AdaptiveRegimeTuning currentAdaptiveRegimeTuning(boolean live, String phase, double impliedProbability) {
        if (!liveLearningEnabled || !regimeLearningApplyEnabled) {
            return AdaptiveRegimeTuning.neutral("All Settled");
        }
        List<AdaptiveRegimeProfile> profiles = loadAdaptiveRegimeProfiles();
        if (profiles.isEmpty()) {
            return AdaptiveRegimeTuning.neutral("All Settled");
        }

        String normalizedPhase = normalizePhase(phase);
        String sideType = impliedProbability >= 0.55 ? "FAVORITE" : impliedProbability <= 0.45 ? "UNDERDOG" : "BALANCED";

        List<AdaptiveRegimeProfile> candidates = new ArrayList<>();
        AdaptiveRegimeProfile exactPhase = findProfile(profiles, normalizedPhase);
        if (exactPhase != null && live) {
            candidates.add(exactPhase);
        }
        AdaptiveRegimeProfile baseRegime = findProfile(profiles, live ? "Live" : "Prematch");
        if (baseRegime != null) {
            candidates.add(baseRegime);
        }
        if (!"BALANCED".equals(sideType)) {
            AdaptiveRegimeProfile sideProfile = findProfile(profiles, sideType.equals("FAVORITE") ? "Favorite Side" : "Underdog Side");
            if (sideProfile != null) {
                candidates.add(sideProfile);
            }
        }
        AdaptiveRegimeProfile all = findProfile(profiles, "All Settled");
        if (all != null) {
            candidates.add(all);
        }
        if (candidates.isEmpty()) {
            return AdaptiveRegimeTuning.neutral("All Settled");
        }

        double weightSum = 0.0;
        double confidenceScale = 0.0;
        double ciBoost = 0.0;
        double reliability = 0.0;
        double calibrationError = 0.0;
        double roiSignal = 0.0;
        List<String> labels = new ArrayList<>();
        for (AdaptiveRegimeProfile candidate : candidates) {
            double priority = candidate.label().equals(normalizedPhase) ? 1.35
                    : candidate.label().equals(live ? "Live" : "Prematch") ? 1.15
                    : candidate.label().equals("All Settled") ? 0.8
                    : 1.0;
            double weight = Math.max(0.05, candidate.reliability() * priority);
            weightSum += weight;
            confidenceScale += candidate.confidenceScale() * weight;
            ciBoost += candidate.ciBoost() * weight;
            reliability += candidate.reliability() * weight;
            calibrationError += candidate.calibrationError() * weight;
            roiSignal += candidate.roiSignal() * weight;
            labels.add(candidate.label());
        }
        if (weightSum <= EPS) {
            return AdaptiveRegimeTuning.neutral("All Settled");
        }

        return new AdaptiveRegimeTuning(
                String.join(" + ", labels.stream().distinct().toList()),
                round4(reliability / weightSum),
                round4(confidenceScale / weightSum),
                round4(ciBoost / weightSum),
                round4(calibrationError / weightSum),
                round4(roiSignal / weightSum)
        );
    }

    @Transactional
    public ModelTrainingReportDto trainModels(LocalDate fromDate, LocalDate toDate) {
        synchronized (trainLock) {
            List<TrainingSample> samples = buildSamples(fromDate, toDate);
            int requiredSamples = Math.max(clamp(absoluteMinSamples, 10, 80), Math.max(10, minTrainingMatches / 8));
            if (samples.size() < requiredSamples) {
                throw new IllegalStateException(
                        "Not enough training samples to train prediction models: "
                                + samples.size()
                                + " (requires at least "
                                + requiredSamples
                                + "). Run scraper/backfill to add completed matches."
                );
            }

            int minimumTrainRows = samples.size() < 40 ? 8 : 12;
            int holdout = Math.max(6, (int) Math.round(samples.size() * clamp(holdoutRatio, 0.1, 0.4)));
            holdout = Math.min(holdout, samples.size() - minimumTrainRows);
            holdout = Math.max(1, holdout);
            List<TrainingSample> train = samples.subList(0, samples.size() - holdout);
            List<TrainingSample> validation = samples.subList(samples.size() - holdout, samples.size());
            List<Double> lambdas = parseLambdas();

            double bestLambda = selectBestLambda(train, lambdas, FeatureSet.base());
            LogisticModel logistic = trainLogisticModel(train, bestLambda, FeatureSet.base(), trainEpochs, learningRate);
            CandidateMetrics logisticMetrics = evaluateCandidate(logistic, validation);
            maybeCalibrate(logistic, validation, logisticMetrics);

            double gbtLambda = selectBestLambda(train, lambdas, FeatureSet.gbtLike());
            LogisticModel gbtLike = trainLogisticModel(train, gbtLambda, FeatureSet.gbtLike(), trainEpochs, learningRate);
            CandidateMetrics gbtMetrics = evaluateCandidate(gbtLike, validation);
            maybeCalibrate(gbtLike, validation, gbtMetrics);

            RandomForestLikeModel rfLike = trainRandomForest(train, FeatureSet.base(), Math.max(10, rfTrees));
            CandidateMetrics rfMetrics = evaluateCandidate(rfLike, validation);

            CandidateMetrics bestAdvanced = gbtMetrics.brierScore <= rfMetrics.brierScore ? gbtMetrics : rfMetrics;
            PredictModel bestAdvancedModel = bestAdvanced.brierScore <= rfMetrics.brierScore ? gbtLike : rfLike;

            EnsembleModel ensemble = buildEnsemble(logistic, logisticMetrics, bestAdvancedModel, bestAdvanced);
            CandidateMetrics ensembleMetrics = evaluateCandidate(ensemble, validation);

            List<CandidateMetrics> ranked = new ArrayList<>(List.of(logisticMetrics, gbtMetrics, rfMetrics, ensembleMetrics));
            ranked.sort(Comparator.comparingDouble(c -> c.brierScore));
            CandidateMetrics champion = ranked.get(0);

            LocalDate trainFrom = train.get(0).matchDate;
            LocalDate trainTo = train.get(train.size() - 1).matchDate;
            LocalDate valFrom = validation.get(0).matchDate;
            LocalDate valTo = validation.get(validation.size() - 1).matchDate;
            String jobId = "train-" + VERSION_TS.format(LocalDateTime.now());
            String championFamily = champion.family;

            Map<String, TrainedModel> trained = new HashMap<>();
            trained.put(FAMILY_LOGISTIC, persistCandidate(
                    jobId,
                    FAMILY_LOGISTIC,
                    logistic,
                    trainFrom,
                    trainTo,
                    valFrom,
                    valTo,
                    logisticMetrics,
                    bestLambda,
                    cvFolds,
                    FAMILY_LOGISTIC.equals(championFamily),
                    "L2 logistic regression over historical feature vectors",
                    null
            ));
            trained.put(FAMILY_GBT_LIKE, persistCandidate(
                    jobId,
                    FAMILY_GBT_LIKE,
                    gbtLike,
                    trainFrom,
                    trainTo,
                    valFrom,
                    valTo,
                    gbtMetrics,
                    gbtLambda,
                    cvFolds,
                    FAMILY_GBT_LIKE.equals(championFamily),
                    "Non-linear feature expansion (GBDT-like surrogate)",
                    null
            ));
            trained.put(FAMILY_RF_LIKE, persistCandidate(
                    jobId,
                    FAMILY_RF_LIKE,
                    rfLike,
                    trainFrom,
                    trainTo,
                    valFrom,
                    valTo,
                    rfMetrics,
                    null,
                    null,
                    FAMILY_RF_LIKE.equals(championFamily),
                    "Random forest style stump ensemble",
                    null
            ));

            Map<String, Object> ensemblePayload = new LinkedHashMap<>();
            ensemblePayload.put("logisticVersion", trained.get(FAMILY_LOGISTIC).version);
            String advancedFamily = bestAdvancedModel.family();
            TrainedModel advancedTrained = trained.get(advancedFamily);
            if (advancedTrained != null) {
                ensemblePayload.put("advancedVersion", advancedTrained.version);
                ensemblePayload.put("advancedFamily", advancedFamily);
            }
            trained.put(FAMILY_ENSEMBLE, persistCandidate(
                    jobId,
                    FAMILY_ENSEMBLE,
                    ensemble,
                    trainFrom,
                    trainTo,
                    valFrom,
                    valTo,
                    ensembleMetrics,
                    null,
                    null,
                    FAMILY_ENSEMBLE.equals(championFamily),
                    "Weighted ensemble (logistic + best non-linear model)",
                    ensemblePayload
            ));

            activeModels.clear();
            activeModels.putAll(trained);
            if (!FAMILY_ENSEMBLE.equals(championFamily) && trained.get(championFamily) != null) {
                // "ENSEMBLE" is the product's default selector. Route it to
                // the validated champion when the ensemble did not win the
                // holdout instead of silently serving an inactive candidate.
                activeModels.put(FAMILY_ENSEMBLE, trained.get(championFamily));
            }

            List<ModelTrainingReportDto.CandidateMetricDto> candidates = List.of(
                    toCandidateDto(FAMILY_LOGISTIC, trained.get(FAMILY_LOGISTIC), logisticMetrics, FAMILY_LOGISTIC.equals(championFamily)),
                    toCandidateDto(FAMILY_GBT_LIKE, trained.get(FAMILY_GBT_LIKE), gbtMetrics, FAMILY_GBT_LIKE.equals(championFamily)),
                    toCandidateDto(FAMILY_RF_LIKE, trained.get(FAMILY_RF_LIKE), rfMetrics, FAMILY_RF_LIKE.equals(championFamily)),
                    toCandidateDto(FAMILY_ENSEMBLE, trained.get(FAMILY_ENSEMBLE), ensembleMetrics, FAMILY_ENSEMBLE.equals(championFamily))
            );

            List<ModelTrainingReportDto.CalibrationBinDto> calibrationCurve = buildCalibrationCurve(champion.model, validation, 10);
            List<ModelTrainingReportDto.RegimeMetricDto> validationRegimes = buildValidationRegimes(champion.model, validation);
            List<ModelTrainingReportDto.RegimeMetricDto> operationalRegimes = buildOperationalRegimes();
            ModelTrainingReportDto report = new ModelTrainingReportDto(
                    jobId,
                    trainFrom,
                    trainTo,
                    samples.size(),
                    BASE_FEATURE_NAMES.length,
                    championFamily,
                    trained.get(championFamily).version,
                    LocalDateTime.now(),
                    candidates,
                    calibrationCurve,
                    validationRegimes,
                    operationalRegimes
            );
            lastTrainingReport.set(report);
            return report;
        }
    }

    private PredictionSnapshot snapshot(String family,
                                        String version,
                                        String calibrationMethod,
                                        double probabilityP1,
                                        List<MatchupAnalysisDto.FeatureContributionDto> contributions,
                                        MatchupFeatureVectorDto featureVector,
                                        double baselineProbability,
                                        double logisticProbability,
                                        double glickoProbability,
                                        double gbtProbability,
                                        double rfProbability,
                                        double ensembleProbability) {
        ConsensusProfile consensus = consensusProfile(
                baselineProbability,
                logisticProbability,
                glickoProbability,
                gbtProbability,
                rfProbability,
                ensembleProbability
        );
        LiveLearningCalibration liveCalibration = loadLiveLearningCalibration();
        double stabilizedProbability = stabilizeProbability(
                probabilityP1,
                featureVector,
                consensus.mean(),
                consensus.disagreement(),
                liveCalibration
        );
        double[] ci = probabilityInterval(stabilizedProbability, featureVector, consensus.disagreement(), liveCalibration);
        return new PredictionSnapshot(
                family,
                version,
                calibrationMethod,
                clamp01(stabilizedProbability),
                clamp01(1.0 - stabilizedProbability),
                ci[0],
                ci[1],
                contributions,
                featureVector,
                baselineProbability,
                logisticProbability,
                glickoProbability,
                gbtProbability,
                rfProbability,
                ensembleProbability
        );
    }

    private double stabilizeProbability(double rawProbability,
                                        MatchupFeatureVectorDto fv,
                                        double consensusMean,
                                        double disagreement,
                                        LiveLearningCalibration liveCalibration) {
        double raw = applyTemperature(clamp01(rawProbability));
        double disagreementFactor = clamp((disagreement - 0.02) / 0.18, 0.0, 1.0);
        double consensusPull = clamp(consensusShrink, 0.0, 0.8) * disagreementFactor;
        double consensusBlended = (raw * (1.0 - consensusPull)) + (consensusMean * consensusPull);

        double rd1 = Math.max(30.0, fv.player1().glickoRatingDeviation());
        double rd2 = Math.max(30.0, fv.player2().glickoRatingDeviation());
        double rdAvg = (rd1 + rd2) / 2.0;
        double rdUncertainty = clamp((rdAvg - 60.0) / 260.0, 0.0, 1.0);

        double volAvg = Math.max(0.0, (fv.player1().glickoVolatility() + fv.player2().glickoVolatility()) / 2.0);
        double volUncertainty = clamp((volAvg - 0.03) / 0.10, 0.0, 1.0);
        double sampleUncertainty = sampleUncertainty(fv);

        double uncertainty = (0.7 * rdUncertainty) + (0.3 * volUncertainty);
        uncertainty = clamp(uncertainty + (0.4 * disagreementFactor), 0.0, 1.0);
        uncertainty = clamp(uncertainty + (clamp(sampleUncertaintyWeight, 0.0, 1.0) * sampleUncertainty), 0.0, 1.0);
        double shrink = clamp(uncertaintyShrink, 0.0, 0.8) * uncertainty;
        double blended = 0.5 + ((consensusBlended - 0.5) * (1.0 - shrink));
        if (liveLearningApplyEnabled && liveCalibration != null) {
            blended = 0.5 + ((blended - 0.5) * liveCalibration.confidenceScale());
        }

        double floor = clamp(probabilityFloor + (0.03 * disagreementFactor), 0.01, 0.49);
        double ceiling = clamp(probabilityCeiling - (0.03 * disagreementFactor), 0.51, 0.99);
        if (ceiling <= floor) {
            ceiling = 1.0 - floor;
        }
        return clamp(blended, floor, ceiling);
    }

    private double applyTemperature(double probability) {
        double p = clamp(probability, EPS, 1.0 - EPS);
        double temperature = clamp(probabilityTemperature, 1.0, 3.0);
        if (temperature <= 1.01) {
            return p;
        }
        double logit = Math.log(p / (1.0 - p));
        return 1.0 / (1.0 + Math.exp(-(logit / temperature)));
    }

    private double[] probabilityInterval(double probability,
                                         MatchupFeatureVectorDto fv,
                                         double disagreement,
                                         LiveLearningCalibration liveCalibration) {
        double rd1 = Math.max(30.0, fv.player1().glickoRatingDeviation());
        double rd2 = Math.max(30.0, fv.player2().glickoRatingDeviation());
        double avgVol = Math.max(0.0, (fv.player1().glickoVolatility() + fv.player2().glickoVolatility()) / 2.0);
        double sampleUncertainty = sampleUncertainty(fv);
        double rdSpread = Math.sqrt(rd1 * rd1 + rd2 * rd2);
        double spread = (2.0 * rdSpread) / 2000.0;
        spread *= (1.0 + Math.min(0.8, avgVol * 6.0));
        spread += clamp(disagreementCiBoost, 0.0, 1.5) * clamp(disagreement, 0.0, 0.35) * 0.35;
        spread += clamp(sampleCiBoost, 0.0, 0.5) * sampleUncertainty;
        if (liveLearningApplyEnabled && liveCalibration != null) {
            spread += liveCalibration.ciBoost();
        }
        spread = clamp(spread, 0.06, 0.50);
        return new double[]{
                clamp(probability - spread, 0.03, 0.97),
                clamp(probability + spread, 0.03, 0.97)
        };
    }

    private double sampleUncertainty(MatchupFeatureVectorDto fv) {
        double supportTarget = clamp(sampleSupportTarget, 2.0, 50.0);
        double h2hRel = h2hSupportReliability(fv.headToHeadSampleWeight(), supportTarget);
        double recentRel = Math.min(
                supportReliability(fv.player1().recentFormSampleWeight(), supportTarget),
                supportReliability(fv.player2().recentFormSampleWeight(), supportTarget)
        );
        double opponentRel = Math.min(
                supportReliability(fv.player1().opponentAdjustedSampleWeight(), supportTarget),
                supportReliability(fv.player2().opponentAdjustedSampleWeight(), supportTarget)
        );
        double scheduleRel = Math.min(
                supportReliability(fv.player1().scheduleStrengthSampleWeight(), supportTarget),
                supportReliability(fv.player2().scheduleStrengthSampleWeight(), supportTarget)
        );

        double combinedReliability = (0.35 * h2hRel) + (0.35 * recentRel) + (0.20 * opponentRel) + (0.10 * scheduleRel);
        return clamp(1.0 - combinedReliability, 0.0, 1.0);
    }

    private double supportReliability(double sampleWeight, double supportTarget) {
        double support = Math.max(0.0, sampleWeight);
        double target = Math.max(1.0, supportTarget);
        double base = support / (support + target);
        double exponent = clamp(sampleSupportExponent, 1.0, 2.5);
        return clamp(Math.pow(base, exponent), 0.0, 1.0);
    }

    private double h2hSupportReliability(double sampleWeight, double supportTarget) {
        double support = Math.max(0.0, sampleWeight);
        double target = Math.max(1.0, supportTarget) * clamp(h2hSupportTargetMultiplier, 1.0, 3.0);
        double base = support / (support + target);
        double exponent = clamp(h2hSupportExponent, 1.0, 2.5);
        return clamp(Math.pow(base, exponent), 0.0, 1.0);
    }

    private double trainingSampleWeight(MatchupFeatureVectorDto fv) {
        double supportTarget = clamp(sampleSupportTarget, 2.0, 50.0);
        double h2hRel = h2hSupportReliability(fv.headToHeadSampleWeight(), supportTarget);
        double recentRel = Math.min(
                supportReliability(fv.player1().recentFormSampleWeight(), supportTarget),
                supportReliability(fv.player2().recentFormSampleWeight(), supportTarget)
        );
        double opponentRel = Math.min(
                supportReliability(fv.player1().opponentAdjustedSampleWeight(), supportTarget),
                supportReliability(fv.player2().opponentAdjustedSampleWeight(), supportTarget)
        );
        double scheduleRel = Math.min(
                supportReliability(fv.player1().scheduleStrengthSampleWeight(), supportTarget),
                supportReliability(fv.player2().scheduleStrengthSampleWeight(), supportTarget)
        );
        double avgRd = (Math.max(30.0, fv.player1().glickoRatingDeviation())
                + Math.max(30.0, fv.player2().glickoRatingDeviation())) / 2.0;
        double rdPenalty = clamp((avgRd - 90.0) / 260.0, 0.0, 1.0);
        double baseReliability = (0.34 * h2hRel) + (0.33 * recentRel) + (0.20 * opponentRel) + (0.13 * scheduleRel);
        return clamp(0.20 + (0.80 * baseReliability) - (0.12 * rdPenalty), 0.12, 1.0);
    }

    private ConsensusProfile consensusProfile(double baselineProbability,
                                              double logisticProbability,
                                              double glickoProbability,
                                              double gbtProbability,
                                              double rfProbability,
                                              double ensembleProbability) {
        double[] values = new double[]{
                clamp01(baselineProbability),
                clamp01(logisticProbability),
                clamp01(glickoProbability),
                clamp01(gbtProbability),
                clamp01(rfProbability),
                clamp01(ensembleProbability)
        };
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;

        double variance = 0.0;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        variance /= values.length;
        double std = Math.sqrt(Math.max(0.0, variance));
        return new ConsensusProfile(mean, std);
    }

    private List<TrainingSample> buildSamples(LocalDate fromDate, LocalDate toDate) {
        LocalDate first = matchRepository.findFirstCompletedMatchDate();
        LocalDate last = matchRepository.findLastCompletedMatchDate();
        if (first == null || last == null) {
            return List.of();
        }
        LocalDate maxReasonableDate = LocalDate.now().plusDays(1);
        LocalDate sanitizedFirst = first.isBefore(MIN_REASONABLE_MATCH_DATE) ? MIN_REASONABLE_MATCH_DATE : first;
        LocalDate sanitizedLast = last.isAfter(maxReasonableDate) ? maxReasonableDate : last;
        if (sanitizedLast.isBefore(sanitizedFirst)) {
            return List.of();
        }

        LocalDate from = fromDate == null ? sanitizedFirst : fromDate;
        LocalDate to = toDate == null ? sanitizedLast : toDate;
        if (from.isBefore(MIN_REASONABLE_MATCH_DATE)) {
            from = MIN_REASONABLE_MATCH_DATE;
        }
        if (to.isAfter(maxReasonableDate)) {
            to = maxReasonableDate;
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("toDate cannot be before fromDate");
        }

        List<Match> matches = matchRepository.findCompletedMatchesBetween(from, to);
        List<TrainingSample> samples = new ArrayList<>(matches.size());
        for (Match match : matches) {
            if (match.getPlayer1() == null || match.getPlayer2() == null || match.getDate() == null || match.getWinnerPlayerId() == null) {
                continue;
            }
            Long p1Id = match.getPlayer1().getId();
            Long p2Id = match.getPlayer2().getId();
            if (p1Id == null || p2Id == null) continue;
            int label;
            if (Objects.equals(match.getWinnerPlayerId(), p1Id)) {
                label = 1;
            } else if (Objects.equals(match.getWinnerPlayerId(), p2Id)) {
                label = 0;
            } else {
                continue;
            }
            LocalDate asOf = match.getDate().minusDays(1);
            MatchupFeatureVectorDto fv = featureService.buildMatchupFeatureVector(p1Id, p2Id, asOf);
            samples.add(new TrainingSample(match.getDate(), toBaseFeatures(fv), label, trainingSampleWeight(fv)));
        }
        samples.sort(Comparator.comparing(TrainingSample::matchDate));
        return samples;
    }

    private double[] toBaseFeatures(MatchupFeatureVectorDto fv) {
        double supportTarget = clamp(sampleSupportTarget, 2.0, 50.0);
        double h2hReliability = h2hSupportReliability(fv.headToHeadSampleWeight(), supportTarget);
        double p1RecentReliability = supportReliability(fv.player1().recentFormSampleWeight(), supportTarget);
        double p2RecentReliability = supportReliability(fv.player2().recentFormSampleWeight(), supportTarget);
        double p1OpponentReliability = supportReliability(fv.player1().opponentAdjustedSampleWeight(), supportTarget);
        double p2OpponentReliability = supportReliability(fv.player2().opponentAdjustedSampleWeight(), supportTarget);
        double p1ScheduleReliability = supportReliability(fv.player1().scheduleStrengthSampleWeight(), supportTarget);
        double p2ScheduleReliability = supportReliability(fv.player2().scheduleStrengthSampleWeight(), supportTarget);

        double recentReliability = Math.min(p1RecentReliability, p2RecentReliability);
        double opponentReliability = Math.min(p1OpponentReliability, p2OpponentReliability);
        double scheduleReliability = Math.min(p1ScheduleReliability, p2ScheduleReliability);

        double h2hDelta = (fv.headToHeadWinRatePlayer1() - 0.5) * h2hReliability;
        double recentDiff = (fv.player1().recentForm() - fv.player2().recentForm()) * recentReliability;
        double adjDiff = (fv.player1().opponentAdjustedForm() - fv.player2().opponentAdjustedForm()) * opponentReliability;
        double scheduleDiff = ((fv.player1().scheduleStrength() - fv.player2().scheduleStrength()) / 300.0) * scheduleReliability;
        double eloDelta = fv.eloProbabilityPlayer1() - 0.5;
        double glickoDelta = fv.glickoProbabilityPlayer1() - 0.5;
        double ratingDiff = (fv.player1().glickoRating() - fv.player2().glickoRating()) / 400.0;
        double rdAdvantage = (fv.player2().glickoRatingDeviation() - fv.player1().glickoRatingDeviation()) / 200.0;
        double volAdvantage = (fv.player2().glickoVolatility() - fv.player1().glickoVolatility()) / 0.2;
        double p1Form = 0.5 + ((fv.player1().recentForm() - 0.5) * p1RecentReliability);
        double p2Form = 0.5 + ((fv.player2().recentForm() - 0.5) * p2RecentReliability);
        double interaction = recentDiff * h2hDelta;
        double trueSkill2Delta = fv.trueSkill2ProbabilityPlayer1() - 0.5;
        double wengLinDelta = fv.wengLinProbabilityPlayer1() - 0.5;
        double raterEnsembleDelta = fv.raterEnsembleProbabilityPlayer1() - 0.5;
        double ratingAgreement = fv.reliabilitySummary() == null
                ? 0.0
                : clamp(fv.reliabilitySummary().ratingAgreement(), 0.0, 1.0);
        double raterConsensusSignal = raterEnsembleDelta * ratingAgreement;
        return new double[]{
                h2hDelta,
                recentDiff,
                adjDiff,
                scheduleDiff,
                eloDelta,
                glickoDelta,
                ratingDiff,
                rdAdvantage,
                volAdvantage,
                p1Form,
                p2Form,
                interaction,
                trueSkill2Delta,
                wengLinDelta,
                raterEnsembleDelta,
                raterConsensusSignal
        };
    }

    private double baselineProbability(double[] x) {
        double z = 0.0;
        z += 0.90 * x[0];
        z += 0.95 * x[1];
        z += 0.55 * x[2];
        z += 0.35 * x[3];
        z += 0.85 * x[4];
        z += 1.05 * x[5];
        z += 0.45 * x[6];
        z += 0.25 * x[7];
        z += 0.15 * x[8];
        z += 0.20 * x[11];
        z += 1.10 * x[12];
        z += 0.80 * x[13];
        z += 0.70 * x[14];
        z += 0.45 * x[15];
        return sigmoid(z);
    }

    private List<MatchupAnalysisDto.FeatureContributionDto> baselineContributions(double[] x) {
        double[] weights = new double[]{
                0.90, 0.95, 0.55, 0.35, 0.85, 1.05, 0.45, 0.25,
                0.15, 0.0, 0.0, 0.20, 1.10, 0.80, 0.70, 0.45
        };
        List<MatchupAnalysisDto.FeatureContributionDto> out = new ArrayList<>();
        for (int i = 0; i < BASE_FEATURE_NAMES.length; i++) {
            double contribution = x[i] * weights[i];
            if (Math.abs(contribution) < 1e-8) continue;
            out.add(new MatchupAnalysisDto.FeatureContributionDto(BASE_FEATURE_NAMES[i], round4(contribution)));
        }
        out.sort((a, b) -> Double.compare(Math.abs(b.contribution()), Math.abs(a.contribution())));
        if (out.size() > 8) {
            return out.subList(0, 8);
        }
        return out;
    }

    private double selectBestLambda(List<TrainingSample> train, List<Double> lambdas, FeatureSet featureSet) {
        int n = train.size();
        int folds = clamp(cvFolds, 2, Math.min(8, n / 8));
        double bestLambda = lambdas.get(0);
        double bestBrier = Double.POSITIVE_INFINITY;
        double bestLogLoss = Double.POSITIVE_INFINITY;

        for (double lambda : lambdas) {
            double brierTotal = 0.0;
            double logLossTotal = 0.0;
            int usedFolds = 0;
            int validationChunk = Math.max(5, Math.min(24, n / (folds + 1)));
            for (int fold = 0; fold < folds; fold++) {
                int latestTrainStart = Math.max(10, n - validationChunk);
                int trainEnd = Math.max(10, ((fold + 1) * latestTrainStart) / folds);
                int start = Math.min(trainEnd, n - validationChunk);
                int end = Math.min(n, start + validationChunk);
                if (start >= end) continue;
                List<TrainingSample> foldTrain = train.subList(0, start);
                List<TrainingSample> validation = train.subList(start, end);
                if (foldTrain.size() < 10 || validation.size() < 5) continue;

                LogisticModel model = trainLogisticModel(foldTrain, lambda, featureSet, Math.max(80, trainEpochs / 2), learningRate);
                CandidateMetrics metrics = evaluateCandidate(model, validation);
                brierTotal += metrics.brierScore;
                logLossTotal += metrics.logLoss;
                usedFolds++;
            }
            if (usedFolds == 0) continue;
            double meanBrier = brierTotal / usedFolds;
            double meanLogLoss = logLossTotal / usedFolds;
            if (meanBrier < bestBrier || (Math.abs(meanBrier - bestBrier) < 1e-9 && meanLogLoss < bestLogLoss)) {
                bestBrier = meanBrier;
                bestLogLoss = meanLogLoss;
                bestLambda = lambda;
            }
        }
        return bestLambda;
    }

    private LogisticModel trainLogisticModel(List<TrainingSample> train,
                                             double lambda,
                                             FeatureSet featureSet,
                                             int epochs,
                                             double lr) {
        int d = featureSet.featureNames.length;
        int n = train.size();
        double[][] X = new double[n][d];
        double[] y = new double[n];
        double[] sampleWeights = new double[n];
        double weightSum = 0.0;
        for (int i = 0; i < n; i++) {
            TrainingSample s = train.get(i);
            X[i] = featureSet.transform.apply(s.baseFeatures);
            y[i] = s.label;
            sampleWeights[i] = clamp(s.sampleWeight(), 0.05, 3.0);
            weightSum += sampleWeights[i];
        }
        if (weightSum <= EPS) {
            weightSum = n;
            Arrays.fill(sampleWeights, 1.0);
        }

        double[] means = new double[d];
        double[] stds = new double[d];
        for (int j = 0; j < d; j++) {
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                sum += sampleWeights[i] * X[i][j];
            }
            means[j] = sum / weightSum;
            double sq = 0.0;
            for (int i = 0; i < n; i++) {
                double dx = X[i][j] - means[j];
                sq += sampleWeights[i] * dx * dx;
            }
            stds[j] = Math.sqrt(Math.max(EPS, sq / weightSum));
        }

        double[] weights = new double[d + 1];
        int totalEpochs = Math.max(30, epochs);
        double rate = clamp(lr, 0.01, 0.3);

        for (int epoch = 0; epoch < totalEpochs; epoch++) {
            double[] grad = new double[d + 1];
            for (int i = 0; i < n; i++) {
                double z = weights[0];
                for (int j = 0; j < d; j++) {
                    z += weights[j + 1] * standardized(X[i][j], means[j], stds[j]);
                }
                double p = sigmoid(z);
                double err = (p - y[i]) * sampleWeights[i];
                grad[0] += err;
                for (int j = 0; j < d; j++) {
                    grad[j + 1] += err * standardized(X[i][j], means[j], stds[j]);
                }
            }
            double invW = 1.0 / weightSum;
            weights[0] -= rate * grad[0] * invW;
            for (int j = 1; j <= d; j++) {
                double reg = lambda * weights[j];
                weights[j] -= rate * (grad[j] * invW + reg);
            }
            rate *= 0.995;
        }

        return new LogisticModel(featureSet.featureNames, featureSet.transform, means, stds, weights, lambda);
    }

    private void maybeCalibrate(LogisticModel model,
                                List<TrainingSample> validation,
                                CandidateMetrics currentMetrics) {
        if (validation.size() < 30) {
            currentMetrics.calibrationMethod = "NONE";
            return;
        }

        double[] logits = new double[validation.size()];
        int[] labels = new int[validation.size()];
        for (int i = 0; i < validation.size(); i++) {
            logits[i] = model.rawScore(validation.get(i).baseFeatures);
            labels[i] = validation.get(i).label;
        }

        PlattCalibrator calibrator = fitPlatt(logits, labels);
        if (calibrator == null) {
            currentMetrics.calibrationMethod = "NONE";
            return;
        }

        CandidateMetrics calibrated = evaluateCandidate(model.withCalibrator(calibrator), validation);
        if (calibrated.brierScore + 0.001 < currentMetrics.brierScore) {
            model.calibrator = calibrator;
            currentMetrics.accuracy = calibrated.accuracy;
            currentMetrics.logLoss = calibrated.logLoss;
            currentMetrics.brierScore = calibrated.brierScore;
            currentMetrics.calibrationMethod = "PLATT";
        } else {
            currentMetrics.calibrationMethod = "NONE";
        }
    }

    private PlattCalibrator fitPlatt(double[] logits, int[] labels) {
        if (logits.length != labels.length || logits.length < 20) {
            return null;
        }
        double a = 1.0;
        double b = 0.0;
        double lr = 0.05;
        int n = logits.length;
        for (int epoch = 0; epoch < 400; epoch++) {
            double gradA = 0.0;
            double gradB = 0.0;
            for (int i = 0; i < n; i++) {
                double p = sigmoid(a * logits[i] + b);
                double err = p - labels[i];
                gradA += err * logits[i];
                gradB += err;
            }
            gradA /= n;
            gradB /= n;
            a -= lr * gradA;
            b -= lr * gradB;
            lr *= 0.998;
        }
        return new PlattCalibrator(a, b);
    }

    private RandomForestLikeModel trainRandomForest(List<TrainingSample> train, FeatureSet featureSet, int trees) {
        int n = train.size();
        int d = featureSet.featureNames.length;
        double[][] X = new double[n][d];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            X[i] = featureSet.transform.apply(train.get(i).baseFeatures);
            y[i] = train.get(i).label;
        }
        double[] means = new double[d];
        for (int j = 0; j < d; j++) {
            double sum = 0.0;
            for (double[] row : X) sum += row[j];
            means[j] = sum / n;
        }
        double globalRate = Arrays.stream(y).average().orElse(0.5);
        Random random = new Random(42L);
        List<Stump> stumps = new ArrayList<>(trees);

        for (int t = 0; t < trees; t++) {
            int f = random.nextInt(d);
            double threshold = X[random.nextInt(n)][f];

            int leftCount = 0;
            int rightCount = 0;
            int leftWins = 0;
            int rightWins = 0;
            for (int i = 0; i < n; i++) {
                int idx = random.nextInt(n);
                if (X[idx][f] <= threshold) {
                    leftCount++;
                    leftWins += y[idx];
                } else {
                    rightCount++;
                    rightWins += y[idx];
                }
            }
            double leftRate = leftCount < 5 ? globalRate : (leftWins / (double) leftCount);
            double rightRate = rightCount < 5 ? globalRate : (rightWins / (double) rightCount);
            stumps.add(new Stump(f, threshold, clamp01(leftRate), clamp01(rightRate)));
        }

        return new RandomForestLikeModel(featureSet.featureNames, featureSet.transform, means, stumps);
    }

    private EnsembleModel buildEnsemble(PredictModel logistic,
                                        CandidateMetrics logisticMetrics,
                                        PredictModel advanced,
                                        CandidateMetrics advancedMetrics) {
        if (advancedMetrics.brierScore + minLiftForAdvanced >= logisticMetrics.brierScore) {
            return new EnsembleModel(logistic, advanced, 1.0, 0.0);
        }
        double wLog = 1.0 / Math.max(1e-6, logisticMetrics.brierScore);
        double wAdv = 1.0 / Math.max(1e-6, advancedMetrics.brierScore);
        double total = wLog + wAdv;
        return new EnsembleModel(logistic, advanced, wLog / total, wAdv / total);
    }

    private CandidateMetrics evaluateCandidate(PredictModel model, List<TrainingSample> validation) {
        double correctWeight = 0.0;
        double logLoss = 0.0;
        double brier = 0.0;
        double weightSum = 0.0;
        for (TrainingSample sample : validation) {
            double sampleWeight = clamp(sample.sampleWeight(), 0.05, 3.0);
            weightSum += sampleWeight;
            double p = clampProbability(model.predict(sample.baseFeatures));
            int y = sample.label;
            if ((p >= 0.5 && y == 1) || (p < 0.5 && y == 0)) {
                correctWeight += sampleWeight;
            }
            logLoss += sampleWeight * (-(y * Math.log(p) + (1 - y) * Math.log(1 - p)));
            brier += sampleWeight * Math.pow(p - y, 2);
        }
        double denom = Math.max(EPS, weightSum);
        return new CandidateMetrics(model, model.family(), correctWeight / denom, logLoss / denom, brier / denom, "NONE");
    }

    private LiveLearningCalibration loadLiveLearningCalibration() {
        if (!liveLearningEnabled) {
            return LiveLearningCalibration.neutral();
        }

        LocalDateTime now = LocalDateTime.now();
        int ttlSeconds = clamp(liveLearningCacheTtlSeconds, 10, 3600);
        LiveLearningCalibrationCache cached = liveLearningCache.get();
        if (cached != null && cached.createdAt().isAfter(now.minusSeconds(ttlSeconds))) {
            return cached.profile();
        }

        int take = clamp(liveLearningWindow, 30, 1000);
        int minSamples = clamp(liveLearningMinSamples, 10, 400);
        List<PaperTradeLearningSample> rows =
                learningSampleRepository.findByLearningEligibleTrueAndStatusInOrderByEventOccurredAtDesc(
                List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST),
                PageRequest.of(0, take)
        );
        if (rows.size() < minSamples) {
            LiveLearningCalibration profile = new LiveLearningCalibration(rows.size(), 0.0, 1.0, 0.0, now);
            liveLearningCache.set(new LiveLearningCalibrationCache(profile, now));
            return profile;
        }

        double halfLifeDays = Math.max(2.0, liveLearningHalfLifeDays);
        double weightedWins = 0.0;
        double weightedModel = 0.0;
        double weightedStake = 0.0;
        double weightedPnl = 0.0;
        double weightSum = 0.0;
        double squaredWeightSum = 0.0;
        for (PaperTradeLearningSample row : rows) {
            if (row == null) {
                continue;
            }
            double w = recencyWeight(learningEventTime(row), now, halfLifeDays)
                    * clamp(row.getSettlementConfidence(), 0.0, 1.0);
            if (w <= 0.0) {
                continue;
            }
            weightSum += w;
            squaredWeightSum += w * w;
            if (PaperTradeBet.STATUS_WON.equals(row.getStatus())) {
                weightedWins += w;
            }
            weightedModel += clamp(row.getModelProbability(), 0.01, 0.99) * w;
            double stake = Math.max(0.0, row.getStake());
            weightedStake += stake * w;
            weightedPnl += row.getProfitLoss() * w;
        }

        if (weightSum <= EPS) {
            LiveLearningCalibration profile = LiveLearningCalibration.neutral();
            liveLearningCache.set(new LiveLearningCalibrationCache(profile, now));
            return profile;
        }

        double observed = weightedWins / weightSum;
        double predicted = weightedModel / weightSum;
        double calibrationError = predicted - observed;
        double roiSignal = weightedStake <= EPS ? 0.0 : weightedPnl / weightedStake;
        double effectiveSampleSize = squaredWeightSum <= EPS
                ? 0.0
                : (weightSum * weightSum) / squaredWeightSum;
        int minimumEffective = clamp(liveLearningMinEffectiveSamples, 20, 400);
        double reliability = clamp(
                effectiveSampleSize / (effectiveSampleSize + Math.max(minimumEffective, minSamples * 2.0)),
                0.0,
                1.0
        );
        if (effectiveSampleSize < minimumEffective) {
            reliability = 0.0;
        }
        double maxScaleShift = clamp(liveLearningMaxScaleShift, 0.01, 0.25);
        // Online data may only shrink confidence. Increasing confidence or
        // moving thresholds requires a separately promoted offline model.
        double scaleShiftRaw = -Math.max(0.0, calibrationError) * 0.75;
        double scaleShift = clamp(scaleShiftRaw * reliability, -maxScaleShift, 0.0);
        double confidenceScale = clamp(1.0 + scaleShift, 1.0 - maxScaleShift, 1.0);
        double ciBoost = clamp(
                ((Math.max(0.0, calibrationError) * 0.32) + (Math.max(0.0, -roiSignal) * 0.08)) * reliability,
                0.0,
                0.12
        );

        LiveLearningCalibration profile = new LiveLearningCalibration(
                rows.size(),
                round4(reliability),
                round4(confidenceScale),
                round4(ciBoost),
                now
        );
        liveLearningCache.set(new LiveLearningCalibrationCache(profile, now));
        return profile;
    }

    private List<AdaptiveRegimeProfile> loadAdaptiveRegimeProfiles() {
        if (!liveLearningEnabled) {
            return List.of();
        }

        LocalDateTime now = LocalDateTime.now();
        int ttlSeconds = clamp(liveLearningCacheTtlSeconds, 10, 3600);
        AdaptiveRegimeProfileCache cached = adaptiveRegimeCache.get();
        if (cached != null && cached.createdAt().isAfter(now.minusSeconds(ttlSeconds))) {
            return cached.profiles();
        }

        int take = clamp(regimeLearningWindow, 40, 800);
        List<PaperTradeLearningSample> rows =
                learningSampleRepository.findByLearningEligibleTrueAndStatusInOrderByEventOccurredAtDesc(
                List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST),
                PageRequest.of(0, take)
        );
        List<AdaptiveRegimeProfile> profiles = buildAdaptiveRegimeProfiles(rows, now);
        adaptiveRegimeCache.set(new AdaptiveRegimeProfileCache(profiles, now));
        return profiles;
    }

    private List<AdaptiveRegimeProfile> buildAdaptiveRegimeProfiles(List<PaperTradeLearningSample> rows, LocalDateTime now) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<AdaptiveRegimeProfile> profiles = new ArrayList<>();
        addAdaptiveProfile(profiles, "All Settled", rows, now, row -> true, false, "ALL", "ALL");
        addAdaptiveProfile(profiles, "Prematch", rows, now, row -> !row.isLiveAtPlacement(), false, "PREMATCH", "ALL");
        addAdaptiveProfile(profiles, "Live", rows, now, PaperTradeLearningSample::isLiveAtPlacement, true, "LIVE", "ALL");
        addAdaptiveProfile(profiles, "LIVE_EARLY", rows, now, row -> "LIVE_EARLY".equals(normalizePhase(placementPhase(row))), true, "LIVE_EARLY", "ALL");
        addAdaptiveProfile(profiles, "LIVE_MID", rows, now, row -> "LIVE_MID".equals(normalizePhase(placementPhase(row))), true, "LIVE_MID", "ALL");
        addAdaptiveProfile(profiles, "LIVE_LATE", rows, now, row -> "LIVE_LATE".equals(normalizePhase(placementPhase(row))), true, "LIVE_LATE", "ALL");
        addAdaptiveProfile(profiles, "Favorite Side", rows, now, row -> row.getImpliedProbability() >= 0.55, false, "ALL", "FAVORITE");
        addAdaptiveProfile(profiles, "Underdog Side", rows, now, row -> row.getImpliedProbability() <= 0.45, false, "ALL", "UNDERDOG");
        profiles.sort(Comparator.comparingDouble(AdaptiveRegimeProfile::reliability).reversed().thenComparing(AdaptiveRegimeProfile::label));
        return profiles;
    }

    private void addAdaptiveProfile(List<AdaptiveRegimeProfile> out,
                                    String label,
                                    List<PaperTradeLearningSample> rows,
                                    LocalDateTime now,
                                    Predicate<PaperTradeLearningSample> filter,
                                    boolean live,
                                    String phase,
                                    String sideType) {
        List<PaperTradeLearningSample> selected = rows.stream().filter(Objects::nonNull).filter(filter).toList();
        int minSamples = clamp(regimeLearningMinSamples, 8, 100);
        if (selected.size() < minSamples) {
            return;
        }

        double halfLifeDays = Math.max(2.0, liveLearningHalfLifeDays);
        double predicted = 0.0;
        double observed = 0.0;
        double weightedStake = 0.0;
        double weightedPnl = 0.0;
        double weightSum = 0.0;
        double squaredWeightSum = 0.0;
        for (PaperTradeLearningSample row : selected) {
            double w = recencyWeight(learningEventTime(row), now, halfLifeDays)
                    * clamp(row.getSettlementConfidence(), 0.0, 1.0);
            if (w <= 0.0) {
                continue;
            }
            weightSum += w;
            squaredWeightSum += w * w;
            predicted += clamp(row.getModelProbability(), 0.01, 0.99) * w;
            observed += (PaperTradeBet.STATUS_WON.equals(row.getStatus()) ? 1.0 : 0.0) * w;
            weightedStake += Math.max(0.0, row.getStake()) * w;
            weightedPnl += row.getProfitLoss() * w;
        }
        if (weightSum <= EPS) {
            return;
        }

        double meanPredicted = predicted / weightSum;
        double observedRate = observed / weightSum;
        double calibrationError = meanPredicted - observedRate;
        double roiSignal = weightedStake <= EPS ? 0.0 : weightedPnl / weightedStake;
        double effectiveSampleSize = squaredWeightSum <= EPS
                ? 0.0
                : (weightSum * weightSum) / squaredWeightSum;
        double reliability = clamp(
                effectiveSampleSize / (effectiveSampleSize + Math.max(minSamples * 3.0, 24.0)),
                0.0,
                1.0
        );
        if (effectiveSampleSize < minSamples) {
            reliability = 0.0;
        }
        double maxScaleShift = clamp(regimeLearningMaxScaleShift, 0.01, 0.18);
        double scaleShiftRaw = -Math.max(0.0, calibrationError) * 0.65;
        double scaleShift = clamp(scaleShiftRaw * reliability, -maxScaleShift, 0.0);
        double confidenceScale = clamp(1.0 + scaleShift, 1.0 - maxScaleShift, 1.0);
        double ciBoost = clamp(
                ((Math.max(0.0, calibrationError) * 0.28) + (Math.max(0.0, -roiSignal) * 0.07)) * reliability,
                0.0,
                0.10
        );

        out.add(new AdaptiveRegimeProfile(
                label,
                selected.size(),
                reliability,
                calibrationError,
                roiSignal,
                confidenceScale,
                ciBoost,
                live,
                phase,
                sideType
        ));
    }

    private AdaptiveRegimeProfile findProfile(List<AdaptiveRegimeProfile> profiles, String label) {
        if (!StringUtils.hasText(label)) {
            return null;
        }
        return profiles.stream()
                .filter(profile -> profile.label().equalsIgnoreCase(label))
                .findFirst()
                .orElse(null);
    }

    private double recencyWeight(LocalDateTime eventOccurredAt, LocalDateTime now, double halfLifeDays) {
        if (eventOccurredAt == null || now == null) {
            return 1.0;
        }
        long days = Math.max(0L, ChronoUnit.DAYS.between(eventOccurredAt.toLocalDate(), now.toLocalDate()));
        double halfLife = Math.max(2.0, halfLifeDays);
        return Math.pow(0.5, days / halfLife);
    }

    private LocalDateTime learningEventTime(PaperTradeLearningSample row) {
        if (row == null) {
            return null;
        }
        if (row.getEventOccurredAt() != null) {
            return row.getEventOccurredAt();
        }
        return row.getPlacedAt() != null ? row.getPlacedAt() : row.getSettledAt();
    }

    private String placementPhase(PaperTradeLearningSample row) {
        if (row == null) {
            return null;
        }
        return StringUtils.hasText(row.getPlacementPhase())
                ? row.getPlacementPhase()
                : row.getLastObservedPhase();
    }

    private List<ModelTrainingReportDto.CalibrationBinDto> buildCalibrationCurve(PredictModel model,
                                                                                  List<TrainingSample> validation,
                                                                                  int bins) {
        int nBins = Math.max(5, Math.min(20, bins));
        int[] count = new int[nBins];
        double[] pred = new double[nBins];
        double[] obs = new double[nBins];
        for (TrainingSample sample : validation) {
            double p = clamp01(model.predict(sample.baseFeatures));
            int b = Math.min(nBins - 1, (int) Math.floor(p * nBins));
            count[b]++;
            pred[b] += p;
            obs[b] += sample.label;
        }

        List<ModelTrainingReportDto.CalibrationBinDto> out = new ArrayList<>(nBins);
        for (int i = 0; i < nBins; i++) {
            double lo = i / (double) nBins;
            double hi = (i + 1) / (double) nBins;
            if (count[i] == 0) {
                out.add(new ModelTrainingReportDto.CalibrationBinDto(lo, hi, 0, 0.0, 0.0));
            } else {
                out.add(new ModelTrainingReportDto.CalibrationBinDto(
                        lo,
                        hi,
                        count[i],
                        pred[i] / count[i],
                        obs[i] / count[i]
                ));
            }
        }
        return out;
    }

    private List<ModelTrainingReportDto.RegimeMetricDto> buildValidationRegimes(PredictModel model,
                                                                                 List<TrainingSample> validation) {
        List<ValidationObservation> rows = new ArrayList<>(validation.size());
        for (TrainingSample sample : validation) {
            double weight = clamp(sample.sampleWeight(), 0.05, 3.0);
            rows.add(new ValidationObservation(
                    clampProbability(model.predict(sample.baseFeatures)),
                    sample.label,
                    weight
            ));
        }

        List<ModelTrainingReportDto.RegimeMetricDto> out = new ArrayList<>();
        addValidationRegime(out, "All Validation", rows, row -> true);
        addValidationRegime(out, "Favorite", rows, row -> row.predicted() >= 0.60);
        addValidationRegime(out, "Underdog", rows, row -> row.predicted() <= 0.40);
        addValidationRegime(out, "High Confidence", rows, row -> row.predicted() >= 0.68 || row.predicted() <= 0.32);
        addValidationRegime(out, "Coin Flip", rows, row -> row.predicted() >= 0.45 && row.predicted() <= 0.55);
        return out;
    }

    private void addValidationRegime(List<ModelTrainingReportDto.RegimeMetricDto> out,
                                     String label,
                                     List<ValidationObservation> rows,
                                     Predicate<ValidationObservation> filter) {
        List<ValidationObservation> selected = rows.stream().filter(filter).toList();
        if (selected.isEmpty()) {
            return;
        }

        double weightSum = selected.stream().mapToDouble(ValidationObservation::weight).sum();
        if (weightSum <= EPS) {
            return;
        }

        double predicted = selected.stream().mapToDouble(row -> row.predicted() * row.weight()).sum() / weightSum;
        double observed = selected.stream().mapToDouble(row -> row.label() * row.weight()).sum() / weightSum;
        double accuracy = selected.stream()
                .mapToDouble(row -> (((row.predicted() >= 0.5) == (row.label() == 1)) ? row.weight() : 0.0))
                .sum() / weightSum;
        double brier = selected.stream()
                .mapToDouble(row -> Math.pow(row.predicted() - row.label(), 2) * row.weight())
                .sum() / weightSum;

        out.add(new ModelTrainingReportDto.RegimeMetricDto(
                label,
                selected.size(),
                round4(predicted),
                round4(observed),
                round4(accuracy),
                round4(brier),
                null
        ));
    }

    private List<ModelTrainingReportDto.RegimeMetricDto> buildOperationalRegimes() {
        int take = clamp(liveLearningWindow * 2, 80, 600);
        List<PaperTradeLearningSample> rows =
                learningSampleRepository.findByLearningEligibleTrueAndStatusInOrderByEventOccurredAtDesc(
                List.of(PaperTradeBet.STATUS_WON, PaperTradeBet.STATUS_LOST),
                PageRequest.of(0, take)
        );
        if (rows.isEmpty()) {
            return List.of();
        }

        List<OperationalObservation> observations = new ArrayList<>(rows.size());
        for (PaperTradeLearningSample row : rows) {
            if (row == null || row.getStake() <= EPS) {
                continue;
            }
            observations.add(new OperationalObservation(
                    clampProbability(row.getModelProbability()),
                    PaperTradeBet.STATUS_WON.equals(row.getStatus()) ? 1 : 0,
                    row.getStake(),
                    row.getProfitLoss(),
                    row.isLiveAtPlacement(),
                    normalizePhase(placementPhase(row)),
                    clamp(row.getImpliedProbability(), 0.0, 1.0)
            ));
        }
        if (observations.isEmpty()) {
            return List.of();
        }

        List<ModelTrainingReportDto.RegimeMetricDto> out = new ArrayList<>();
        addOperationalRegime(out, "All Settled", observations, row -> true);
        addOperationalRegime(out, "Prematch", observations, row -> !row.liveAtPlacement());
        addOperationalRegime(out, "Live", observations, OperationalObservation::liveAtPlacement);
        addOperationalRegime(out, "Live Early", observations, row -> "LIVE_EARLY".equals(row.phase()));
        addOperationalRegime(out, "Live Mid", observations, row -> "LIVE_MID".equals(row.phase()));
        addOperationalRegime(out, "Live Late", observations, row -> "LIVE_LATE".equals(row.phase()));
        addOperationalRegime(out, "Favorite Side", observations, row -> row.impliedProbability() >= 0.55);
        addOperationalRegime(out, "Underdog Side", observations, row -> row.impliedProbability() <= 0.45);
        return out;
    }

    private void addOperationalRegime(List<ModelTrainingReportDto.RegimeMetricDto> out,
                                      String label,
                                      List<OperationalObservation> rows,
                                      Predicate<OperationalObservation> filter) {
        List<OperationalObservation> selected = rows.stream().filter(filter).toList();
        if (selected.isEmpty()) {
            return;
        }

        int count = selected.size();
        double predicted = selected.stream().mapToDouble(OperationalObservation::predicted).average().orElse(0.0);
        double observed = selected.stream().mapToInt(OperationalObservation::outcome).average().orElse(0.0);
        double accuracy = selected.stream()
                .mapToDouble(row -> ((row.predicted() >= 0.5) == (row.outcome() == 1)) ? 1.0 : 0.0)
                .average()
                .orElse(0.0);
        double brier = selected.stream()
                .mapToDouble(row -> Math.pow(row.predicted() - row.outcome(), 2))
                .average()
                .orElse(0.0);
        double totalStake = selected.stream().mapToDouble(OperationalObservation::stake).sum();
        double totalPnl = selected.stream().mapToDouble(OperationalObservation::profitLoss).sum();
        Double roiPct = totalStake <= EPS ? null : round2((totalPnl / totalStake) * 100.0);

        out.add(new ModelTrainingReportDto.RegimeMetricDto(
                label,
                count,
                round4(predicted),
                round4(observed),
                round4(accuracy),
                round4(brier),
                roiPct
        ));
    }

    private String normalizePhase(String phase) {
        if (!StringUtils.hasText(phase)) {
            return "UNKNOWN";
        }
        return phase.trim().toUpperCase(Locale.ROOT);
    }

    private TrainedModel persistCandidate(String jobId,
                                          String family,
                                          PredictModel model,
                                          LocalDate trainFrom,
                                          LocalDate trainTo,
                                          LocalDate valFrom,
                                          LocalDate valTo,
                                          CandidateMetrics metrics,
                                          Double lambda,
                                          Integer folds,
                                          boolean active,
                                          String notes,
                                          Map<String, Object> extraPayload) {
        registryRepository.deactivateFamily(family);
        String version = VERSION_TS.format(LocalDateTime.now()) + "-" + family + "-" + (System.nanoTime() % 100_000);

        PredictionModelRegistryEntry entry = new PredictionModelRegistryEntry();
        entry.setModelVersion(version);
        entry.setModelFamily(family);
        entry.setTrainingFrom(trainFrom);
        entry.setTrainingTo(trainTo);
        entry.setValidationFrom(valFrom);
        entry.setValidationTo(valTo);
        entry.setAccuracy(metrics.accuracy);
        entry.setLogLoss(metrics.logLoss);
        entry.setBrierScore(metrics.brierScore);
        entry.setCalibrationMethod(metrics.calibrationMethod);
        entry.setRegularizationLambda(lambda);
        entry.setFolds(folds);
        entry.setActive(active);
        entry.setNotes(notes + " | job=" + jobId);
        Map<String, Object> payload = new LinkedHashMap<>(model.toPayload());
        if (extraPayload != null && !extraPayload.isEmpty()) {
            payload.putAll(extraPayload);
        }
        entry.setPayloadJson(serializePayload(payload));
        entry = registryRepository.save(entry);

        return new TrainedModel(
                family,
                version,
                metrics.calibrationMethod,
                model,
                metrics.accuracy,
                metrics.logLoss,
                metrics.brierScore,
                entry.getCreatedAt()
        );
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"payload-serialization-failed\"}";
        }
    }

    private ModelRegistryEntryDto toRegistryDto(PredictionModelRegistryEntry e) {
        return new ModelRegistryEntryDto(
                e.getId(),
                e.getModelVersion(),
                e.getModelFamily(),
                e.getTrainingFrom(),
                e.getTrainingTo(),
                e.getValidationFrom(),
                e.getValidationTo(),
                e.getAccuracy(),
                e.getLogLoss(),
                e.getBrierScore(),
                e.getCalibrationMethod(),
                e.getRegularizationLambda(),
                e.getFolds(),
                e.isActive(),
                e.getNotes(),
                e.getCreatedAt()
        );
    }

    private ModelTrainingReportDto.CandidateMetricDto toCandidateDto(String family,
                                                                     TrainedModel trained,
                                                                     CandidateMetrics metrics,
                                                                     boolean active) {
        return new ModelTrainingReportDto.CandidateMetricDto(
                family,
                trained.version,
                metrics.accuracy,
                metrics.logLoss,
                metrics.brierScore,
                metrics.calibrationMethod,
                active
        );
    }

    private void ensureModelsReady() {
        TrainedModel logistic = activeModels.get(FAMILY_LOGISTIC);
        TrainedModel ensemble = activeModels.get(FAMILY_ENSEMBLE);
        if (logistic != null && ensemble != null) return;
        synchronized (trainLock) {
            logistic = activeModels.get(FAMILY_LOGISTIC);
            ensemble = activeModels.get(FAMILY_ENSEMBLE);
            if (logistic != null && ensemble != null) return;
            tryLoadModelsFromRegistry();
            logistic = activeModels.get(FAMILY_LOGISTIC);
            ensemble = activeModels.get(FAMILY_ENSEMBLE);
            if (logistic != null && ensemble != null) return;
            trainModels(null, null);
        }
    }

    private ModelSelection selectRequestedModel(String requested,
                                                TrainedModel logistic,
                                                TrainedModel gbtLike,
                                                TrainedModel rfLike,
                                                TrainedModel ensemble) {
        if (!StringUtils.hasText(requested)) {
            return new ModelSelection(FAMILY_ENSEMBLE, ensemble, false);
        }
        String trimmed = requested.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (FAMILY_BASELINE.equals(upper)) {
            return new ModelSelection(FAMILY_BASELINE, null, true);
        }
        if (isKnownFamily(upper)) {
            TrainedModel selected = switch (upper) {
                case FAMILY_LOGISTIC -> logistic;
                case FAMILY_GBT_LIKE -> gbtLike;
                case FAMILY_RF_LIKE -> rfLike;
                default -> ensemble;
            };
            return new ModelSelection(upper, selected, false);
        }
        TrainedModel byVersion = resolveModelByVersion(trimmed);
        if (byVersion != null) {
            return new ModelSelection(byVersion.family, byVersion, false);
        }
        return new ModelSelection(FAMILY_ENSEMBLE, ensemble, false);
    }

    private TrainedModel resolveModelByVersion(String modelVersion) {
        if (!StringUtils.hasText(modelVersion)) {
            return null;
        }
        String requested = modelVersion.trim();
        for (TrainedModel model : activeModels.values()) {
            if (model.version.equalsIgnoreCase(requested)) {
                return model;
            }
        }
        Optional<PredictionModelRegistryEntry> entry = registryRepository.findByModelVersion(requested);
        if (entry.isEmpty()) {
            return null;
        }
        Map<String, TrainedModel> knownByVersion = new HashMap<>();
        for (TrainedModel model : activeModels.values()) {
            knownByVersion.put(model.version, model);
        }
        TrainedModel restored = restoreModelFromRegistryEntry(entry.get(), knownByVersion);
        if (restored != null) {
            knownByVersion.put(restored.version, restored);
        }
        return restored;
    }

    private void tryLoadModelsFromRegistry() {
        Map<String, TrainedModel> loadedByFamily = new HashMap<>();
        Map<String, TrainedModel> loadedByVersion = new HashMap<>();
        TrainedModel activeChampion = null;

        for (String family : List.of(FAMILY_LOGISTIC, FAMILY_GBT_LIKE, FAMILY_RF_LIKE)) {
            loadLatestModelForFamily(family, loadedByVersion).ifPresent(model -> {
                loadedByFamily.put(family, model);
                loadedByVersion.put(model.version, model);
            });
            List<PredictionModelRegistryEntry> activeRows =
                    registryRepository.findActiveByFamily(family, PageRequest.of(0, 1));
            if (!activeRows.isEmpty()) {
                TrainedModel active = restoreModelFromRegistryEntry(activeRows.get(0), loadedByVersion);
                if (active != null) {
                    activeChampion = active;
                    loadedByFamily.put(family, active);
                    loadedByVersion.put(active.version, active);
                }
            }
        }
        final TrainedModel nonEnsembleChampion = activeChampion;
        loadLatestModelForFamily(FAMILY_ENSEMBLE, loadedByVersion).ifPresent(model -> {
            loadedByFamily.put(FAMILY_ENSEMBLE, model);
            loadedByVersion.put(model.version, model);
        });
        List<PredictionModelRegistryEntry> activeEnsembleRows =
                registryRepository.findActiveByFamily(FAMILY_ENSEMBLE, PageRequest.of(0, 1));
        if (!activeEnsembleRows.isEmpty()) {
            TrainedModel activeEnsemble =
                    restoreModelFromRegistryEntry(activeEnsembleRows.get(0), loadedByVersion);
            if (activeEnsemble != null) {
                loadedByFamily.put(FAMILY_ENSEMBLE, activeEnsemble);
            }
        } else if (nonEnsembleChampion != null) {
            loadedByFamily.put(FAMILY_ENSEMBLE, nonEnsembleChampion);
        }

        if (!loadedByFamily.isEmpty()) {
            activeModels.clear();
            activeModels.putAll(loadedByFamily);
        }
    }

    private Optional<TrainedModel> loadLatestModelForFamily(String family, Map<String, TrainedModel> knownByVersion) {
        List<PredictionModelRegistryEntry> activeRows = registryRepository.findActiveByFamily(family, PageRequest.of(0, 1));
        PredictionModelRegistryEntry selected = null;
        if (!activeRows.isEmpty()) {
            selected = activeRows.get(0);
        } else {
            List<PredictionModelRegistryEntry> recentRows = registryRepository.findRecentByFamily(family, PageRequest.of(0, 1));
            if (!recentRows.isEmpty()) {
                selected = recentRows.get(0);
            }
        }
        if (selected == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(restoreModelFromRegistryEntry(selected, knownByVersion));
    }

    private TrainedModel restoreModelFromRegistryEntry(PredictionModelRegistryEntry entry,
                                                       Map<String, TrainedModel> knownByVersion) {
        if (entry == null || !StringUtils.hasText(entry.getPayloadJson())) {
            return null;
        }
        PredictModel model = restorePredictModel(entry, knownByVersion);
        if (model == null) {
            return null;
        }
        return new TrainedModel(
                entry.getModelFamily(),
                entry.getModelVersion(),
                StringUtils.hasText(entry.getCalibrationMethod()) ? entry.getCalibrationMethod() : "NONE",
                model,
                safeMetric(entry.getAccuracy()),
                safeMetric(entry.getLogLoss()),
                safeMetric(entry.getBrierScore()),
                entry.getCreatedAt() == null ? LocalDateTime.now() : entry.getCreatedAt()
        );
    }

    private PredictModel restorePredictModel(PredictionModelRegistryEntry entry,
                                             Map<String, TrainedModel> knownByVersion) {
        try {
            JsonNode payload = objectMapper.readTree(entry.getPayloadJson());
            String payloadType = payload.path("type").asText(entry.getModelFamily()).toUpperCase(Locale.ROOT);
            return switch (payloadType) {
                case FAMILY_LOGISTIC, FAMILY_GBT_LIKE -> restoreLogisticLike(payload);
                case FAMILY_RF_LIKE -> restoreRandomForestLike(payload);
                case FAMILY_ENSEMBLE -> restoreEnsemble(payload, knownByVersion);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private PredictModel restoreLogisticLike(JsonNode payload) {
        double[] means = readDoubleArray(payload.path("means"));
        double[] stds = readDoubleArray(payload.path("stds"));
        double[] weights = readDoubleArray(payload.path("weights"));
        if (means.length == 0 || means.length != stds.length || weights.length != means.length + 1) {
            return null;
        }
        String[] featureNames = readStringArray(payload.path("featureNames"), means.length);
        if (featureNames.length != means.length) {
            return null;
        }

        LogisticModel model = new LogisticModel(
                featureNames,
                transformForFeatureCount(featureNames.length),
                means,
                stds,
                weights,
                payload.path("lambda").asDouble(0.0)
        );
        if (payload.hasNonNull("calibratorA") && payload.hasNonNull("calibratorB")) {
            model.calibrator = new PlattCalibrator(
                    payload.path("calibratorA").asDouble(),
                    payload.path("calibratorB").asDouble()
            );
        }
        return model;
    }

    private PredictModel restoreRandomForestLike(JsonNode payload) {
        String[] featureNames = readStringArray(payload.path("featureNames"), -1);
        double[] featureMeans = readDoubleArray(payload.path("featureMeans"));
        if (featureNames.length == 0 || featureMeans.length != featureNames.length) {
            return null;
        }

        List<Stump> stumps = new ArrayList<>();
        JsonNode trees = payload.path("trees");
        if (trees.isArray()) {
            for (JsonNode tree : trees) {
                int featureIndex = tree.path("featureIndex").asInt(-1);
                if (featureIndex < 0 || featureIndex >= featureNames.length) {
                    continue;
                }
                stumps.add(new Stump(
                        featureIndex,
                        tree.path("threshold").asDouble(),
                        clamp01(tree.path("leftProbability").asDouble(0.5)),
                        clamp01(tree.path("rightProbability").asDouble(0.5))
                ));
            }
        }
        if (stumps.isEmpty()) {
            return null;
        }
        return new RandomForestLikeModel(featureNames, transformForFeatureCount(featureNames.length), featureMeans, stumps);
    }

    private PredictModel restoreEnsemble(JsonNode payload, Map<String, TrainedModel> knownByVersion) {
        PredictModel logistic = resolveEnsembleComponent(
                payload.path("logisticVersion").asText(""),
                payload.path("logisticFamily").asText(FAMILY_LOGISTIC),
                knownByVersion
        );
        if (logistic == null) {
            return null;
        }

        double advancedWeight = clamp(payload.path("advancedWeight").asDouble(0.0), 0.0, 1.0);
        PredictModel advanced = resolveEnsembleComponent(
                payload.path("advancedVersion").asText(""),
                payload.path("advancedFamily").asText(FAMILY_RF_LIKE),
                knownByVersion
        );
        if (advanced == null) {
            advanced = logistic;
            advancedWeight = 0.0;
        }
        double logisticWeight = clamp(payload.path("logisticWeight").asDouble(1.0), 0.0, 1.0);
        if (logisticWeight + advancedWeight <= EPS) {
            logisticWeight = 1.0;
            advancedWeight = 0.0;
        } else {
            double sum = logisticWeight + advancedWeight;
            logisticWeight /= sum;
            advancedWeight /= sum;
        }
        return new EnsembleModel(logistic, advanced, logisticWeight, advancedWeight);
    }

    private PredictModel resolveEnsembleComponent(String version,
                                                  String family,
                                                  Map<String, TrainedModel> knownByVersion) {
        if (StringUtils.hasText(version)) {
            TrainedModel known = knownByVersion.get(version);
            if (known != null) {
                return known.model;
            }
            Optional<PredictionModelRegistryEntry> byVersion = registryRepository.findByModelVersion(version);
            if (byVersion.isPresent()) {
                TrainedModel restored = restoreModelFromRegistryEntry(byVersion.get(), knownByVersion);
                if (restored != null) {
                    knownByVersion.put(restored.version, restored);
                    return restored.model;
                }
            }
        }

        String normalizedFamily = normalizeFamily(family);
        TrainedModel active = activeModels.get(normalizedFamily);
        if (active != null) {
            return active.model;
        }
        Optional<TrainedModel> recent = loadLatestModelForFamily(normalizedFamily, knownByVersion);
        if (recent.isPresent()) {
            knownByVersion.put(recent.get().version, recent.get());
            return recent.get().model;
        }
        return null;
    }

    private Function<double[], double[]> transformForFeatureCount(int featureCount) {
        FeatureSet gbt = FeatureSet.gbtLike();
        if (featureCount == gbt.featureNames.length) {
            return gbt.transform;
        }
        if (featureCount == 18) {
            return FeatureSet.legacyGbtTransform();
        }
        return x -> Arrays.copyOf(x, Math.min(featureCount, x.length));
    }

    private String[] readStringArray(JsonNode node, int expectedLength) {
        if (!node.isArray()) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        for (JsonNode child : node) {
            out.add(child.asText(""));
        }
        if (expectedLength > 0 && out.size() != expectedLength) {
            return new String[0];
        }
        return out.toArray(new String[0]);
    }

    private double[] readDoubleArray(JsonNode node) {
        if (!node.isArray()) {
            return new double[0];
        }
        double[] out = new double[node.size()];
        int idx = 0;
        for (JsonNode child : node) {
            out[idx++] = child.asDouble();
        }
        return out;
    }

    private static double safeMetric(Double value) {
        return value == null ? 0.0 : value;
    }

    private boolean isKnownFamily(String family) {
        return FAMILY_LOGISTIC.equals(family)
                || FAMILY_GBT_LIKE.equals(family)
                || FAMILY_RF_LIKE.equals(family)
                || FAMILY_ENSEMBLE.equals(family);
    }

    private List<Double> parseLambdas() {
        if (!StringUtils.hasText(lambdaCandidatesRaw)) {
            return List.of(0.0, 0.0005, 0.001, 0.003, 0.01);
        }
        List<Double> out = new ArrayList<>();
        for (String token : lambdaCandidatesRaw.split(",")) {
            String t = token.trim();
            if (t.isBlank()) continue;
            try {
                double value = Double.parseDouble(t);
                if (value >= 0.0) out.add(value);
            } catch (NumberFormatException ignore) {
            }
        }
        if (out.isEmpty()) {
            return List.of(0.0, 0.0005, 0.001, 0.003, 0.01);
        }
        Collections.sort(out);
        return out;
    }

    private String normalizeFamily(String raw) {
        if (!StringUtils.hasText(raw)) return FAMILY_ENSEMBLE;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.equals(FAMILY_BASELINE)) return FAMILY_BASELINE;
        if (upper.equals(FAMILY_LOGISTIC)) return FAMILY_LOGISTIC;
        if (upper.equals(FAMILY_GBT_LIKE)) return FAMILY_GBT_LIKE;
        if (upper.equals(FAMILY_RF_LIKE)) return FAMILY_RF_LIKE;
        if (upper.equals(FAMILY_ENSEMBLE)) return FAMILY_ENSEMBLE;
        return FAMILY_ENSEMBLE;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static double sigmoid(double z) {
        if (z >= 0) {
            double ez = Math.exp(-z);
            return 1.0 / (1.0 + ez);
        }
        double ez = Math.exp(z);
        return ez / (1.0 + ez);
    }

    /**
     * Neutralizes untrained/constant columns and clips out-of-distribution
     * z-scores. A near-zero training standard deviation must never amplify a
     * live feature into a double-digit logit contribution.
     */
    private static double standardized(double value, double mean, double std) {
        if (!Double.isFinite(value) || !Double.isFinite(mean) || !Double.isFinite(std)
                || Math.abs(std) < MIN_FEATURE_STD) {
            return 0.0;
        }
        return clamp((value - mean) / Math.abs(std),
                -MAX_STANDARDIZED_FEATURE,
                MAX_STANDARDIZED_FEATURE);
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static double clampProbability(double value) {
        return clamp(value, 1e-6, 1.0 - 1e-6);
    }

    private static double clamp(double value, double lo, double hi) {
        if (value < lo) return lo;
        if (value > hi) return hi;
        return value;
    }

    private static int clamp(int value, int lo, int hi) {
        if (value < lo) return lo;
        return Math.min(value, hi);
    }

    public record PredictionSnapshot(String modelFamily,
                                     String modelVersion,
                                     String calibrationMethod,
                                     double player1Probability,
                                     double player2Probability,
                                     double player1ConfidenceLow,
                                     double player1ConfidenceHigh,
                                     List<MatchupAnalysisDto.FeatureContributionDto> featureContributions,
                                     MatchupFeatureVectorDto featureVector,
                                     double baselineProbability,
                                     double logisticProbability,
                                     double glickoProbability,
                                     double gbtLikeProbability,
                                     double rfLikeProbability,
                                     double ensembleProbability) {
    }

    private record TrainingSample(LocalDate matchDate, double[] baseFeatures, int label, double sampleWeight) {
    }

    private record ValidationObservation(double predicted, int label, double weight) {
    }

    private record OperationalObservation(double predicted,
                                          int outcome,
                                          double stake,
                                          double profitLoss,
                                          boolean liveAtPlacement,
                                          String phase,
                                          double impliedProbability) {
    }

    private record ModelSelection(String family, TrainedModel model, boolean baseline) {
    }

    private record ConsensusProfile(double mean, double disagreement) {
    }

    private record LiveLearningCalibration(int sampleSize,
                                           double reliability,
                                           double confidenceScale,
                                           double ciBoost,
                                           LocalDateTime computedAt) {
        static LiveLearningCalibration neutral() {
            return new LiveLearningCalibration(0, 0.0, 1.0, 0.0, LocalDateTime.now());
        }
    }

    private record LiveLearningCalibrationCache(LiveLearningCalibration profile, LocalDateTime createdAt) {
    }

    public record AdaptiveRegimeTuning(String label,
                                       double reliability,
                                       double confidenceScale,
                                       double ciBoost,
                                       double calibrationError,
                                       double roiSignal) {
        static AdaptiveRegimeTuning neutral(String label) {
            return new AdaptiveRegimeTuning(label, 0.0, 1.0, 0.0, 0.0, 0.0);
        }
    }

    private record AdaptiveRegimeProfile(String label,
                                         int sampleSize,
                                         double reliability,
                                         double calibrationError,
                                         double roiSignal,
                                         double confidenceScale,
                                         double ciBoost,
                                         boolean live,
                                         String phase,
                                         String sideType) {
    }

    private record AdaptiveRegimeProfileCache(List<AdaptiveRegimeProfile> profiles, LocalDateTime createdAt) {
    }

    private static class TrainedModel {
        private final String family;
        private final String version;
        private final String calibrationMethod;
        private final PredictModel model;
        private final double accuracy;
        private final double logLoss;
        private final double brierScore;
        private final LocalDateTime trainedAt;

        private TrainedModel(String family,
                             String version,
                             String calibrationMethod,
                             PredictModel model,
                             double accuracy,
                             double logLoss,
                             double brierScore,
                             LocalDateTime trainedAt) {
            this.family = family;
            this.version = version;
            this.calibrationMethod = calibrationMethod;
            this.model = model;
            this.accuracy = accuracy;
            this.logLoss = logLoss;
            this.brierScore = brierScore;
            this.trainedAt = trainedAt;
        }

        double predict(double[] baseFeatures) {
            return model.predict(baseFeatures);
        }

        List<MatchupAnalysisDto.FeatureContributionDto> contributions(double[] baseFeatures) {
            return model.contributions(baseFeatures);
        }
    }

    private static class CandidateMetrics {
        private final PredictModel model;
        private final String family;
        private double accuracy;
        private double logLoss;
        private double brierScore;
        private String calibrationMethod;

        private CandidateMetrics(PredictModel model,
                                 String family,
                                 double accuracy,
                                 double logLoss,
                                 double brierScore,
                                 String calibrationMethod) {
            this.model = model;
            this.family = family;
            this.accuracy = accuracy;
            this.logLoss = logLoss;
            this.brierScore = brierScore;
            this.calibrationMethod = calibrationMethod;
        }
    }

    private static class FeatureSet {
        private final String[] featureNames;
        private final Function<double[], double[]> transform;

        private FeatureSet(String[] featureNames, Function<double[], double[]> transform) {
            this.featureNames = featureNames;
            this.transform = transform;
        }

        static FeatureSet base() {
            return new FeatureSet(BASE_FEATURE_NAMES, x -> Arrays.copyOf(x, BASE_FEATURE_NAMES.length));
        }

        static FeatureSet gbtLike() {
            String[] expandedNames = new String[]{
                    BASE_FEATURE_NAMES[0], BASE_FEATURE_NAMES[1], BASE_FEATURE_NAMES[2], BASE_FEATURE_NAMES[3],
                    BASE_FEATURE_NAMES[4], BASE_FEATURE_NAMES[5], BASE_FEATURE_NAMES[6], BASE_FEATURE_NAMES[7],
                    BASE_FEATURE_NAMES[8], BASE_FEATURE_NAMES[9], BASE_FEATURE_NAMES[10], BASE_FEATURE_NAMES[11],
                    BASE_FEATURE_NAMES[12], BASE_FEATURE_NAMES[13], BASE_FEATURE_NAMES[14], BASE_FEATURE_NAMES[15],
                    "Recent Form Delta^2", "Glicko Probability Delta^2", "Rating Delta^2",
                    "H2H × Recent Form Delta", "Recent Form × OppAdj Delta", "Elo × Glicko Probability Delta"
            };
            return new FeatureSet(expandedNames, x -> new double[]{
                    x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8], x[9], x[10], x[11],
                    x[12], x[13], x[14], x[15],
                    x[1] * x[1], x[5] * x[5], x[6] * x[6],
                    x[0] * x[1], x[1] * x[2], x[4] * x[5]
            });
        }

        static Function<double[], double[]> legacyGbtTransform() {
            return x -> new double[]{
                    x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8], x[9], x[10], x[11],
                    x[1] * x[1], x[5] * x[5], x[6] * x[6],
                    x[0] * x[1], x[1] * x[2], x[4] * x[5]
            };
        }
    }

    private interface PredictModel {
        String family();

        double predict(double[] baseFeatures);

        List<MatchupAnalysisDto.FeatureContributionDto> contributions(double[] baseFeatures);

        Map<String, Object> toPayload();
    }

    private static class LogisticModel implements PredictModel {
        private final String[] featureNames;
        private final Function<double[], double[]> transform;
        private final double[] means;
        private final double[] stds;
        private final double[] weights;
        private final double lambda;
        private PlattCalibrator calibrator;

        private LogisticModel(String[] featureNames,
                              Function<double[], double[]> transform,
                              double[] means,
                              double[] stds,
                              double[] weights,
                              double lambda) {
            this.featureNames = featureNames;
            this.transform = transform;
            this.means = means;
            this.stds = stds;
            this.weights = weights;
            this.lambda = lambda;
        }

        @Override
        public String family() {
            return featureNames.length > BASE_FEATURE_NAMES.length ? FAMILY_GBT_LIKE : FAMILY_LOGISTIC;
        }

        @Override
        public double predict(double[] baseFeatures) {
            double z = rawScore(baseFeatures);
            double probability = sigmoid(z);
            if (calibrator != null) {
                probability = calibrator.apply(z);
            }
            return clamp01(probability);
        }

        private LogisticModel withCalibrator(PlattCalibrator calibrator) {
            LogisticModel clone = new LogisticModel(featureNames, transform, means, stds, weights, lambda);
            clone.calibrator = calibrator;
            return clone;
        }

        private double rawScore(double[] baseFeatures) {
            double[] x = transform.apply(baseFeatures);
            double z = weights[0];
            for (int j = 0; j < x.length; j++) {
                z += weights[j + 1] * standardized(x[j], means[j], stds[j]);
            }
            return z;
        }

        @Override
        public List<MatchupAnalysisDto.FeatureContributionDto> contributions(double[] baseFeatures) {
            double[] x = transform.apply(baseFeatures);
            List<MatchupAnalysisDto.FeatureContributionDto> out = new ArrayList<>();
            for (int j = 0; j < x.length; j++) {
                double contribution = weights[j + 1] * standardized(x[j], means[j], stds[j]);
                out.add(new MatchupAnalysisDto.FeatureContributionDto(featureNames[j], round4(contribution)));
            }
            out.sort((a, b) -> Double.compare(Math.abs(b.contribution()), Math.abs(a.contribution())));
            if (out.size() > 8) {
                return out.subList(0, 8);
            }
            return out;
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", family());
            payload.put("featureNames", featureNames);
            payload.put("means", means);
            payload.put("stds", stds);
            payload.put("weights", weights);
            payload.put("lambda", lambda);
            if (calibrator != null) {
                payload.put("calibratorA", calibrator.a);
                payload.put("calibratorB", calibrator.b);
            }
            return payload;
        }
    }

    private static class RandomForestLikeModel implements PredictModel {
        private final String[] featureNames;
        private final Function<double[], double[]> transform;
        private final double[] featureMeans;
        private final List<Stump> stumps;

        private RandomForestLikeModel(String[] featureNames,
                                      Function<double[], double[]> transform,
                                      double[] featureMeans,
                                      List<Stump> stumps) {
            this.featureNames = featureNames;
            this.transform = transform;
            this.featureMeans = featureMeans;
            this.stumps = stumps;
        }

        @Override
        public String family() {
            return FAMILY_RF_LIKE;
        }

        @Override
        public double predict(double[] baseFeatures) {
            double[] x = transform.apply(baseFeatures);
            if (stumps.isEmpty()) return 0.5;
            double sum = 0.0;
            for (Stump stump : stumps) {
                sum += stump.predict(x);
            }
            return clamp01(sum / stumps.size());
        }

        @Override
        public List<MatchupAnalysisDto.FeatureContributionDto> contributions(double[] baseFeatures) {
            double base = predict(baseFeatures);
            double[] x = transform.apply(baseFeatures);
            List<MatchupAnalysisDto.FeatureContributionDto> out = new ArrayList<>();
            for (int i = 0; i < featureNames.length; i++) {
                double[] pert = Arrays.copyOf(x, x.length);
                pert[i] = featureMeans[i];
                double pertProb = predictFromTransformed(pert);
                out.add(new MatchupAnalysisDto.FeatureContributionDto(featureNames[i], round4(base - pertProb)));
            }
            out.sort((a, b) -> Double.compare(Math.abs(b.contribution()), Math.abs(a.contribution())));
            if (out.size() > 8) {
                return out.subList(0, 8);
            }
            return out;
        }

        private double predictFromTransformed(double[] x) {
            if (stumps.isEmpty()) return 0.5;
            double sum = 0.0;
            for (Stump stump : stumps) {
                sum += stump.predict(x);
            }
            return clamp01(sum / stumps.size());
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", FAMILY_RF_LIKE);
            payload.put("featureNames", featureNames);
            payload.put("featureMeans", featureMeans);
            payload.put("trees", stumps);
            return payload;
        }
    }

    private static class EnsembleModel implements PredictModel {
        private final PredictModel logistic;
        private final PredictModel advanced;
        private final double logisticWeight;
        private final double advancedWeight;

        private EnsembleModel(PredictModel logistic,
                              PredictModel advanced,
                              double logisticWeight,
                              double advancedWeight) {
            this.logistic = logistic;
            this.advanced = advanced;
            this.logisticWeight = logisticWeight;
            this.advancedWeight = advancedWeight;
        }

        @Override
        public String family() {
            return FAMILY_ENSEMBLE;
        }

        @Override
        public double predict(double[] baseFeatures) {
            double pLog = logistic.predict(baseFeatures);
            double pAdv = advancedWeight <= 0.0 ? pLog : advanced.predict(baseFeatures);
            return clamp01((logisticWeight * pLog) + (advancedWeight * pAdv));
        }

        @Override
        public List<MatchupAnalysisDto.FeatureContributionDto> contributions(double[] baseFeatures) {
            List<MatchupAnalysisDto.FeatureContributionDto> combined = new ArrayList<>();
            Map<String, Double> merged = new LinkedHashMap<>();
            for (MatchupAnalysisDto.FeatureContributionDto c : logistic.contributions(baseFeatures)) {
                merged.merge(c.feature(), c.contribution() * logisticWeight, Double::sum);
            }
            if (advancedWeight > 0.0) {
                for (MatchupAnalysisDto.FeatureContributionDto c : advanced.contributions(baseFeatures)) {
                    merged.merge(c.feature(), c.contribution() * advancedWeight, Double::sum);
                }
            }
            for (Map.Entry<String, Double> e : merged.entrySet()) {
                combined.add(new MatchupAnalysisDto.FeatureContributionDto(e.getKey(), round4(e.getValue())));
            }
            combined.sort((a, b) -> Double.compare(Math.abs(b.contribution()), Math.abs(a.contribution())));
            if (combined.size() > 8) {
                return combined.subList(0, 8);
            }
            return combined;
        }

        @Override
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", FAMILY_ENSEMBLE);
            payload.put("logisticWeight", logisticWeight);
            payload.put("advancedWeight", advancedWeight);
            payload.put("logisticFamily", logistic.family());
            payload.put("advancedFamily", advanced.family());
            return payload;
        }
    }

    private static class Stump {
        private final int featureIndex;
        private final double threshold;
        private final double leftProbability;
        private final double rightProbability;

        private Stump(int featureIndex, double threshold, double leftProbability, double rightProbability) {
            this.featureIndex = featureIndex;
            this.threshold = threshold;
            this.leftProbability = leftProbability;
            this.rightProbability = rightProbability;
        }

        private double predict(double[] x) {
            return x[featureIndex] <= threshold ? leftProbability : rightProbability;
        }
    }

    private static class PlattCalibrator {
        private final double a;
        private final double b;

        private PlattCalibrator(double a, double b) {
            this.a = a;
            this.b = b;
        }

        private double apply(double logit) {
            return clamp01(sigmoid(a * logit + b));
        }
    }
}
