package com.ttl.tabletennis.service;

import com.ttl.tabletennis.dto.MatchupAnalysisDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.dto.PredictionPanelDto;
import com.ttl.tabletennis.prediction.calibration.MondrianGroupKey;
import com.ttl.tabletennis.prediction.calibration.MondrianSplitConformal;
import com.ttl.tabletennis.prediction.conformal.ConformalPredictor;
import com.ttl.tabletennis.prediction.conformal.ConformalResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link PredictionPanelDto} consumed by the v3 prediction
 * panel UI (Phase 05 item 9).
 *
 * <p>The panel needs three blocks per spec §10:
 *
 * <ul>
 *   <li>Probability + interval (v2 prediction snapshot).</li>
 *   <li>SHAP top-K contributions (existing feature contributions on the
 *       v2 snapshot, trimmed to the K largest by absolute value).</li>
 *   <li>Heuristic uncertainty envelope until an evaluated calibration
 *       bundle is available end-to-end.</li>
 * </ul>
 *
 * <p>The reliability curve is the calibration bin set from the latest
 * training report.
 */
@Service
public class PredictionPanelService {

    public static final int DEFAULT_TOP_K = 6;
    private static final double DEFAULT_FALLBACK_QUANTILE = 0.85;

    private final PredictionFacade predictionFacade;
    private final ConformalPredictor conformalPredictor;

    public PredictionPanelService(PredictionFacade predictionFacade,
                                  ConformalPredictor conformalPredictor) {
        this.predictionFacade = predictionFacade;
        this.conformalPredictor = conformalPredictor;
    }

    public PredictionPanelDto build(long player1Id, long player2Id, LocalDate asOfDate, String modelFamily) {
        return build(player1Id, player2Id, asOfDate, modelFamily, DEFAULT_TOP_K);
    }

    public PredictionPanelDto build(long player1Id, long player2Id, LocalDate asOfDate, String modelFamily, int topK) {
        if (player1Id == player2Id) {
            throw new IllegalArgumentException("player1Id and player2Id must differ");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }

        PredictionModelService.PredictionSnapshot snapshot = predictionFacade.predict(
                player1Id, player2Id, asOfDate, modelFamily);
        if (snapshot == null) {
            throw new IllegalStateException("PredictionFacade returned a null snapshot");
        }

        double pTop = clamp01(snapshot.player1Probability());
        ConformalResult conformal = conformalPredictor.predict(
                fallbackConformal(),
                pTop,
                new MondrianGroupKey(5, false, false)
        );

        List<MatchupAnalysisDto.FeatureContributionDto> contributions = trimContributions(
                snapshot.featureContributions(), topK);

        List<PredictionPanelDto.ReliabilityBinDto> reliabilityCurve = reliabilityFromReport();

        return new PredictionPanelDto(
                matchKey(player1Id, player2Id, asOfDate),
                player1Id,
                player2Id,
                snapshot.modelFamily(),
                snapshot.modelVersion(),
                snapshot.calibrationMethod(),
                new PredictionPanelDto.ProbabilityDto(
                        pTop,
                        clamp01(snapshot.player1ConfidenceLow()),
                        clamp01(snapshot.player1ConfidenceHigh())
                ),
                new PredictionPanelDto.ProbabilityDto(
                        clamp01(1.0 - pTop),
                        clamp01(1.0 - snapshot.player1ConfidenceHigh()),
                        clamp01(1.0 - snapshot.player1ConfidenceLow())
                ),
                conformalDto(conformal),
                contributions,
                reliabilityCurve,
                Instant.now()
        );
    }

    static String matchKey(long player1Id, long player2Id, LocalDate asOfDate) {
        long lo = Math.min(player1Id, player2Id);
        long hi = Math.max(player1Id, player2Id);
        return lo + "-" + hi + "@" + (asOfDate == null ? "latest" : asOfDate);
    }

    private MondrianSplitConformal fallbackConformal() {
        // Until CalibrationBundle threads end-to-end, use a service-level
        // fallback quantile so the panel always renders a sensible interval.
        return new MondrianSplitConformal(
                ConformalPredictor.DEFAULT_ALPHA,
                DEFAULT_FALLBACK_QUANTILE,
                Map.of(),
                Map.of(),
                "v3.0.0-service-fallback"
        );
    }

    private PredictionPanelDto.ConformalDto conformalDto(ConformalResult result) {
        return new PredictionPanelDto.ConformalDto(
                result.coverage(),
                result.alpha(),
                result.label().name(),
                result.intervalLow(),
                result.intervalHigh(),
                result.quantile(),
                "heuristic-uncalibrated",
                result.predictionSet().stream().map(Enum::name).sorted().toList(),
                result.groupKey()
        );
    }

    static List<MatchupAnalysisDto.FeatureContributionDto> trimContributions(
            List<MatchupAnalysisDto.FeatureContributionDto> source, int topK) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(c -> c != null && c.feature() != null && !Double.isNaN(c.contribution()))
                .sorted(Comparator.comparingDouble(
                        (MatchupAnalysisDto.FeatureContributionDto c) -> Math.abs(c.contribution())).reversed())
                .limit(topK)
                .toList();
    }

    private List<PredictionPanelDto.ReliabilityBinDto> reliabilityFromReport() {
        try {
            ModelTrainingReportDto report = predictionFacade.latestTrainingReport();
            if (report == null || report.calibrationCurve() == null) {
                return List.of();
            }
            return report.calibrationCurve().stream()
                    .map(bin -> new PredictionPanelDto.ReliabilityBinDto(
                            bin.lowerBound(),
                            bin.upperBound(),
                            bin.count(),
                            bin.meanPredicted(),
                            bin.observedRate()
                    ))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.5;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
