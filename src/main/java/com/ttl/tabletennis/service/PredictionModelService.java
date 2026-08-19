package com.ttl.tabletennis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.domain.PredictionModelRegistryEntry;
import com.ttl.tabletennis.dto.AdaptiveRegimeProfileDto;
import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.MatchupFeatureVectorDto;
import com.ttl.tabletennis.dto.ModelRegistryEntryDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.PredictionModelRegistryRepository;
import com.ttl.tabletennis.util.NameUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
    private static final LocalDate MIN_REASONABLE_MATCH_DATE = LocalDate.of(2015, 1, 1);
    /**
     * R2.1 uses only antisymmetric inputs. Every feature changes sign when
     * player order is reversed, which lets the complete predictor enforce
     * P(A,B) = 1 - P(B,A) as a hard runtime invariant.
     */
    private static final String MODEL_SCHEMA_VERSION = "java-prematch-symmetric-v3";

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
            "TrueSkill2 Probability Delta",
            "Weng-Lin Probability Delta",
            "Rater Ensemble Delta",
            "Rater Consensus Signal"
    };

    private final MatchRepository matchRepository;
    private final OddsSnapshotRepository oddsSnapshotRepository;
    private final FeatureService featureService;
    private final PaperTradeLearningSampleRepository learningSampleRepository;
    private final PredictionModelRegistryRepository registryRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, TrainedModel> activeModels = new ConcurrentHashMap<>();
    private final AtomicReference<ModelTrainingReportDto> lastTrainingReport = new AtomicReference<>();
    private final AtomicReference<LiveLearningCalibrationCache> liveLearningCache = new AtomicReference<>();
    private final AtomicReference<AdaptiveRegimeProfileCache> adaptiveRegimeCache = new AtomicReference<>();
    private final Object trainLock = new Object();

    @PersistenceContext
    private EntityManager entityManager;

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

    @Value("${ttl.prediction.temporalGapDays:1}")
    private int temporalGapDays;

    @Value("${ttl.prediction.maxTrainingDates:24}")
    private int maxTrainingDates;

    @Value("${ttl.prediction.maxSamplesPerDate:120}")
    private int maxSamplesPerDate;

    @Value("${ttl.prediction.requireMarketBenchmark:true}")
    private boolean requireMarketBenchmark;

    @Value("${ttl.prediction.marketBenchmark.minSamples:500}")
    private int marketBenchmarkMinSamples;

    @Value("${ttl.prediction.marketBenchmark.minCoverage:0.99}")
    private double marketBenchmarkMinCoverage;

    @Value("${ttl.prediction.promotion.maxEce:0.03}")
    private double promotionMaxEce;

    @Value("${ttl.prediction.promotion.maxSideAccuracyGap:0.05}")
    private double promotionMaxSideAccuracyGap;

    @Value("${ttl.prediction.promotion.minFutureOutcomes:500}")
    private int promotionMinFutureOutcomes;

    @Value("${ttl.prediction.promotion.minFutureDays:5}")
    private int promotionMinFutureDays;

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

    @Value("${ttl.prediction.calibrationTemperatureGrid:1.25,1.40,1.55,1.75}")
    private String calibrationTemperatureGrid;

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

    @Value("${ttl.prediction.formFeatureCap:0.30}")
    private double formFeatureCap;

    @Value("${ttl.prediction.scheduleFeatureCap:0.25}")
    private double scheduleFeatureCap;

    @Value("${ttl.prediction.wengLinFeatureEnabled:false}")
    private boolean wengLinFeatureEnabled;

    @Value("${ttl.prediction.raterConsensusFeatureEnabled:false}")
    private boolean raterConsensusFeatureEnabled;

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

    @Value("${ttl.prediction.releaseName:accuracy-guardrails-r1}")
    private String releaseName;

    public PredictionModelService(MatchRepository matchRepository,
                                  FeatureService featureService,
                                  PaperTradeLearningSampleRepository learningSampleRepository,
                                  PredictionModelRegistryRepository registryRepository,
                                  OddsSnapshotRepository oddsSnapshotRepository,
                                  ObjectMapper objectMapper) {
        this.matchRepository = matchRepository;
        this.featureService = featureService;
        this.learningSampleRepository = learningSampleRepository;
        this.registryRepository = registryRepository;
        this.oddsSnapshotRepository = oddsSnapshotRepository;
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
                    ensembleProbability,
                    logistic != null,
                    gbtLike != null,
                    rfLike != null
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
                    baselineProbability,
                    false,
                    false,
                    false
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
                ensembleProbability,
                logistic != null,
                gbtLike != null,
                rfLike != null
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

    public String featureSchemaChecksum() {
        return expectedFeatureSchemaHash();
    }

    public ModelTrainingReportDto latestTrainingReport() {
        ModelTrainingReportDto cached = lastTrainingReport.get();
        if (cached != null) {
            return cached;
        }
        List<PredictionModelRegistryEntry> rows =
                registryRepository.findRecentByFamily(null, PageRequest.of(0, 1));
        if (rows.isEmpty() || !StringUtils.hasText(rows.get(0).getPayloadJson())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(rows.get(0).getPayloadJson()).path("trainingReport");
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            ModelTrainingReportDto restored = objectMapper.treeToValue(node, ModelTrainingReportDto.class);
            lastTrainingReport.compareAndSet(null, restored);
            return lastTrainingReport.get();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Returns true only when the exact artifact is the independently gated
     * product champion. Candidate families and historical versions remain
     * inspectable, but cannot become actionable merely by selecting them.
     */
    public boolean isPromotedModel(String family, String version) {
        if (!StringUtils.hasText(version) || FAMILY_BASELINE.equalsIgnoreCase(family)) {
            return false;
        }
        return registryRepository.findByModelVersion(version.trim())
                .map(PredictionModelRegistryEntry::isActive)
                .orElse(false);
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
            // Feature construction executes many read queries while the full
            // historical match set is attached to this persistence context.
            // AUTO mode makes Hibernate dirty-check every attached match before
            // every query, turning training into quadratic work. Registry rows
            // are the only writes in this transaction, so flushing once at
            // commit is both correct and dramatically faster.
            entityManager.setFlushMode(FlushModeType.COMMIT);
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

            TemporalSplit split = temporalSplit(samples);
            List<TrainingSample> train = split.train();
            List<TrainingSample> calibration = split.calibration();
            List<TrainingSample> test = split.test();
            List<Double> lambdas = parseLambdas();

            CrossValidationSelection baseCv = selectBestLambda(train, lambdas, FeatureSet.base());
            double bestLambda = baseCv.lambda();
            LogisticModel logistic = trainLogisticModel(train, bestLambda, FeatureSet.base(), trainEpochs, learningRate);
            CandidateMetrics logisticCalibration = evaluateCandidate(logistic, calibration);
            maybeCalibrate(logistic, calibration, logisticCalibration);
            logisticCalibration = evaluateCandidate(logistic, calibration)
                    .withCalibrationMethod(logistic.calibrationMethod());

            CrossValidationSelection gbtCv = selectBestLambda(train, lambdas, FeatureSet.gbtLike());
            double gbtLambda = gbtCv.lambda();
            LogisticModel gbtLike = trainLogisticModel(train, gbtLambda, FeatureSet.gbtLike(), trainEpochs, learningRate);
            CandidateMetrics gbtCalibration = evaluateCandidate(gbtLike, calibration);
            maybeCalibrate(gbtLike, calibration, gbtCalibration);
            gbtCalibration = evaluateCandidate(gbtLike, calibration)
                    .withCalibrationMethod(gbtLike.calibrationMethod());

            RandomForestLikeModel rfLike = trainRandomForest(train, FeatureSet.base(), Math.max(10, rfTrees));
            CandidateMetrics rfCalibration = evaluateCandidate(rfLike, calibration);

            CandidateMetrics bestAdvanced = gbtCalibration.brierScore <= rfCalibration.brierScore
                    ? gbtCalibration : rfCalibration;
            PredictModel bestAdvancedModel = FAMILY_GBT_LIKE.equals(bestAdvanced.family) ? gbtLike : rfLike;

            EnsembleModel ensemble = buildEnsemble(logistic, logisticCalibration, bestAdvancedModel, bestAdvanced);
            CandidateMetrics ensembleCalibration = evaluateCandidate(ensemble, calibration);

            List<CandidateMetrics> ranked = new ArrayList<>(List.of(
                    logisticCalibration, gbtCalibration, rfCalibration, ensembleCalibration));
            ranked.sort(Comparator.comparingDouble(c -> c.brierScore));
            CandidateMetrics selectedOnCalibration = ranked.get(0);

            CandidateMetrics logisticMetrics = evaluateCandidate(logistic, test)
                    .withCalibrationMethod(logistic.calibrationMethod());
            CandidateMetrics gbtMetrics = evaluateCandidate(gbtLike, test)
                    .withCalibrationMethod(gbtLike.calibrationMethod());
            CandidateMetrics rfMetrics = evaluateCandidate(rfLike, test);
            CandidateMetrics ensembleMetrics = evaluateCandidate(ensemble, test);

            Map<String, CandidateMetrics> testByFamily = Map.of(
                    FAMILY_LOGISTIC, logisticMetrics,
                    FAMILY_GBT_LIKE, gbtMetrics,
                    FAMILY_RF_LIKE, rfMetrics,
                    FAMILY_ENSEMBLE, ensembleMetrics
            );
            CandidateMetrics champion = testByFamily.get(selectedOnCalibration.family);
            BenchmarkMetrics benchmarks = evaluateBenchmarks(train, test);
            MarketBenchmarkMetrics marketBenchmark = evaluateMarketBenchmark(champion.model, test);
            boolean timeSliceStable = stableAcrossTimeSlices(champion.model, train, test);
            BootstrapStability bootstrap = bootstrapStability(champion.model, train, test);
            SwapInvariantAudit swapAudit = auditSwapInvariance(champion.model, 10_000);
            boolean stable = timeSliceStable && bootstrap.passed() && swapAudit.passed();
            boolean beatsKnownBaselines = champion.brierScore + minLiftForAdvanced < benchmarks.constantBrier()
                    && champion.brierScore + minLiftForAdvanced < benchmarks.eloBrier()
                    && champion.brierScore + minLiftForAdvanced < benchmarks.recentFormBrier();
            int untouchedOutcomes = originalOrientationSamples(test).size();
            long untouchedDays = originalOrientationSamples(test).stream()
                    .map(TrainingSample::matchDate)
                    .distinct()
                    .count();
            boolean futureSampleGatePassed = untouchedOutcomes >= Math.max(100, promotionMinFutureOutcomes)
                    && untouchedDays >= Math.max(3, promotionMinFutureDays);
            boolean marketBenchmarkAvailable = marketBenchmark.coveredSamples() >= Math.max(50, marketBenchmarkMinSamples)
                    && marketBenchmark.coverage() >= clamp(marketBenchmarkMinCoverage, 0.90, 1.0)
                    && marketBenchmark.asOfViolations() == 0;
            boolean beatsMarket = marketBenchmarkAvailable
                    && champion.brierScore <= marketBenchmark.brierScore() + EPS
                    && champion.logLoss <= marketBenchmark.logLoss() + EPS;
            double expectedCalibrationError = expectedCalibrationError(champion.model, test, 10);
            boolean calibrationGatePassed = expectedCalibrationError <= clamp(promotionMaxEce, 0.01, 0.10);
            SideAccuracyAudit sideAccuracy = sideAccuracyAudit(champion.model, test);
            boolean sideAccuracyGatePassed = sideAccuracy.marketControlledGap()
                    <= clamp(promotionMaxSideAccuracyGap, 0.01, 0.20);
            boolean groupedCvGatePassed = baseCv.usedFolds() >= 2 && gbtCv.usedFolds() >= 2;
            boolean promotionApproved = beatsKnownBaselines
                    && stable
                    && groupedCvGatePassed
                    && futureSampleGatePassed
                    && calibrationGatePassed
                    && sideAccuracyGatePassed
                    && (!requireMarketBenchmark || (marketBenchmarkAvailable && beatsMarket));

            LocalDate trainFrom = train.get(0).matchDate;
            LocalDate trainTo = train.get(train.size() - 1).matchDate;
            LocalDate valFrom = test.get(0).matchDate;
            LocalDate valTo = test.get(test.size() - 1).matchDate;
            String jobId = "train-" + VERSION_TS.format(LocalDateTime.now());
            String championFamily = champion.family;

            Map<String, Object> releaseEvidence = new LinkedHashMap<>();
            releaseEvidence.put("datasetFingerprint", datasetFingerprint(samples));
            releaseEvidence.put("calibrationFrom", calibration.get(0).matchDate.toString());
            releaseEvidence.put("calibrationTo", calibration.get(calibration.size() - 1).matchDate.toString());
            releaseEvidence.put("calibrationTemperatureGrid", parseCalibrationTemperatures());
            releaseEvidence.put("selectedCalibrationMethod", champion.calibrationMethod);
            releaseEvidence.put("testFrom", valFrom.toString());
            releaseEvidence.put("testTo", valTo.toString());
            releaseEvidence.put("temporalGapDays", Math.max(0, temporalGapDays));
            releaseEvidence.put("trainingDateCount", samples.stream().map(TrainingSample::matchDate).distinct().count());
            releaseEvidence.put("maxSamplesPerDate", Math.max(20, maxSamplesPerDate));
            releaseEvidence.put("constantBrier", benchmarks.constantBrier());
            releaseEvidence.put("eloBrier", benchmarks.eloBrier());
            releaseEvidence.put("recentFormBrier", benchmarks.recentFormBrier());
            releaseEvidence.put("marketBenchmark", marketBenchmarkAvailable
                    ? "TIMESTAMP_MATCHED_HARD_ROCK_NO_VIG"
                    : "INSUFFICIENT_TIMESTAMP_MATCHED_HARD_ROCK_NO_VIG");
            releaseEvidence.put("marketBenchmarkTotalSamples", marketBenchmark.totalSamples());
            releaseEvidence.put("marketBenchmarkCoveredSamples", marketBenchmark.coveredSamples());
            releaseEvidence.put("marketBenchmarkCoverage", marketBenchmark.coverage());
            releaseEvidence.put("marketBenchmarkAsOfViolations", marketBenchmark.asOfViolations());
            releaseEvidence.put("marketBrier", marketBenchmark.brierScore());
            releaseEvidence.put("marketLogLoss", marketBenchmark.logLoss());
            releaseEvidence.put("marketAccuracy", marketBenchmark.accuracy());
            releaseEvidence.put("marketBenchmarkAvailable", marketBenchmarkAvailable);
            releaseEvidence.put("beatsMarketBrierAndLogLoss", beatsMarket);
            releaseEvidence.put("untouchedFutureOutcomes", untouchedOutcomes);
            releaseEvidence.put("untouchedFutureDays", untouchedDays);
            releaseEvidence.put("futureSampleGatePassed", futureSampleGatePassed);
            releaseEvidence.put("expectedCalibrationError", expectedCalibrationError);
            releaseEvidence.put("calibrationGatePassed", calibrationGatePassed);
            releaseEvidence.put("player1WinnerAccuracy", sideAccuracy.player1WinnerAccuracy());
            releaseEvidence.put("player2WinnerAccuracy", sideAccuracy.player2WinnerAccuracy());
            releaseEvidence.put("playerSideRawAccuracyGap", sideAccuracy.rawGap());
            releaseEvidence.put("playerSideAccuracyGap", sideAccuracy.marketControlledGap());
            releaseEvidence.put("playerSideMarketControlledSamples", sideAccuracy.marketControlledSamples());
            releaseEvidence.put("sideAccuracyGatePassed", sideAccuracyGatePassed);
            releaseEvidence.put("playerPairGroupedWalkForwardCv", true);
            releaseEvidence.put("groupedRegularization", true);
            releaseEvidence.put("basePairGroupedCvFolds", baseCv.usedFolds());
            releaseEvidence.put("gbtPairGroupedCvFolds", gbtCv.usedFolds());
            releaseEvidence.put("groupedCvGatePassed", groupedCvGatePassed);
            releaseEvidence.put("clusterBootstrapDimensions", List.of("date", "player", "player_pair"));
            releaseEvidence.put("timeSliceStabilityPassed", timeSliceStable);
            releaseEvidence.put("bootstrapSamples", bootstrap.samples());
            releaseEvidence.put("bootstrapConstantSkillLower95", bootstrap.constantSkillLower95());
            releaseEvidence.put("bootstrapEloSkillLower95", bootstrap.eloSkillLower95());
            releaseEvidence.put("bootstrapRecentFormSkillLower95", bootstrap.recentFormSkillLower95());
            releaseEvidence.put("bootstrapStabilityPassed", bootstrap.passed());
            releaseEvidence.put("pairedTrainingAugmentation", true);
            releaseEvidence.put("swapInvariantTrials", swapAudit.trials());
            releaseEvidence.put("swapInvariantFailures", swapAudit.failures());
            releaseEvidence.put("swapInvariantMaxProbabilityError", swapAudit.maxProbabilityError());
            releaseEvidence.put("swapInvariantPassed", swapAudit.passed());
            releaseEvidence.put("stabilityPassed", stable);
            releaseEvidence.put("promotionApproved", promotionApproved);
            releaseEvidence.put("promotionReason", promotionApproved
                    ? "PASSED_ALL_INTEGRITY_MARKET_AND_FUTURE_GATES"
                    : (!beatsKnownBaselines ? "FAILED_KNOWN_BASELINE_LIFT"
                    : (!stable ? "FAILED_TEMPORAL_STABILITY"
                    : (!groupedCvGatePassed ? "FAILED_PAIR_GROUPED_CROSS_VALIDATION"
                    : (!futureSampleGatePassed ? "INSUFFICIENT_FUTURE_OUTCOMES"
                    : (!marketBenchmarkAvailable ? "MARKET_BENCHMARK_UNAVAILABLE"
                    : (!beatsMarket ? "FAILED_MARKET_BENCHMARK"
                    : (!calibrationGatePassed ? "FAILED_CALIBRATION_ECE"
                    : "FAILED_PLAYER_SIDE_ACCURACY_GAP"))))))));

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
                    baseCv.usedFolds(),
                    promotionApproved && FAMILY_LOGISTIC.equals(championFamily),
                    "L2 logistic regression over historical feature vectors",
                    releaseEvidence
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
                    gbtCv.usedFolds(),
                    promotionApproved && FAMILY_GBT_LIKE.equals(championFamily),
                    "Non-linear feature expansion (GBDT-like surrogate)",
                    releaseEvidence
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
                    promotionApproved && FAMILY_RF_LIKE.equals(championFamily),
                    "Random forest style stump ensemble",
                    releaseEvidence
            ));

            Map<String, Object> ensemblePayload = new LinkedHashMap<>(releaseEvidence);
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
                    promotionApproved && FAMILY_ENSEMBLE.equals(championFamily),
                    "Weighted ensemble (logistic + best non-linear model)",
                    ensemblePayload
            ));

            activeModels.clear();
            // Explicit family/version inspection remains available after a
            // training run. The product default only receives a model after
            // every release gate passes; otherwise it stays on the baseline.
            activeModels.put(FAMILY_LOGISTIC, trained.get(FAMILY_LOGISTIC));
            activeModels.put(FAMILY_GBT_LIKE, trained.get(FAMILY_GBT_LIKE));
            activeModels.put(FAMILY_RF_LIKE, trained.get(FAMILY_RF_LIKE));
            // Keep the newest reviewed artifact available for shadow scoring
            // even when release gates withhold production promotion. Product
            // recommendations still require registry.active=true via
            // isPromotedModel(), so availability cannot bypass governance.
            activeModels.put(FAMILY_ENSEMBLE,
                    promotionApproved ? trained.get(championFamily) : trained.get(FAMILY_ENSEMBLE));

            List<ModelTrainingReportDto.CandidateMetricDto> candidates = List.of(
                    toCandidateDto(FAMILY_LOGISTIC, trained.get(FAMILY_LOGISTIC), logisticMetrics, promotionApproved && FAMILY_LOGISTIC.equals(championFamily)),
                    toCandidateDto(FAMILY_GBT_LIKE, trained.get(FAMILY_GBT_LIKE), gbtMetrics, promotionApproved && FAMILY_GBT_LIKE.equals(championFamily)),
                    toCandidateDto(FAMILY_RF_LIKE, trained.get(FAMILY_RF_LIKE), rfMetrics, promotionApproved && FAMILY_RF_LIKE.equals(championFamily)),
                    toCandidateDto(FAMILY_ENSEMBLE, trained.get(FAMILY_ENSEMBLE), ensembleMetrics, promotionApproved && FAMILY_ENSEMBLE.equals(championFamily))
            );

            List<ModelTrainingReportDto.CalibrationBinDto> calibrationCurve = buildCalibrationCurve(champion.model, test, 10);
            List<ModelTrainingReportDto.RegimeMetricDto> validationRegimes = buildValidationRegimes(champion.model, test);
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
            persistTrainingReport(report, trained);
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
                                        double ensembleProbability,
                                        boolean logisticAvailable,
                                        boolean gbtAvailable,
                                        boolean rfAvailable) {
        ConsensusProfile consensus = consensusProfile(
                baselineProbability,
                logisticProbability,
                glickoProbability,
                gbtProbability,
                rfProbability,
                logisticAvailable,
                gbtAvailable,
                rfAvailable
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
                ensembleProbability,
                clamp01(probabilityP1)
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
                                              boolean logisticAvailable,
                                              boolean gbtAvailable,
                                              boolean rfAvailable) {
        // Count only independently available predictors. Previously every
        // missing artifact was substituted with the same baseline value and
        // counted as another vote, manufacturing agreement and narrow CIs.
        List<Double> available = new ArrayList<>();
        available.add(clamp01(baselineProbability));
        available.add(clamp01(glickoProbability));
        if (logisticAvailable) available.add(clamp01(logisticProbability));
        if (gbtAvailable) available.add(clamp01(gbtProbability));
        if (rfAvailable) available.add(clamp01(rfProbability));
        double[] values = available.stream().mapToDouble(Double::doubleValue).toArray();
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
        HistoricalMarketIndex marketIndex = historicalMarketIndex(from, to);
        Map<LocalDate, List<TrainingCandidate>> candidatesByDate = new LinkedHashMap<>();
        Map<String, Boolean> seenIdentities = new HashMap<>();
        for (Match match : matches) {
            if (match.getPlayer1() == null || match.getPlayer2() == null || match.getDate() == null || match.getWinnerPlayerId() == null) {
                continue;
            }
            Long p1Id = match.getPlayer1().getId();
            Long p2Id = match.getPlayer2().getId();
            if (p1Id == null || p2Id == null || p1Id.equals(p2Id)) continue;
            String identity = stableMatchIdentity(match);
            if (!StringUtils.hasText(identity) || seenIdentities.putIfAbsent(identity, Boolean.TRUE) != null) {
                continue;
            }
            int label;
            if (Objects.equals(match.getWinnerPlayerId(), p1Id)) {
                label = 1;
            } else if (Objects.equals(match.getWinnerPlayerId(), p2Id)) {
                label = 0;
            } else {
                continue;
            }
            candidatesByDate.computeIfAbsent(match.getDate(), ignored -> new ArrayList<>())
                    .add(new TrainingCandidate(match, identity, label));
        }

        // Historical imports can contain hundreds of matches on a single day.
        // Reconstructing every row overweights dense scrape days and makes an
        // operator-triggered training request monopolize the live database.
        // Keep a deterministic, identity-hashed sample from each of the most
        // recent distinct dates so the temporal split remains representative,
        // reproducible, and bounded.
        int dateLimit = clamp(maxTrainingDates, 8, 120);
        int perDateLimit = clamp(maxSamplesPerDate, 20, 500);
        List<LocalDate> selectedDates = candidatesByDate.keySet().stream()
                .sorted(Comparator.reverseOrder())
                .limit(dateLimit)
                .sorted()
                .toList();
        List<TrainingSample> samples = new ArrayList<>(selectedDates.size() * perDateLimit);
        for (LocalDate date : selectedDates) {
            List<TrainingCandidate> candidates = new ArrayList<>(candidatesByDate.getOrDefault(date, List.of()));
            candidates.sort(Comparator.comparing(candidate -> sha256(candidate.identity())));
            for (TrainingCandidate candidate : candidates.stream().limit(perDateLimit).toList()) {
                Match match = candidate.match();
                LocalDate asOf = date.minusDays(1);
                MatchupFeatureVectorDto fv = featureService.buildMatchupFeatureVector(
                        match.getPlayer1().getId(), match.getPlayer2().getId(), asOf);
                double[] features = toBaseFeatures(fv);
                double sampleWeight = trainingSampleWeight(fv);
                MarketObservation market = marketIndex.lookup(match);
                String pairKey = canonicalPairKey(
                        match.getPlayer1().getId(), match.getPlayer2().getId());
                samples.add(new TrainingSample(
                        date,
                        features,
                        candidate.label(),
                        sampleWeight,
                        candidate.identity() + "|AB",
                        pairKey,
                        match.getPlayer1().getId(),
                        match.getPlayer2().getId(),
                        market == null ? null : market.player1NoVigProbability(),
                        market == null ? null : market.observedAt(),
                        market == null ? null : market.startAt()));
                // Paired augmentation prevents player-order imbalance from
                // leaking into the fitted intercept or feature coefficients.
                samples.add(new TrainingSample(
                        date,
                        negate(features),
                        1 - candidate.label(),
                        sampleWeight,
                        candidate.identity() + "|BA",
                        pairKey,
                        match.getPlayer2().getId(),
                        match.getPlayer1().getId(),
                        market == null ? null : 1.0 - market.player1NoVigProbability(),
                        market == null ? null : market.observedAt(),
                        market == null ? null : market.startAt()));
            }
        }
        samples.sort(Comparator.comparing(TrainingSample::matchDate));
        return samples;
    }

    private HistoricalMarketIndex historicalMarketIndex(LocalDate from, LocalDate to) {
        LocalDateTime observedFrom = from.minusDays(14).atStartOfDay();
        LocalDateTime observedTo = to.plusDays(2).atStartOfDay();
        return HistoricalMarketIndex.from(
                oddsSnapshotRepository.findHistoricalNoVigSnapshots(observedFrom, observedTo));
    }

    private static String canonicalPairKey(Long player1Id, Long player2Id) {
        long left = player1Id == null ? Long.MIN_VALUE : player1Id;
        long right = player2Id == null ? Long.MIN_VALUE : player2Id;
        return Math.min(left, right) + ":" + Math.max(left, right);
    }

    private static String marketPairDateKey(String player1, String player2, LocalDate date) {
        String left = NameUtils.normalizeForLookup(player1);
        String right = NameUtils.normalizeForLookup(player2);
        String pair = left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
        return pair + "|" + date;
    }

    private static LocalDateTime parseMarketStart(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        try {
            return LocalDateTime.ofInstant(Instant.parse(normalized), ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // Continue through the other supported ISO representations.
        }
        try {
            return OffsetDateTime.parse(normalized).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException ignored) {
            // Continue to an explicitly zone-less timestamp.
        }
        try {
            return LocalDateTime.parse(normalized);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String stableMatchIdentity(Match match) {
        if (StringUtils.hasText(match.getSourceFeedCode()) && StringUtils.hasText(match.getSourceFeedEventId())) {
            return match.getSourceFeedCode().trim().toUpperCase(Locale.ROOT)
                    + ":" + match.getSourceFeedEventId().trim();
        }
        if (StringUtils.hasText(match.getExternalId())) {
            return "EXTERNAL:" + match.getExternalId().trim();
        }
        return null;
    }

    private TemporalSplit temporalSplit(List<TrainingSample> samples) {
        List<LocalDate> dates = samples.stream()
                .map(TrainingSample::matchDate)
                .distinct()
                .sorted()
                .toList();
        if (dates.size() < 8) {
            throw new IllegalStateException("Training requires at least 8 distinct match dates for temporal isolation");
        }
        double fraction = clamp(holdoutRatio, 0.10, 0.25);
        int targetCalibrationIndex = Math.max(2,
                (int) Math.floor(dates.size() * (1.0 - (2.0 * fraction))));
        int targetTestIndex = Math.max(targetCalibrationIndex + 2,
                (int) Math.floor(dates.size() * (1.0 - fraction)));
        targetTestIndex = Math.min(targetTestIndex, dates.size() - 2);
        int gap = Math.max(0, temporalGapDays);

        // Pick chronological boundaries by retained sample mass, not only by
        // date count. Imported history is intentionally capped per day and can
        // be either very dense or one-match-per-day. Fixed percentile indexes
        // can leave a valid sparse history one observation short after purge
        // gaps. Searching the small, bounded date set preserves strict temporal
        // isolation while keeping every split decision-grade.
        int calibrationDateIndex = -1;
        int testDateIndex = -1;
        int bestRetained = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int candidateCalibration = 2; candidateCalibration <= dates.size() - 4; candidateCalibration++) {
            LocalDate calibrationStart = dates.get(candidateCalibration);
            LocalDate trainEnd = calibrationStart.minusDays(gap + 1L);
            for (int candidateTest = candidateCalibration + 2;
                 candidateTest <= dates.size() - 2;
                 candidateTest++) {
                LocalDate testStart = dates.get(candidateTest);
                LocalDate calibrationEnd = testStart.minusDays(gap + 1L);
                int trainCount = 0;
                int calibrationCount = 0;
                int testCount = 0;
                for (TrainingSample sample : samples) {
                    if (!sample.matchDate().isAfter(trainEnd)) {
                        trainCount++;
                    } else if (!sample.matchDate().isBefore(calibrationStart)
                            && !sample.matchDate().isAfter(calibrationEnd)) {
                        calibrationCount++;
                    } else if (!sample.matchDate().isBefore(testStart)) {
                        testCount++;
                    }
                }
                if (trainCount < 8 || calibrationCount < 6 || testCount < 6) {
                    continue;
                }
                int retained = trainCount + calibrationCount + testCount;
                double retainedScale = Math.max(1.0, retained);
                double distance = Math.abs((trainCount / retainedScale) - (1.0 - (2.0 * fraction)))
                        + Math.abs((calibrationCount / retainedScale) - fraction)
                        + Math.abs((testCount / retainedScale) - fraction)
                        + (Math.abs(candidateCalibration - targetCalibrationIndex)
                        + Math.abs(candidateTest - targetTestIndex)) * 1.0e-6;
                if (distance < bestDistance
                        || (Math.abs(distance - bestDistance) <= EPS && retained > bestRetained)) {
                    calibrationDateIndex = candidateCalibration;
                    testDateIndex = candidateTest;
                    bestDistance = distance;
                    bestRetained = retained;
                }
            }
        }
        if (calibrationDateIndex < 0 || testDateIndex < 0) {
            throw new IllegalStateException(
                    "Temporal train/calibration/test split cannot retain at least 8/6/6 samples after purge gaps");
        }
        LocalDate calibrationStart = dates.get(calibrationDateIndex);
        LocalDate testStart = dates.get(testDateIndex);
        LocalDate trainEnd = calibrationStart.minusDays(gap + 1L);
        LocalDate calibrationEnd = testStart.minusDays(gap + 1L);

        List<TrainingSample> train = samples.stream()
                .filter(sample -> !sample.matchDate().isAfter(trainEnd))
                .toList();
        List<TrainingSample> calibration = samples.stream()
                .filter(sample -> !sample.matchDate().isBefore(calibrationStart)
                        && !sample.matchDate().isAfter(calibrationEnd))
                .toList();
        List<TrainingSample> test = samples.stream()
                .filter(sample -> !sample.matchDate().isBefore(testStart))
                .toList();
        if (train.size() < 8 || calibration.size() < 6 || test.size() < 6) {
            throw new IllegalStateException(
                    "Temporal train/calibration/test split is too small after purge gaps: "
                            + train.size() + "/" + calibration.size() + "/" + test.size());
        }
        return new TemporalSplit(train, calibration, test);
    }

    private BenchmarkMetrics evaluateBenchmarks(List<TrainingSample> train, List<TrainingSample> test) {
        double constant = constantProbability(train);
        double constantBrier = 0.0;
        double eloBrier = 0.0;
        double recentFormBrier = 0.0;
        double testWeight = 0.0;
        for (TrainingSample sample : test) {
            double weight = clamp(sample.sampleWeight(), 0.05, 3.0);
            testWeight += weight;
            constantBrier += weight * Math.pow(constant - sample.label(), 2.0);
            double elo = clampProbability(0.5 + sample.baseFeatures()[4]);
            double recentForm = clampProbability(0.5 + sample.baseFeatures()[1]);
            eloBrier += weight * Math.pow(elo - sample.label(), 2.0);
            recentFormBrier += weight * Math.pow(recentForm - sample.label(), 2.0);
        }
        return new BenchmarkMetrics(
                constantBrier / Math.max(EPS, testWeight),
                eloBrier / Math.max(EPS, testWeight),
                recentFormBrier / Math.max(EPS, testWeight)
        );
    }

    private MarketBenchmarkMetrics evaluateMarketBenchmark(PredictModel model,
                                                            List<TrainingSample> test) {
        List<TrainingSample> originals = originalOrientationSamples(test);
        int total = originals.size();
        int covered = 0;
        int correct = 0;
        int asOfViolations = 0;
        double brier = 0.0;
        double logLoss = 0.0;
        double weightSum = 0.0;
        for (TrainingSample sample : originals) {
            Double market = sample.marketProbability();
            if (market == null || !Double.isFinite(market)) {
                continue;
            }
            covered++;
            if (sample.marketObservedAt() == null
                    || sample.marketStartAt() == null
                    || !sample.marketObservedAt().isBefore(sample.marketStartAt())) {
                asOfViolations++;
                continue;
            }
            double probability = clampProbability(market);
            double weight = clamp(sample.sampleWeight(), 0.05, 3.0);
            weightSum += weight;
            brier += weight * Math.pow(probability - sample.label(), 2.0);
            logLoss += weight * (-(sample.label() * Math.log(probability)
                    + (1 - sample.label()) * Math.log(1.0 - probability)));
            if ((probability >= 0.5) == (sample.label() == 1)) {
                correct++;
            }
        }
        double validCovered = Math.max(0, covered - asOfViolations);
        return new MarketBenchmarkMetrics(
                total,
                (int) validCovered,
                total == 0 ? 0.0 : validCovered / total,
                weightSum <= EPS ? Double.POSITIVE_INFINITY : brier / weightSum,
                weightSum <= EPS ? Double.POSITIVE_INFINITY : logLoss / weightSum,
                validCovered <= 0 ? 0.0 : correct / validCovered,
                asOfViolations
        );
    }

    private List<TrainingSample> originalOrientationSamples(List<TrainingSample> samples) {
        return samples.stream()
                .filter(sample -> sample.identity().endsWith("|AB"))
                .toList();
    }

    private double expectedCalibrationError(PredictModel model,
                                            List<TrainingSample> samples,
                                            int requestedBins) {
        List<TrainingSample> originals = originalOrientationSamples(samples);
        if (originals.isEmpty()) return 1.0;
        int bins = clamp(requestedBins, 5, 20);
        double[] predicted = new double[bins];
        double[] observed = new double[bins];
        double[] weights = new double[bins];
        for (TrainingSample sample : originals) {
            double probability = clampProbability(model.predict(sample.baseFeatures()));
            int bin = Math.min(bins - 1, (int) Math.floor(probability * bins));
            double weight = clamp(sample.sampleWeight(), 0.05, 3.0);
            predicted[bin] += probability * weight;
            observed[bin] += sample.label() * weight;
            weights[bin] += weight;
        }
        double total = Arrays.stream(weights).sum();
        double ece = 0.0;
        for (int i = 0; i < bins; i++) {
            if (weights[i] <= EPS) continue;
            ece += (weights[i] / Math.max(EPS, total))
                    * Math.abs((predicted[i] / weights[i]) - (observed[i] / weights[i]));
        }
        return ece;
    }

    private SideAccuracyAudit sideAccuracyAudit(PredictModel model,
                                                List<TrainingSample> samples) {
        final int marketBins = 10;
        int[] p1BinSamples = new int[marketBins];
        int[] p2BinSamples = new int[marketBins];
        int[] p1BinCorrect = new int[marketBins];
        int[] p2BinCorrect = new int[marketBins];
        int player1WinnerSamples = 0;
        int player2WinnerSamples = 0;
        int player1WinnerCorrect = 0;
        int player2WinnerCorrect = 0;
        for (TrainingSample sample : originalOrientationSamples(samples)) {
            boolean predictedP1 = model.predict(sample.baseFeatures()) >= 0.5;
            if (sample.label() == 1) {
                player1WinnerSamples++;
                if (predictedP1) player1WinnerCorrect++;
            } else {
                player2WinnerSamples++;
                if (!predictedP1) player2WinnerCorrect++;
            }
            if (sample.marketProbability() != null
                    && Double.isFinite(sample.marketProbability())
                    && sample.marketProbability() > 0.0
                    && sample.marketProbability() < 1.0) {
                double actualWinnerMarketProbability = sample.label() == 1
                        ? sample.marketProbability()
                        : 1.0 - sample.marketProbability();
                int bin = Math.min(marketBins - 1,
                        Math.max(0, (int) Math.floor(actualWinnerMarketProbability * marketBins)));
                if (sample.label() == 1) {
                    p1BinSamples[bin]++;
                    if (predictedP1) p1BinCorrect[bin]++;
                } else {
                    p2BinSamples[bin]++;
                    if (!predictedP1) p2BinCorrect[bin]++;
                }
            }
        }
        double p1Accuracy = player1WinnerSamples == 0 ? 0.0
                : (double) player1WinnerCorrect / player1WinnerSamples;
        double p2Accuracy = player2WinnerSamples == 0 ? 0.0
                : (double) player2WinnerCorrect / player2WinnerSamples;
        double rawGap = player1WinnerSamples == 0 || player2WinnerSamples == 0
                ? 1.0
                : Math.abs(p1Accuracy - p2Accuracy);
        double controlledGapSum = 0.0;
        int controlledSamples = 0;
        for (int bin = 0; bin < marketBins; bin++) {
            int balancedWeight = Math.min(p1BinSamples[bin], p2BinSamples[bin]);
            if (balancedWeight == 0) continue;
            double p1BinAccuracy = (double) p1BinCorrect[bin] / p1BinSamples[bin];
            double p2BinAccuracy = (double) p2BinCorrect[bin] / p2BinSamples[bin];
            controlledGapSum += balancedWeight * Math.abs(p1BinAccuracy - p2BinAccuracy);
            controlledSamples += balancedWeight;
        }
        double marketControlledGap = controlledSamples == 0
                ? 1.0
                : controlledGapSum / controlledSamples;
        return new SideAccuracyAudit(
                p1Accuracy,
                p2Accuracy,
                rawGap,
                marketControlledGap,
                controlledSamples * 2);
    }

    private double constantProbability(List<TrainingSample> train) {
        double weightedWins = 0.0;
        double trainWeight = 0.0;
        for (TrainingSample sample : train) {
            double weight = clamp(sample.sampleWeight(), 0.05, 3.0);
            weightedWins += weight * sample.label();
            trainWeight += weight;
        }
        return clampProbability(weightedWins / Math.max(EPS, trainWeight));
    }

    private boolean stableAcrossTimeSlices(PredictModel model,
                                           List<TrainingSample> train,
                                           List<TrainingSample> test) {
        if (test.size() < 18) {
            return false;
        }
        int segment = test.size() / 3;
        for (int i = 0; i < 3; i++) {
            int from = i * segment;
            int to = i == 2 ? test.size() : (i + 1) * segment;
            List<TrainingSample> window = test.subList(from, to);
            if (window.size() < 6) {
                return false;
            }
            double modelBrier = evaluateCandidate(model, window).brierScore;
            BenchmarkMetrics baselines = evaluateBenchmarks(train, window);
            if (modelBrier > baselines.constantBrier() + minLiftForAdvanced
                    || modelBrier > baselines.eloBrier() + minLiftForAdvanced
                    || modelBrier > baselines.recentFormBrier() + minLiftForAdvanced) {
                return false;
            }
        }
        return true;
    }

    private BootstrapStability bootstrapStability(PredictModel model,
                                                   List<TrainingSample> train,
                                                   List<TrainingSample> test) {
        final int samples = 500;
        List<TrainingSample> originals = originalOrientationSamples(test);
        if (originals.size() < 30) {
            return new BootstrapStability(false, 0, -1.0, -1.0, -1.0);
        }
        double constant = constantProbability(train);
        ClusterSkillBounds byDate = bootstrapClusterSkill(
                model, originals, constant, samples, 0x54544c44415445L,
                sample -> Set.of(sample.matchDate().toString()));
        ClusterSkillBounds byPair = bootstrapClusterSkill(
                model, originals, constant, samples, 0x54544c50414952L,
                sample -> Set.of(sample.pairKey()));
        ClusterSkillBounds byPlayer = bootstrapClusterSkill(
                model, originals, constant, samples, 0x54544c504c4159L,
                sample -> Set.of("P:" + sample.player1Id(), "P:" + sample.player2Id()));
        double constantLower = Math.min(byDate.constantLower95(),
                Math.min(byPair.constantLower95(), byPlayer.constantLower95()));
        double eloLower = Math.min(byDate.eloLower95(),
                Math.min(byPair.eloLower95(), byPlayer.eloLower95()));
        double recentLower = Math.min(byDate.recentLower95(),
                Math.min(byPair.recentLower95(), byPlayer.recentLower95()));
        boolean passed = byDate.valid() && byPair.valid() && byPlayer.valid()
                && constantLower > minLiftForAdvanced
                && eloLower > minLiftForAdvanced
                && recentLower > minLiftForAdvanced;
        return new BootstrapStability(passed, samples, constantLower, eloLower, recentLower);
    }

    private ClusterSkillBounds bootstrapClusterSkill(
            PredictModel model,
            List<TrainingSample> samples,
            double constant,
            int iterations,
            long seed,
            Function<TrainingSample, Set<String>> clusterKeys) {
        Map<String, List<TrainingSample>> clusters = new LinkedHashMap<>();
        for (TrainingSample sample : samples) {
            for (String key : clusterKeys.apply(sample)) {
                clusters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sample);
            }
        }
        if (clusters.size() < 3) {
            return ClusterSkillBounds.invalid();
        }
        List<List<TrainingSample>> groups = new ArrayList<>(clusters.values());
        Random random = new Random(seed);
        List<Double> constantSkill = new ArrayList<>(iterations);
        List<Double> eloSkill = new ArrayList<>(iterations);
        List<Double> recentFormSkill = new ArrayList<>(iterations);
        for (int iteration = 0; iteration < iterations; iteration++) {
            double modelLoss = 0.0;
            double constantLoss = 0.0;
            double eloLoss = 0.0;
            double recentFormLoss = 0.0;
            double weightSum = 0.0;
            for (int draw = 0; draw < groups.size(); draw++) {
                for (TrainingSample sample : groups.get(random.nextInt(groups.size()))) {
                    double weight = clamp(sample.sampleWeight(), 0.05, 3.0);
                    double label = sample.label();
                    double modelProbability = clampProbability(model.predict(sample.baseFeatures()));
                    double eloProbability = clampProbability(0.5 + sample.baseFeatures()[4]);
                    double recentProbability = clampProbability(0.5 + sample.baseFeatures()[1]);
                    weightSum += weight;
                    modelLoss += weight * Math.pow(modelProbability - label, 2.0);
                    constantLoss += weight * Math.pow(constant - label, 2.0);
                    eloLoss += weight * Math.pow(eloProbability - label, 2.0);
                    recentFormLoss += weight * Math.pow(recentProbability - label, 2.0);
                }
            }
            double divisor = Math.max(EPS, weightSum);
            constantSkill.add((constantLoss - modelLoss) / divisor);
            eloSkill.add((eloLoss - modelLoss) / divisor);
            recentFormSkill.add((recentFormLoss - modelLoss) / divisor);
        }
        return new ClusterSkillBounds(
                lowerFivePercentile(constantSkill),
                lowerFivePercentile(eloSkill),
                lowerFivePercentile(recentFormSkill),
                true);
    }

    private double lowerFivePercentile(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int index = Math.max(0, (int) Math.floor((sorted.size() - 1) * 0.05));
        return sorted.get(index);
    }

    private String datasetFingerprint(List<TrainingSample> samples) {
        StringBuilder canonical = new StringBuilder(MODEL_SCHEMA_VERSION);
        for (TrainingSample sample : samples) {
            canonical.append('|').append(sample.identity())
                    .append('|').append(sample.matchDate())
                    .append('|').append(sample.label())
                    .append('|').append(sample.pairKey())
                    .append('|').append(sample.marketProbability())
                    .append('|').append(sample.marketObservedAt())
                    .append('|').append(sample.marketStartAt());
            for (double value : sample.baseFeatures()) {
                canonical.append('|').append(String.format(Locale.ROOT, "%.8f", value));
            }
        }
        return sha256(canonical.toString());
    }

    /**
     * Release-blocking property audit over randomized antisymmetric feature
     * vectors. The hard predictor wrapper should make every probability pair
     * complementary to machine precision, independent of model family.
     */
    private SwapInvariantAudit auditSwapInvariance(PredictModel model, int requestedTrials) {
        int trials = Math.max(10_000, requestedTrials);
        Random random = new Random(0x52_32_31L);
        int failures = 0;
        double maxError = 0.0;
        for (int i = 0; i < trials; i++) {
            double[] forward = new double[BASE_FEATURE_NAMES.length];
            for (int j = 0; j < forward.length; j++) {
                forward[j] = (random.nextDouble() * 2.0) - 1.0;
            }
            double pForward = clamp01(model.predict(forward));
            double pReverse = clamp01(model.predict(negate(forward)));
            double error = Math.abs((pForward + pReverse) - 1.0);
            maxError = Math.max(maxError, error);
            if (!Double.isFinite(error) || error > 1.0e-6) {
                failures++;
            }
        }
        return new SwapInvariantAudit(trials, failures, maxError, failures == 0);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format(Locale.ROOT, "%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
        double formCap = clamp(formFeatureCap, 0.10, 0.50);
        double recentDiff = clamp(
                (fv.player1().recentForm() - fv.player2().recentForm()) * recentReliability,
                -formCap,
                formCap);
        double adjDiff = clamp(
                (fv.player1().opponentAdjustedForm() - fv.player2().opponentAdjustedForm()) * opponentReliability,
                -formCap,
                formCap);
        double scheduleCap = clamp(scheduleFeatureCap, 0.10, 0.40);
        double scheduleDiff = clamp(
                ((fv.player1().scheduleStrength() - fv.player2().scheduleStrength()) / 300.0) * scheduleReliability,
                -scheduleCap,
                scheduleCap);
        double eloDelta = fv.eloProbabilityPlayer1() - 0.5;
        double glickoDelta = fv.glickoProbabilityPlayer1() - 0.5;
        double ratingDiff = (fv.player1().glickoRating() - fv.player2().glickoRating()) / 400.0;
        double rdAdvantage = (fv.player2().glickoRatingDeviation() - fv.player1().glickoRatingDeviation()) / 200.0;
        double volAdvantage = (fv.player2().glickoVolatility() - fv.player1().glickoVolatility()) / 0.2;
        double trueSkill2Delta = fv.trueSkill2ProbabilityPlayer1() - 0.5;
        double wengLinDelta = wengLinFeatureEnabled ? fv.wengLinProbabilityPlayer1() - 0.5 : 0.0;
        double raterEnsembleDelta = fv.raterEnsembleProbabilityPlayer1() - 0.5;
        double ratingAgreement = fv.reliabilitySummary() == null
                ? 0.0
                : clamp(fv.reliabilitySummary().ratingAgreement(), 0.0, 1.0);
        double raterConsensusSignal = raterConsensusFeatureEnabled
                ? raterEnsembleDelta * ratingAgreement
                : 0.0;
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
                trueSkill2Delta,
                wengLinDelta,
                raterEnsembleDelta,
                raterConsensusSignal
        };
    }

    private double baselineProbability(double[] x) {
        double z = 0.0;
        z += 0.55 * x[0];
        z += 0.30 * x[1];
        z += 0.30 * x[2];
        z += 0.15 * x[3];
        z += 1.15 * x[4];
        z += 1.15 * x[5];
        z += 0.45 * x[6];
        z += 0.25 * x[7];
        z += 0.15 * x[8];
        z += 0.75 * x[9];
        z += 0.80 * x[10];
        z += 0.70 * x[11];
        z += 0.45 * x[12];
        return sigmoid(z);
    }

    private List<MatchupAnalysisDto.FeatureContributionDto> baselineContributions(double[] x) {
        double[] weights = new double[]{
                0.55, 0.30, 0.30, 0.15, 1.15, 1.15, 0.45, 0.25,
                0.15, 0.75, 0.80, 0.70, 0.45
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

    private CrossValidationSelection selectBestLambda(List<TrainingSample> train,
                                                      List<Double> lambdas,
                                                      FeatureSet featureSet) {
        List<LocalDate> dates = train.stream()
                .map(TrainingSample::matchDate)
                .distinct()
                .sorted()
                .toList();
        int folds = clamp(cvFolds, 2, Math.min(8, Math.max(2, dates.size() - 2)));
        double bestLambda = lambdas.get(0);
        double bestBrier = Double.POSITIVE_INFINITY;
        double bestLogLoss = Double.POSITIVE_INFINITY;
        int bestUsedFolds = 0;

        for (double lambda : lambdas) {
            double brierTotal = 0.0;
            double logLossTotal = 0.0;
            int usedFolds = 0;
            int validationDateCount = Math.max(1, dates.size() / (folds + 2));
            Set<Integer> usedStarts = new java.util.HashSet<>();
            for (int fold = 0; fold < folds; fold++) {
                int latestStart = Math.max(2, dates.size() - validationDateCount);
                int startIndex = Math.max(2, ((fold + 1) * latestStart) / (folds + 1));
                if (!usedStarts.add(startIndex) || startIndex >= dates.size()) continue;
                int endIndex = Math.min(dates.size(), startIndex + validationDateCount);
                LocalDate validationStart = dates.get(startIndex);
                LocalDate validationEnd = dates.get(endIndex - 1);
                List<TrainingSample> validation = train.stream()
                        .filter(sample -> !sample.matchDate().isBefore(validationStart)
                                && !sample.matchDate().isAfter(validationEnd))
                        .toList();
                Set<String> validationPairs = validation.stream()
                        .map(TrainingSample::pairKey)
                        .collect(java.util.stream.Collectors.toSet());
                Set<Long> validationPlayers = validation.stream()
                        .flatMap(sample -> java.util.stream.Stream.of(sample.player1Id(), sample.player2Id()))
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                LocalDate trainCutoff = validationStart.minusDays(Math.max(0, temporalGapDays) + 1L);
                List<TrainingSample> foldTrain = train.stream()
                        .filter(sample -> !sample.matchDate().isAfter(trainCutoff))
                        .filter(sample -> !validationPairs.contains(sample.pairKey()))
                        .filter(sample -> !validationPlayers.contains(sample.player1Id())
                                && !validationPlayers.contains(sample.player2Id()))
                        .toList();
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
                bestUsedFolds = usedFolds;
            }
        }
        return new CrossValidationSelection(bestLambda, bestUsedFolds, bestBrier, bestLogLoss);
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
            // Antisymmetric paired training makes zero the semantic neutral
            // point. Pinning the center to zero preserves exact sign reversal
            // even under floating-point accumulation and artifact reloads.
            means[j] = 0.0;
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
                // The intercept remains exactly zero. With paired examples
                // and antisymmetric inputs, a neutral matchup must stay 50/50.
                double z = 0.0;
                for (int j = 0; j < d; j++) {
                    z += weights[j + 1] * standardized(X[i][j], means[j], stds[j]);
                }
                double p = sigmoid(z);
                double err = (p - y[i]) * sampleWeights[i];
                for (int j = 0; j < d; j++) {
                    grad[j + 1] += err * standardized(X[i][j], means[j], stds[j]);
                }
            }
            double invW = 1.0 / weightSum;
            weights[0] = 0.0;
            for (int j = 1; j <= d; j++) {
                double reg = lambda * regularizationMultiplier(featureSet.featureNames[j - 1]) * weights[j];
                weights[j] -= rate * (grad[j] * invW + reg);
            }
            rate *= 0.995;
        }

        return new LogisticModel(featureSet.featureNames, featureSet.transform, means, stds, weights, lambda);
    }

    private static double regularizationMultiplier(String featureName) {
        String normalized = featureName == null ? "" : featureName.toLowerCase(Locale.ROOT);
        if (normalized.contains("form") || normalized.contains("schedule")) {
            return 1.50;
        }
        if (normalized.contains("head-to-head") || normalized.contains("h2h")) {
            return 1.25;
        }
        if (normalized.contains("rating") || normalized.contains("glicko")
                || normalized.contains("trueskill") || normalized.contains("weng-lin")
                || normalized.contains("rater") || normalized.contains("elo")) {
            return 1.00;
        }
        return 1.15;
    }

    private void maybeCalibrate(LogisticModel model,
                                List<TrainingSample> validation,
                                CandidateMetrics currentMetrics) {
        if (validation.size() < 30) {
            currentMetrics.calibrationMethod = "NONE";
            return;
        }

        List<Double> temperatures = parseCalibrationTemperatures();
        if (temperatures.isEmpty()) {
            currentMetrics.calibrationMethod = "NONE";
            return;
        }

        PlattCalibrator selected = null;
        CandidateMetrics selectedMetrics = null;
        for (double temperature : temperatures) {
            String method = "TEMPERATURE_GRID_"
                    + String.format(Locale.ROOT, "%.2f", temperature).replace('.', '_');
            PlattCalibrator candidate = new PlattCalibrator(1.0 / temperature, 0.0, method);
            CandidateMetrics metrics = evaluateCandidate(model.withCalibrator(candidate), validation);
            if (selectedMetrics == null
                    || metrics.logLoss + EPS < selectedMetrics.logLoss
                    || (Math.abs(metrics.logLoss - selectedMetrics.logLoss) <= EPS
                    && metrics.brierScore < selectedMetrics.brierScore)) {
                selected = candidate;
                selectedMetrics = metrics;
            }
        }

        if (selected == null || selectedMetrics == null) {
            currentMetrics.calibrationMethod = "NONE";
            return;
        }

        // The grid is preregistered. It is selected on the calibration window
        // only; the untouched future test window is never consulted here.
        model.calibrator = selected;
        currentMetrics.accuracy = selectedMetrics.accuracy;
        currentMetrics.logLoss = selectedMetrics.logLoss;
        currentMetrics.brierScore = selectedMetrics.brierScore;
        currentMetrics.calibrationMethod = selected.method;
    }

    private List<Double> parseCalibrationTemperatures() {
        LinkedHashSet<Double> parsed = new LinkedHashSet<>();
        if (StringUtils.hasText(calibrationTemperatureGrid)) {
            for (String token : calibrationTemperatureGrid.split(",")) {
                try {
                    double value = Double.parseDouble(token.trim());
                    if (Double.isFinite(value) && value >= 1.0 && value <= 3.0) {
                        parsed.add(value);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed override values; fail closed to the
                    // documented preregistered grid below if none are valid.
                }
            }
        }
        if (parsed.isEmpty()) {
            parsed.addAll(List.of(1.25, 1.40, 1.55, 1.75));
        }
        return List.copyOf(parsed);
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
        String release = StringUtils.hasText(releaseName)
                ? releaseName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "")
                : "accuracy-guardrails-r1";
        String version = release + "-" + VERSION_TS.format(LocalDateTime.now())
                + "-" + family + "-" + (System.nanoTime() % 100_000);

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
        payload.put("schemaVersion", MODEL_SCHEMA_VERSION);
        payload.put("baseFeatureCount", BASE_FEATURE_NAMES.length);
        payload.put("featureSchemaHash", expectedFeatureSchemaHash());
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

    private void persistTrainingReport(ModelTrainingReportDto report, Map<String, TrainedModel> trained) {
        for (TrainedModel model : trained.values()) {
            registryRepository.findByModelVersion(model.version).ifPresent(entry -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = objectMapper.readValue(entry.getPayloadJson(), Map.class);
                    payload.put("trainingReport", report);
                    entry.setPayloadJson(serializePayload(payload));
                    registryRepository.save(entry);
                } catch (Exception ignored) {
                    // The model payload remains valid even if report attachment
                    // fails; latestTrainingReport() will truthfully return N/A.
                }
            });
        }
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"payload-serialization-failed\"}";
        }
    }

    private String expectedFeatureSchemaHash() {
        return sha256(MODEL_SCHEMA_VERSION + "|" + String.join("|", BASE_FEATURE_NAMES));
    }

    private ModelRegistryEntryDto toRegistryDto(PredictionModelRegistryEntry e) {
        RegistryReleaseMetadata release = registryReleaseMetadata(e);
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
                e.getCreatedAt(),
                release.artifactChecksum(),
                release.featureSchemaChecksum(),
                release.promotionStatus(),
                release.promotionReason()
        );
    }

    private RegistryReleaseMetadata registryReleaseMetadata(PredictionModelRegistryEntry entry) {
        if (entry == null || !StringUtils.hasText(entry.getPayloadJson())) {
            return new RegistryReleaseMetadata(null, null,
                    entry != null && entry.isActive() ? "APPROVED" : "RESEARCH", null);
        }
        try {
            JsonNode payload = objectMapper.readTree(entry.getPayloadJson());
            String featureSchema = payload.path("featureSchemaHash").asText(null);
            String reason = payload.path("promotionReason").asText(null);
            boolean promotionApproved = payload.path("promotionApproved").asBoolean(false);
            String status = entry.isActive()
                    ? "APPROVED"
                    : promotionApproved
                    ? "SHADOW"
                    : StringUtils.hasText(reason) ? "PROMOTION_FAILED" : "RESEARCH";
            return new RegistryReleaseMetadata(
                    sha256(entry.getModelVersion() + "|" + entry.getPayloadJson()),
                    featureSchema,
                    status,
                    reason);
        } catch (Exception ignored) {
            return new RegistryReleaseMetadata(null, null,
                    entry.isActive() ? "APPROVED" : "RESEARCH", null);
        }
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
            // Prediction requests must never trigger an implicit retrain. An
            // operator-reviewed training run may promote a compatible model;
            // until then predict() deliberately falls back to the baseline.
        }
    }

    private ModelSelection selectRequestedModel(String requested,
                                                TrainedModel logistic,
                                                TrainedModel gbtLike,
                                                TrainedModel rfLike,
                                                TrainedModel ensemble) {
        if (!StringUtils.hasText(requested)) {
            return ensemble == null
                    ? new ModelSelection(FAMILY_BASELINE, null, true)
                    : new ModelSelection(FAMILY_ENSEMBLE, ensemble, false);
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
            if (FAMILY_ENSEMBLE.equals(upper) && selected == null) {
                return new ModelSelection(FAMILY_BASELINE, null, true);
            }
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

        for (String family : List.of(FAMILY_LOGISTIC, FAMILY_GBT_LIKE, FAMILY_RF_LIKE)) {
            loadLatestModelForFamily(family, loadedByVersion).ifPresent(model -> {
                loadedByFamily.put(family, model);
                loadedByVersion.put(model.version, model);
            });
        }
        loadLatestModelForFamily(FAMILY_ENSEMBLE, loadedByVersion)
                .ifPresent(model -> loadedByFamily.put(FAMILY_ENSEMBLE, model));

        if (!loadedByFamily.isEmpty()) {
            activeModels.clear();
            activeModels.putAll(loadedByFamily);
        }
    }

    private Optional<TrainedModel> loadLatestModelForFamily(String family, Map<String, TrainedModel> knownByVersion) {
        List<PredictionModelRegistryEntry> candidates = new ArrayList<>();
        candidates.addAll(registryRepository.findActiveByFamily(family, PageRequest.of(0, 20)));
        candidates.addAll(registryRepository.findRecentByFamily(family, PageRequest.of(0, 20)));
        for (PredictionModelRegistryEntry candidate : candidates) {
            TrainedModel restored = restoreModelFromRegistryEntry(candidate, knownByVersion);
            if (restored != null) {
                return Optional.of(restored);
            }
        }
        return Optional.empty();
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
            if (!MODEL_SCHEMA_VERSION.equals(payload.path("schemaVersion").asText())
                    || payload.path("baseFeatureCount").asInt(-1) != BASE_FEATURE_NAMES.length
                    || !expectedFeatureSchemaHash().equals(payload.path("featureSchemaHash").asText())) {
                return null;
            }
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
                    payload.path("calibratorB").asDouble(),
                    payload.path("calibratorMethod").asText("PLATT_LEGACY")
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
        if (featureCount == BASE_FEATURE_NAMES.length) {
            return FeatureSet.base().transform;
        }
        throw new IllegalArgumentException("Unsupported prediction feature schema size: " + featureCount);
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

    private static double[] negate(double[] values) {
        double[] reversed = Arrays.copyOf(values, values.length);
        for (int i = 0; i < reversed.length; i++) {
            reversed[i] = -reversed[i];
        }
        return reversed;
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
                                     double ensembleProbability,
                                     double rawPlayer1Probability) {

        /** Compatibility constructor for callers built against the pre-R2.1 snapshot. */
        public PredictionSnapshot(String modelFamily,
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
            this(modelFamily, modelVersion, calibrationMethod, player1Probability, player2Probability,
                    player1ConfidenceLow, player1ConfidenceHigh, featureContributions, featureVector,
                    baselineProbability, logisticProbability, glickoProbability, gbtLikeProbability,
                    rfLikeProbability, ensembleProbability, player1Probability);
        }
    }

    private record TrainingSample(LocalDate matchDate,
                                  double[] baseFeatures,
                                  int label,
                                  double sampleWeight,
                                  String identity,
                                  String pairKey,
                                  Long player1Id,
                                  Long player2Id,
                                  Double marketProbability,
                                  LocalDateTime marketObservedAt,
                                  LocalDateTime marketStartAt) {
    }

    private record TrainingCandidate(Match match, String identity, int label) {
    }

    private record TemporalSplit(List<TrainingSample> train,
                                 List<TrainingSample> calibration,
                                 List<TrainingSample> test) {
    }

    private record CrossValidationSelection(double lambda,
                                            int usedFolds,
                                            double brierScore,
                                            double logLoss) {
    }

    private record BenchmarkMetrics(double constantBrier,
                                    double eloBrier,
                                    double recentFormBrier) {
    }

    private record MarketBenchmarkMetrics(int totalSamples,
                                          int coveredSamples,
                                          double coverage,
                                          double brierScore,
                                          double logLoss,
                                          double accuracy,
                                          int asOfViolations) {
    }

    private record SideAccuracyAudit(double player1WinnerAccuracy,
                                     double player2WinnerAccuracy,
                                     double rawGap,
                                     double marketControlledGap,
                                     int marketControlledSamples) {
    }

    private record MarketObservation(double player1NoVigProbability,
                                     LocalDateTime observedAt,
                                     LocalDateTime startAt) {
    }

    private static final class HistoricalMarketIndex {
        private final Map<String, List<HistoricalMarketEvent>> byPairDate;

        private HistoricalMarketIndex(Map<String, List<HistoricalMarketEvent>> byPairDate) {
            this.byPairDate = byPairDate;
        }

        private static HistoricalMarketIndex from(List<OddsSnapshot> snapshots) {
            Map<String, List<OddsSnapshot>> byMatchKey = new LinkedHashMap<>();
            if (snapshots != null) {
                for (OddsSnapshot snapshot : snapshots) {
                    if (snapshot == null
                            || !StringUtils.hasText(snapshot.getMatchKey())
                            || snapshot.getMatchKey().startsWith("mk:")) {
                        continue;
                    }
                    byMatchKey.computeIfAbsent(snapshot.getMatchKey(), ignored -> new ArrayList<>())
                            .add(snapshot);
                }
            }

            Map<String, List<HistoricalMarketEvent>> index = new LinkedHashMap<>();
            for (Map.Entry<String, List<OddsSnapshot>> entry : byMatchKey.entrySet()) {
                HistoricalMarketEvent event = HistoricalMarketEvent.from(entry.getKey(), entry.getValue());
                if (event == null) continue;
                String key = marketPairDateKey(event.player1(), event.player2(), event.startAt().toLocalDate());
                index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
            }
            return new HistoricalMarketIndex(index);
        }

        private MarketObservation lookup(Match match) {
            if (match == null || match.getDate() == null
                    || match.getPlayer1() == null || match.getPlayer2() == null) {
                return null;
            }
            String p1 = NameUtils.normalizeForLookup(match.getPlayer1().getName());
            String p2 = NameUtils.normalizeForLookup(match.getPlayer2().getName());
            String key = marketPairDateKey(p1, p2, match.getDate());
            List<MarketObservation> candidates = byPairDate.getOrDefault(key, List.of()).stream()
                    .map(event -> event.observationFor(p1, p2))
                    .filter(Objects::nonNull)
                    .toList();
            // Multiple same-pair events on one day cannot be associated with a
            // date-only archive match without guessing. Fail closed.
            return candidates.size() == 1 ? candidates.get(0) : null;
        }
    }

    private record HistoricalMarketEvent(String player1,
                                         String player2,
                                         LocalDateTime startAt,
                                         List<OddsSnapshot> snapshots) {
        private static HistoricalMarketEvent from(String matchKey, List<OddsSnapshot> snapshots) {
            int first = matchKey == null ? -1 : matchKey.indexOf('|');
            int second = first < 0 ? -1 : matchKey.indexOf('|', first + 1);
            if (first <= 0 || second <= first + 1 || second >= matchKey.length() - 1) {
                return null;
            }
            String player1 = NameUtils.normalizeForLookup(matchKey.substring(0, first));
            String player2 = NameUtils.normalizeForLookup(matchKey.substring(first + 1, second));
            LocalDateTime start = parseMarketStart(matchKey.substring(second + 1));
            if (!StringUtils.hasText(player1) || !StringUtils.hasText(player2) || start == null) {
                return null;
            }
            return new HistoricalMarketEvent(player1, player2, start, List.copyOf(snapshots));
        }

        private MarketObservation observationFor(String matchPlayer1, String matchPlayer2) {
            String side;
            if (player1.equals(matchPlayer1) && player2.equals(matchPlayer2)) {
                side = "P1";
            } else if (player1.equals(matchPlayer2) && player2.equals(matchPlayer1)) {
                side = "P2";
            } else {
                return null;
            }
            OddsSnapshot latest = snapshots.stream()
                    .filter(snapshot -> side.equalsIgnoreCase(snapshot.getSide()))
                    .filter(snapshot -> snapshot.getObservedAt() != null
                            && snapshot.getObservedAt().isBefore(startAt))
                    .filter(snapshot -> snapshot.getNoVigProbability() != null
                            && Double.isFinite(snapshot.getNoVigProbability())
                            && snapshot.getNoVigProbability() > 0.0
                            && snapshot.getNoVigProbability() < 1.0)
                    .max(Comparator.comparing(OddsSnapshot::getObservedAt))
                    .orElse(null);
            return latest == null ? null : new MarketObservation(
                    latest.getNoVigProbability(), latest.getObservedAt(), startAt);
        }
    }

    private record BootstrapStability(boolean passed,
                                      int samples,
                                      double constantSkillLower95,
                                      double eloSkillLower95,
                                      double recentFormSkillLower95) {
    }

    private record ClusterSkillBounds(double constantLower95,
                                      double eloLower95,
                                      double recentLower95,
                                      boolean valid) {
        private static ClusterSkillBounds invalid() {
            return new ClusterSkillBounds(-1.0, -1.0, -1.0, false);
        }
    }

    private record SwapInvariantAudit(int trials,
                                      int failures,
                                      double maxProbabilityError,
                                      boolean passed) {
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

    private record RegistryReleaseMetadata(String artifactChecksum,
                                           String featureSchemaChecksum,
                                           String promotionStatus,
                                           String promotionReason) {
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

        private CandidateMetrics withCalibrationMethod(String method) {
            this.calibrationMethod = method;
            return this;
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
                    BASE_FEATURE_NAMES[12],
                    "Recent Form Delta^3", "Glicko Probability Delta^3", "Rating Delta^3",
                    "H2H × Recent × OppAdj", "Recent × |OppAdj|", "Elo × |Glicko Delta|"
            };
            return new FeatureSet(expandedNames, x -> new double[]{
                    x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8], x[9], x[10], x[11],
                    x[12],
                    x[1] * x[1] * x[1], x[5] * x[5] * x[5], x[6] * x[6] * x[6],
                    x[0] * x[1] * x[2], x[1] * Math.abs(x[2]), x[4] * Math.abs(x[5])
            });
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
            double forward = directionalProbability(baseFeatures);
            double reverse = directionalProbability(negate(baseFeatures));
            return clamp01(0.5 * (forward + (1.0 - reverse)));
        }

        private double directionalProbability(double[] baseFeatures) {
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

        private String calibrationMethod() {
            return calibrator == null ? "NONE" : calibrator.method;
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
            double[] reversed = transform.apply(negate(baseFeatures));
            List<MatchupAnalysisDto.FeatureContributionDto> out = new ArrayList<>();
            for (int j = 0; j < x.length; j++) {
                double forward = weights[j + 1] * standardized(x[j], means[j], stds[j]);
                double reverse = weights[j + 1] * standardized(reversed[j], means[j], stds[j]);
                double contribution = 0.5 * (forward - reverse);
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
                payload.put("calibratorMethod", calibrator.method);
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
            double forward = directionalProbability(baseFeatures);
            double reverse = directionalProbability(negate(baseFeatures));
            return clamp01(0.5 * (forward + (1.0 - reverse)));
        }

        private double directionalProbability(double[] baseFeatures) {
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
                double[] neutralBase = Arrays.copyOf(baseFeatures, baseFeatures.length);
                if (i < neutralBase.length) {
                    neutralBase[i] = 0.0;
                } else {
                    // Expanded odd features are derived from the base vector;
                    // their attribution is already represented by their
                    // originating deltas and is left at zero here.
                    out.add(new MatchupAnalysisDto.FeatureContributionDto(featureNames[i], 0.0));
                    continue;
                }
                double pertProb = predict(neutralBase);
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
            payload.put("trees", stumps.stream().map(stump -> Map.of(
                    "featureIndex", stump.featureIndex,
                    "threshold", stump.threshold,
                    "leftProbability", stump.leftProbability,
                    "rightProbability", stump.rightProbability
            )).toList());
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
        private final String method;

        private PlattCalibrator(double a, double b, String method) {
            this.a = a;
            this.b = b;
            this.method = StringUtils.hasText(method) ? method : "PLATT_LEGACY";
        }

        private double apply(double logit) {
            return clamp01(sigmoid(a * logit + b));
        }
    }
}
