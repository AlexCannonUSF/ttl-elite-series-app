package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.ModelLearningAuditDto;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ModelLearningAuditService {

    private static final double EPS = 1.0e-9;
    private static final double HALF_LIFE_DAYS = 60.0;

    private final PaperTradeLearningSampleRepository learningRepository;
    private final TrackedMatchObservationRepository observationRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelLearningAuditService(PaperTradeLearningSampleRepository learningRepository,
                                     TrackedMatchObservationRepository observationRepository) {
        this(learningRepository, observationRepository, Clock.systemUTC());
    }

    ModelLearningAuditService(PaperTradeLearningSampleRepository learningRepository,
                              TrackedMatchObservationRepository observationRepository,
                              Clock clock) {
        this.learningRepository = learningRepository;
        this.observationRepository = observationRepository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public ModelLearningAuditDto snapshot(int requestedWindowDays) {
        int windowDays = Math.max(7, Math.min(730, requestedWindowDays));
        Instant generatedAt = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(generatedAt, ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusDays(windowDays);
        List<PaperTradeLearningSample> all = safeAll(cutoff);
        List<PaperTradeLearningSample> eligible = all.stream()
                .filter(PaperTradeLearningSample::isLearningEligible)
                .filter(this::binaryOutcome)
                .toList();

        Map<String, Integer> exclusions = new LinkedHashMap<>();
        all.stream()
                .filter(row -> row == null || !row.isLearningEligible() || !binaryOutcome(row))
                .map(this::exclusionReason)
                .forEach(reason -> exclusions.merge(reason, 1, Integer::sum));
        int nonBinary = exclusions.getOrDefault("NON_BINARY_OUTCOME", 0);
        int lowConfidence = exclusions.entrySet().stream()
                .filter(entry -> entry.getKey().contains("LOW_CONFIDENCE"))
                .mapToInt(Map.Entry::getValue)
                .sum();
        double coverage = all.isEmpty() ? 0.0 : eligible.size() * 100.0 / all.size();
        List<ModelLearningAuditDto.ExclusionReasonCountDto> exclusionReasons = exclusions.entrySet().stream()
                .map(entry -> new ModelLearningAuditDto.ExclusionReasonCountDto(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(ModelLearningAuditDto.ExclusionReasonCountDto::count).reversed()
                        .thenComparing(ModelLearningAuditDto.ExclusionReasonCountDto::reason))
                .toList();

        return new ModelLearningAuditDto(
                generatedAt,
                windowDays,
                new ModelLearningAuditDto.OutcomeQualityDto(
                        all.size(),
                        eligible.size(),
                        Math.max(0, all.size() - eligible.size()),
                        eligible.size(),
                        lowConfidence,
                        nonBinary,
                        round2(coverage),
                        exclusionReasons
                ),
                calibration(eligible, now),
                segments(eligible, now, PaperTradeLearningSample::getTopTrigger),
                segments(eligible, now, PaperTradeLearningSample::getPriceRegime),
                factors(eligible, now),
                scoreRules(cutoff),
                clv(eligible)
        );
    }

    private ModelLearningAuditDto.CalibrationEvidenceDto calibration(
            List<PaperTradeLearningSample> rows,
            LocalDateTime now) {
        WeightedSummary summary = summarize(rows, now);
        return new ModelLearningAuditDto.CalibrationEvidenceDto(
                rows.size(),
                round2(summary.effectiveN()),
                round4(summary.meanPredicted()),
                round4(summary.observed()),
                round4(summary.meanPredicted() - summary.observed()),
                round4(summary.brier()),
                round4(summary.logLoss())
        );
    }

    private List<ModelLearningAuditDto.SegmentPerformanceDto> segments(
            List<PaperTradeLearningSample> rows,
            LocalDateTime now,
            java.util.function.Function<PaperTradeLearningSample, String> classifier) {
        Map<String, List<PaperTradeLearningSample>> groups = new LinkedHashMap<>();
        for (PaperTradeLearningSample row : rows) {
            String key = classifier.apply(row);
            key = StringUtils.hasText(key) ? key.trim() : "UNKNOWN";
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return groups.entrySet().stream()
                .map(entry -> {
                    WeightedSummary summary = summarize(entry.getValue(), now);
                    return new ModelLearningAuditDto.SegmentPerformanceDto(
                            entry.getKey(),
                            entry.getValue().size(),
                            round2(summary.effectiveN()),
                            round4(summary.observed()),
                            round4(summary.meanPredicted()),
                            round4(summary.meanPredicted() - summary.observed()),
                            round2(summary.roi() * 100.0)
                    );
                })
                .sorted(Comparator.comparingInt(ModelLearningAuditDto.SegmentPerformanceDto::rawSampleSize).reversed())
                .toList();
    }

    private List<ModelLearningAuditDto.FactorPerformanceDto> factors(
            List<PaperTradeLearningSample> rows,
            LocalDateTime now) {
        Map<String, FactorAccumulator> factors = new LinkedHashMap<>();
        for (PaperTradeLearningSample row : rows) {
            boolean flip = "P2".equalsIgnoreCase(row.getSideOrientation());
            boolean won = PaperTradeBet.STATUS_WON.equalsIgnoreCase(row.getStatus());
            double weight = weight(row, now);
            for (FactorValue factor : parseFactors(row.getFeatureContributions())) {
                double aligned = flip ? -factor.value() : factor.value();
                factors.computeIfAbsent(factor.name(), ignored -> new FactorAccumulator())
                        .add(aligned, won, weight);
            }
        }
        return factors.entrySet().stream()
                .map(entry -> entry.getValue().toDto(entry.getKey()))
                .sorted(Comparator.comparingInt(ModelLearningAuditDto.FactorPerformanceDto::rawSampleSize).reversed()
                        .thenComparing(ModelLearningAuditDto.FactorPerformanceDto::meanAbsoluteContribution,
                                Comparator.reverseOrder()))
                .toList();
    }

    private List<ModelLearningAuditDto.ScoreRulePerformanceDto> scoreRules(LocalDateTime cutoff) {
        List<TrackedMatchObservation> observations;
        try {
            observations = observationRepository
                    .findByProvisionalResolvedAtAfterAndProvisionalCorrectIsNotNull(cutoff);
        } catch (RuntimeException ex) {
            observations = List.of();
        }
        Map<String, ScoreAccumulator> groups = new LinkedHashMap<>();
        for (TrackedMatchObservation observation : observations) {
            if (observation == null || observation.getProvisionalCorrect() == null) {
                continue;
            }
            String method = StringUtils.hasText(observation.getProvisionalOutcomeMethod())
                    ? observation.getProvisionalOutcomeMethod()
                    : "UNKNOWN";
            groups.computeIfAbsent(method, ignored -> new ScoreAccumulator()).add(observation);
        }
        return groups.entrySet().stream()
                .map(entry -> entry.getValue().toDto(entry.getKey()))
                .sorted(Comparator.comparingInt(ModelLearningAuditDto.ScoreRulePerformanceDto::resolvedObservations)
                        .reversed())
                .toList();
    }

    private ModelLearningAuditDto.ClvEvidenceDto clv(List<PaperTradeLearningSample> rows) {
        double stake = 0.0;
        double weightedClv = 0.0;
        int covered = 0;
        for (PaperTradeLearningSample row : rows) {
            Double close = row.getClosingDecimalOdds();
            if (close == null || !Double.isFinite(close) || close <= 1.0
                    || row.getImpliedProbability() <= 0.0 || row.getStake() <= 0.0) {
                continue;
            }
            double perBet = ((1.0 / close) - row.getImpliedProbability()) / row.getImpliedProbability();
            stake += row.getStake();
            weightedClv += row.getStake() * perBet;
            covered++;
        }
        return new ModelLearningAuditDto.ClvEvidenceDto(
                rows.size(),
                covered,
                rows.isEmpty() ? 0.0 : round2(covered * 100.0 / rows.size()),
                stake <= EPS ? null : round2(weightedClv * 100.0 / stake)
        );
    }

    private WeightedSummary summarize(List<PaperTradeLearningSample> rows, LocalDateTime now) {
        double sumW = 0.0;
        double sumW2 = 0.0;
        double predicted = 0.0;
        double observed = 0.0;
        double brier = 0.0;
        double logLoss = 0.0;
        double stake = 0.0;
        double pnl = 0.0;
        for (PaperTradeLearningSample row : rows) {
            double w = weight(row, now);
            double p = clamp(row.getModelProbability(), 0.001, 0.999);
            double y = PaperTradeBet.STATUS_WON.equalsIgnoreCase(row.getStatus()) ? 1.0 : 0.0;
            sumW += w;
            sumW2 += w * w;
            predicted += p * w;
            observed += y * w;
            brier += Math.pow(p - y, 2) * w;
            logLoss += -(y * Math.log(p) + (1.0 - y) * Math.log(1.0 - p)) * w;
            stake += Math.max(0.0, row.getStake()) * w;
            pnl += row.getProfitLoss() * w;
        }
        if (sumW <= EPS) {
            return WeightedSummary.empty();
        }
        return new WeightedSummary(
                sumW2 <= EPS ? 0.0 : sumW * sumW / sumW2,
                predicted / sumW,
                observed / sumW,
                brier / sumW,
                logLoss / sumW,
                stake <= EPS ? 0.0 : pnl / stake
        );
    }

    private double weight(PaperTradeLearningSample row, LocalDateTime now) {
        LocalDateTime event = row.getEventOccurredAt() != null ? row.getEventOccurredAt()
                : row.getPlacedAt() != null ? row.getPlacedAt()
                : row.getSettledAt();
        long days = event == null ? 0L
                : Math.max(0L, ChronoUnit.DAYS.between(event.toLocalDate(), now.toLocalDate()));
        double recency = Math.pow(0.5, days / HALF_LIFE_DAYS);
        return recency * clamp(row.getSettlementConfidence(), 0.0, 1.0);
    }

    private boolean binaryOutcome(PaperTradeLearningSample row) {
        return row != null && (PaperTradeBet.STATUS_WON.equalsIgnoreCase(row.getStatus())
                || PaperTradeBet.STATUS_LOST.equalsIgnoreCase(row.getStatus()));
    }

    private String exclusionReason(PaperTradeLearningSample row) {
        if (row == null || !binaryOutcome(row)) {
            return "NON_BINARY_OUTCOME";
        }
        if (StringUtils.hasText(row.getLearningExclusionReason())) {
            return row.getLearningExclusionReason().trim().toUpperCase(Locale.ROOT);
        }
        return "LEGACY_LOW_CONFIDENCE";
    }

    private List<FactorValue> parseFactors(String encoded) {
        if (!StringUtils.hasText(encoded)) {
            return List.of();
        }
        List<FactorValue> out = new ArrayList<>();
        for (String token : encoded.split("\\|")) {
            int split = token.lastIndexOf('=');
            if (split <= 0 || split >= token.length() - 1) {
                continue;
            }
            try {
                out.add(new FactorValue(token.substring(0, split), Double.parseDouble(token.substring(split + 1))));
            } catch (NumberFormatException ignored) {
                // Best-effort telemetry parser.
            }
        }
        return out;
    }

    private List<PaperTradeLearningSample> safeAll(LocalDateTime cutoff) {
        try {
            List<PaperTradeLearningSample> rows = learningRepository.findLearningEvidenceAfter(cutoff);
            return rows == null ? List.of() : rows;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record WeightedSummary(double effectiveN,
                                   double meanPredicted,
                                   double observed,
                                   double brier,
                                   double logLoss,
                                   double roi) {
        static WeightedSummary empty() {
            return new WeightedSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private record FactorValue(String name, double value) {
    }

    private static final class FactorAccumulator {
        private int count;
        private int wins;
        private int losses;
        private double sumW;
        private double sumW2;
        private double absolute;
        private double directionCorrect;
        private double winContribution;
        private double lossContribution;

        void add(double contribution, boolean won, double weight) {
            count++;
            sumW += weight;
            sumW2 += weight * weight;
            absolute += Math.abs(contribution) * weight;
            if ((won && contribution >= 0.0) || (!won && contribution < 0.0)) {
                directionCorrect += weight;
            }
            if (won) {
                wins++;
                winContribution += contribution;
            } else {
                losses++;
                lossContribution += contribution;
            }
        }

        ModelLearningAuditDto.FactorPerformanceDto toDto(String factor) {
            return new ModelLearningAuditDto.FactorPerformanceDto(
                    factor,
                    count,
                    round2(sumW2 <= EPS ? 0.0 : sumW * sumW / sumW2),
                    round4(sumW <= EPS ? 0.0 : absolute / sumW),
                    round4(sumW <= EPS ? 0.0 : directionCorrect / sumW),
                    round4(wins == 0 ? 0.0 : winContribution / wins),
                    round4(losses == 0 ? 0.0 : lossContribution / losses)
            );
        }
    }

    private static final class ScoreAccumulator {
        private int count;
        private int correct;
        private double confidence;

        void add(TrackedMatchObservation observation) {
            count++;
            if (Boolean.TRUE.equals(observation.getProvisionalCorrect())) {
                correct++;
            }
            confidence += observation.getProvisionalOutcomeConfidence() == null
                    ? 0.5
                    : observation.getProvisionalOutcomeConfidence();
        }

        ModelLearningAuditDto.ScoreRulePerformanceDto toDto(String method) {
            double accuracy = count == 0 ? 0.0 : correct / (double) count;
            double stated = count == 0 ? 0.0 : confidence / count;
            return new ModelLearningAuditDto.ScoreRulePerformanceDto(
                    method,
                    count,
                    correct,
                    round4(accuracy),
                    round4(stated),
                    round4(stated - accuracy)
            );
        }
    }
}
