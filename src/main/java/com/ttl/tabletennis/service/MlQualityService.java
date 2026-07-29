package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.dto.MlQualityDto;
import com.ttl.tabletennis.dto.ModelTrainingReportDto;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregates Phase 05 item 10 reliability + drift signals.
 *
 * <p>Pairs the latest training-time calibration curve with a rolling
 * window over settled paper-trade decisions so operators can see drift
 * in calibration, prediction distribution, and daily volume.
 */
@Service
public class MlQualityService {

    public static final int DEFAULT_WINDOW_DAYS = 14;
    public static final int DEFAULT_BIN_COUNT = 10;
    static final String STATUS_WON = "WON";
    static final String STATUS_LOST = "LOST";
    /**
     * #115 — Bet statuses that are NOT predictions resolved into outcomes.
     * VOIDED bets had their stake returned (e.g. settlement timed out
     * because the score feed never delivered a terminal-state observation,
     * or the match was abandoned). PUSHED bets are pure draws.
     *
     * <p>Before this fix, {@code MlQualityService} coerced every non-WON
     * status to {@code y=0}, inflating ECE and Brier on void-heavy days
     * (e.g. Session 65 with 6/7 closures being voids fed bogus "misses"
     * into the calibration buckets). The right treatment is to omit them
     * from the calibration sample entirely — VOID/PUSH are missing data,
     * not negative outcomes.
     */
    private static final java.util.Set<String> NON_RESOLVED_STATUSES =
            java.util.Set.of("VOIDED", "VOID", "PUSHED", "PUSH");

    private final PredictionFacade predictionFacade;
    private final PaperTradeLearningSampleRepository learningSampleRepository;
    private final Clock clock;

    @Autowired
    public MlQualityService(PredictionFacade predictionFacade,
                            PaperTradeLearningSampleRepository learningSampleRepository) {
        this(predictionFacade, learningSampleRepository, Clock.systemUTC());
    }

    MlQualityService(PredictionFacade predictionFacade,
                     PaperTradeLearningSampleRepository learningSampleRepository,
                     Clock clock) {
        this.predictionFacade = predictionFacade;
        this.learningSampleRepository = learningSampleRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public MlQualityDto snapshot() {
        return snapshot(DEFAULT_WINDOW_DAYS, DEFAULT_BIN_COUNT);
    }

    public MlQualityDto snapshot(int windowDays, int binCount) {
        if (windowDays <= 0) {
            throw new IllegalArgumentException("windowDays must be positive");
        }
        if (binCount <= 1) {
            throw new IllegalArgumentException("binCount must be > 1");
        }

        Instant now = clock.instant();
        LocalDateTime cutoff = LocalDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(windowDays);

        List<PaperTradeLearningSample> recentSettled = safeRecentSettled(cutoff);

        MlQualityDto.ReliabilitySnapshot training = trainingReliability();
        MlQualityDto.ReliabilitySnapshot recent = recentReliability(recentSettled, binCount);
        List<MlQualityDto.HistogramBin> histogram = probabilityHistogram(recentSettled, binCount);
        List<MlQualityDto.DailyCount> dailyVolume = dailyVolume(recentSettled, cutoff, windowDays);
        MlQualityDto.DriftSummary drift = driftSummary(training, recent);

        return new MlQualityDto(
                windowDays,
                now,
                training,
                recent,
                histogram,
                dailyVolume,
                drift
        );
    }

    // ---- training-time reliability ---------------------------------------

    private MlQualityDto.ReliabilitySnapshot trainingReliability() {
        try {
            ModelTrainingReportDto report = predictionFacade.latestTrainingReport();
            if (report == null || report.calibrationCurve() == null || report.calibrationCurve().isEmpty()) {
                return emptySnapshot("training");
            }
            List<MlQualityDto.ReliabilityBin> bins = report.calibrationCurve().stream()
                    .map(bin -> new MlQualityDto.ReliabilityBin(
                            bin.lowerBound(),
                            bin.upperBound(),
                            bin.count(),
                            bin.meanPredicted(),
                            bin.observedRate()))
                    .toList();
            int total = bins.stream().mapToInt(MlQualityDto.ReliabilityBin::count).sum();
            return new MlQualityDto.ReliabilitySnapshot(
                    "training",
                    total,
                    computeEce(bins),
                    computeMaxBinDeviation(bins),
                    null,
                    bins
            );
        } catch (RuntimeException e) {
            return emptySnapshot("training");
        }
    }

    // ---- recent reliability from settled paper-trades --------------------

    private MlQualityDto.ReliabilitySnapshot recentReliability(List<PaperTradeLearningSample> samples,
                                                                int binCount) {
        if (samples == null || samples.isEmpty()) {
            return emptySnapshot("recent");
        }
        List<double[]> rows = new ArrayList<>(samples.size());
        for (PaperTradeLearningSample sample : samples) {
            if (sample == null || sample.getStatus() == null) {
                continue;
            }
            String status = sample.getStatus();
            // #115: VOID and PUSH never resolved into a model-evaluable outcome.
            // Treating them as y=0 would inflate calibration error during periods
            // when the score feed mis-fires (the very scenario this dashboard
            // exists to detect — circular).
            if (NON_RESOLVED_STATUSES.contains(status.toUpperCase(java.util.Locale.ROOT))) {
                continue;
            }
            double p = clamp01(sample.getModelProbability());
            double y = STATUS_WON.equalsIgnoreCase(status) ? 1.0 : 0.0;
            rows.add(new double[]{p, y});
        }
        if (rows.isEmpty()) {
            return emptySnapshot("recent");
        }
        rows.sort((a, b) -> Double.compare(a[0], b[0]));
        List<MlQualityDto.ReliabilityBin> bins = equalMassBins(rows, binCount);
        return new MlQualityDto.ReliabilitySnapshot(
                "recent",
                rows.size(),
                computeEce(bins),
                computeMaxBinDeviation(bins),
                computeBrier(rows),
                bins
        );
    }

    static List<MlQualityDto.ReliabilityBin> equalMassBins(List<double[]> sortedRows, int binCount) {
        int n = sortedRows.size();
        if (n == 0) {
            return List.of();
        }
        int per = Math.max(1, n / binCount);
        List<MlQualityDto.ReliabilityBin> out = new ArrayList<>(binCount);
        int idx = 0;
        for (int b = 0; b < binCount && idx < n; b++) {
            int end = (b == binCount - 1) ? n : Math.min(n, idx + per);
            if (end <= idx) {
                break;
            }
            double sumP = 0.0;
            double sumY = 0.0;
            int count = end - idx;
            double low = sortedRows.get(idx)[0];
            double high = sortedRows.get(end - 1)[0];
            for (int k = idx; k < end; k++) {
                sumP += sortedRows.get(k)[0];
                sumY += sortedRows.get(k)[1];
            }
            out.add(new MlQualityDto.ReliabilityBin(
                    low,
                    high,
                    count,
                    sumP / count,
                    sumY / count
            ));
            idx = end;
        }
        return out;
    }

    // ---- histogram & volume ---------------------------------------------

    private List<MlQualityDto.HistogramBin> probabilityHistogram(List<PaperTradeLearningSample> samples, int binCount) {
        int[] counts = new int[binCount];
        for (PaperTradeLearningSample sample : samples) {
            if (sample == null) {
                continue;
            }
            double p = clamp01(sample.getModelProbability());
            int bucket = Math.min(binCount - 1, (int) Math.floor(p * binCount));
            counts[bucket]++;
        }
        List<MlQualityDto.HistogramBin> bins = new ArrayList<>(binCount);
        for (int i = 0; i < binCount; i++) {
            double lo = (double) i / binCount;
            double hi = (i + 1.0) / binCount;
            bins.add(new MlQualityDto.HistogramBin(lo, hi, counts[i]));
        }
        return bins;
    }

    private List<MlQualityDto.DailyCount> dailyVolume(List<PaperTradeLearningSample> samples,
                                                       LocalDateTime cutoff,
                                                       int windowDays) {
        Map<LocalDate, Integer> bucketed = new TreeMap<>();
        for (PaperTradeLearningSample sample : samples) {
            if (sample == null || sample.getSettledAt() == null) {
                continue;
            }
            LocalDate day = sample.getSettledAt().toLocalDate();
            bucketed.merge(day, 1, Integer::sum);
        }
        LocalDate start = cutoff.toLocalDate();
        Map<LocalDate, Integer> filled = new TreeMap<>();
        for (int d = 0; d <= windowDays; d++) {
            filled.put(start.plusDays(d), 0);
        }
        filled.putAll(bucketed);
        List<MlQualityDto.DailyCount> series = new ArrayList<>(filled.size());
        filled.forEach((day, count) -> series.add(new MlQualityDto.DailyCount(day, count == null ? 0 : count)));
        return series;
    }

    // ---- drift summary --------------------------------------------------

    private MlQualityDto.DriftSummary driftSummary(MlQualityDto.ReliabilitySnapshot training,
                                                    MlQualityDto.ReliabilitySnapshot recent) {
        Double trainingEce = training == null ? null : training.ece();
        Double recentEce = recent == null ? null : recent.ece();
        Double eceDelta = subtractOrNull(recentEce, trainingEce);

        Double trainingMeanP = training == null ? null : weightedMean(training.bins(), MlQualityDto.ReliabilityBin::meanPredicted);
        Double recentMeanP = recent == null ? null : weightedMean(recent.bins(), MlQualityDto.ReliabilityBin::meanPredicted);
        Double meanPredictedDelta = subtractOrNull(recentMeanP, trainingMeanP);

        Double trainingMeanObs = training == null ? null : weightedMean(training.bins(), MlQualityDto.ReliabilityBin::observedRate);
        Double recentMeanObs = recent == null ? null : weightedMean(recent.bins(), MlQualityDto.ReliabilityBin::observedRate);
        Double meanObservedDelta = subtractOrNull(recentMeanObs, trainingMeanObs);

        return new MlQualityDto.DriftSummary(
                eceDelta,
                meanPredictedDelta,
                meanObservedDelta,
                classifySeverity(eceDelta, meanObservedDelta)
        );
    }

    static String classifySeverity(Double eceDelta, Double meanObservedDelta) {
        if (eceDelta == null && meanObservedDelta == null) {
            return "UNKNOWN";
        }
        double ed = eceDelta == null ? 0.0 : Math.abs(eceDelta);
        double od = meanObservedDelta == null ? 0.0 : Math.abs(meanObservedDelta);
        if (ed >= 0.04 || od >= 0.05) {
            return "RED";
        }
        if (ed >= 0.02 || od >= 0.025) {
            return "AMBER";
        }
        return "GREEN";
    }

    static Double subtractOrNull(Double a, Double b) {
        if (a == null || b == null) {
            return null;
        }
        return a - b;
    }

    static Double weightedMean(List<MlQualityDto.ReliabilityBin> bins,
                                java.util.function.ToDoubleFunction<MlQualityDto.ReliabilityBin> getter) {
        if (bins == null || bins.isEmpty()) {
            return null;
        }
        double total = 0.0;
        double weight = 0.0;
        for (MlQualityDto.ReliabilityBin bin : bins) {
            int n = bin.count();
            if (n <= 0) {
                continue;
            }
            total += getter.applyAsDouble(bin) * n;
            weight += n;
        }
        if (weight == 0.0) {
            return null;
        }
        return total / weight;
    }

    // ---- math helpers ---------------------------------------------------

    static Double computeEce(List<MlQualityDto.ReliabilityBin> bins) {
        if (bins == null || bins.isEmpty()) {
            return null;
        }
        int total = bins.stream().mapToInt(MlQualityDto.ReliabilityBin::count).sum();
        if (total == 0) {
            return null;
        }
        double weighted = 0.0;
        for (MlQualityDto.ReliabilityBin bin : bins) {
            double weight = (double) bin.count() / total;
            weighted += weight * Math.abs(bin.meanPredicted() - bin.observedRate());
        }
        return weighted;
    }

    static Double computeMaxBinDeviation(List<MlQualityDto.ReliabilityBin> bins) {
        if (bins == null || bins.isEmpty()) {
            return null;
        }
        double max = 0.0;
        for (MlQualityDto.ReliabilityBin bin : bins) {
            max = Math.max(max, Math.abs(bin.meanPredicted() - bin.observedRate()));
        }
        return max;
    }

    static Double computeBrier(List<double[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (double[] row : rows) {
            sum += (row[0] - row[1]) * (row[0] - row[1]);
        }
        return sum / rows.size();
    }

    private List<PaperTradeLearningSample> safeRecentSettled(LocalDateTime cutoff) {
        if (learningSampleRepository == null) {
            return List.of();
        }
        try {
            List<PaperTradeLearningSample> samples = learningSampleRepository.findBySettledAtAfter(cutoff);
            return samples == null ? List.of() : samples;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private MlQualityDto.ReliabilitySnapshot emptySnapshot(String label) {
        return new MlQualityDto.ReliabilitySnapshot(label, 0, null, null, null, List.of());
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
