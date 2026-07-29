package com.ttl.tabletennis.prediction.staking;

import com.ttl.tabletennis.domain.PaperTradeLearningSample;
import com.ttl.tabletennis.repository.PaperTradeLearningSampleRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 06 item 7 — rolling 7-day CLV gauge.
 *
 * <p>Computes true closing-line CLV when a {@code closingDecimalOdds}
 * snapshot has been captured on the
 * {@link PaperTradeLearningSample}, and falls back to a
 * {@code rolling7DayPnL / rollingStake} proxy for samples missing the
 * closing snapshot. The gauge is published as
 * {@code ttl_staking_clv_7d}.
 *
 * <p>True per-bet CLV is computed from implied probabilities:
 * {@code (impliedAtClose - impliedAtBet) / impliedAtBet}. Positive means
 * we secured a better price than the market settled at — i.e. we got
 * value the market eventually agreed with. The window aggregate is the
 * stake-weighted mean of per-bet CLV across the 7-day window.
 *
 * <p>Two siblings ship alongside:
 *
 * <ul>
 *   <li>{@code ttl_staking_clv_7d_samples} — total settled samples in window.</li>
 *   <li>{@code ttl_staking_clv_7d_coverage} — fraction of those samples that
 *       had a closing-line snapshot attached. Reaches 1.0 once the §5 plumbing
 *       has accumulated enough history; reads &lt; 1.0 while older rows are
 *       still on the PnL/stake proxy.</li>
 * </ul>
 *
 * <p>The {@code CLVNegative7Day} alert keeps the same expression
 * ({@code ttl_staking_clv_7d < 0}). The semantics tighten with coverage.
 */
@Component
public class StakingClvWatcher {

    public static final String METRIC_CLV = "ttl.staking.clv_7d";
    public static final String METRIC_SAMPLES = "ttl.staking.clv_7d_samples";
    public static final String METRIC_COVERAGE = "ttl.staking.clv_7d_coverage";

    private static final Logger log = LoggerFactory.getLogger(StakingClvWatcher.class);
    private static final long WINDOW_DAYS = 7;

    private final PaperTradeLearningSampleRepository repository;
    private final Clock clock;
    private final AtomicReference<Double> clvGauge = new AtomicReference<>(0.0);
    private final AtomicReference<Double> sampleGauge = new AtomicReference<>(0.0);
    private final AtomicReference<Double> coverageGauge = new AtomicReference<>(0.0);

    @Autowired
    public StakingClvWatcher(PaperTradeLearningSampleRepository repository,
                             MeterRegistry meterRegistry) {
        this(repository, meterRegistry, Clock.systemUTC());
    }

    StakingClvWatcher(PaperTradeLearningSampleRepository repository,
                      MeterRegistry meterRegistry,
                      Clock clock) {
        this.repository = repository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        if (meterRegistry != null) {
            Gauge.builder(METRIC_CLV, clvGauge, ref -> ref.get() == null ? 0.0 : ref.get())
                    .description("Rolling 7-day CLV — stake-weighted (impliedClose-impliedTaken)/impliedTaken when closing snapshot present, PnL/stake otherwise")
                    .register(meterRegistry);
            Gauge.builder(METRIC_SAMPLES, sampleGauge, ref -> ref.get() == null ? 0.0 : ref.get())
                    .description("Number of settled samples in the rolling 7-day window")
                    .register(meterRegistry);
            Gauge.builder(METRIC_COVERAGE, coverageGauge, ref -> ref.get() == null ? 0.0 : ref.get())
                    .description("Fraction of rolling-window samples with a closing-line snapshot attached (0..1)")
                    .register(meterRegistry);
        }
    }

    /** Snapshot computed at scheduled tick; exposed as a separate type so
     *  the gauge update path and the test path share the same data. */
    public record ClvSnapshot(double clv,
                              int sampleCount,
                              double totalStake,
                              double totalPnL,
                              int closingLineSamples,
                              double coverageRatio) {
        public static ClvSnapshot empty() {
            return new ClvSnapshot(0.0, 0, 0.0, 0.0, 0, 0.0);
        }
    }

    @Scheduled(fixedDelayString = "${ttl.staking.clv.refreshFixedDelayMs:60000}")
    public ClvSnapshot refresh() {
        if (repository == null) {
            update(ClvSnapshot.empty());
            return ClvSnapshot.empty();
        }
        ClvSnapshot snapshot;
        try {
            LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(WINDOW_DAYS);
            List<PaperTradeLearningSample> rows = repository.findBySettledAtAfter(cutoff);
            snapshot = compute(rows);
        } catch (RuntimeException ex) {
            log.warn("[staking-clv] refresh failed: {}", ex.getMessage());
            snapshot = ClvSnapshot.empty();
        }
        update(snapshot);
        return snapshot;
    }

    public double currentClv() {
        Double value = clvGauge.get();
        return value == null ? 0.0 : value;
    }

    public int currentSampleCount() {
        Double value = sampleGauge.get();
        return value == null ? 0 : value.intValue();
    }

    static ClvSnapshot compute(List<PaperTradeLearningSample> rows) {
        if (rows == null || rows.isEmpty()) {
            return ClvSnapshot.empty();
        }
        double stakeSum = 0.0;
        double pnlSum = 0.0;
        // Stake-weighted numerator for the published CLV metric.
        // For samples WITH a closing-line snapshot we use true CLV
        // (implied(close) - implied(taken)) / implied(taken).
        // For samples WITHOUT one we fall back to per-bet PnL/stake so the
        // metric still trends sensibly while §5 backfill is in flight.
        double clvNumerator = 0.0;
        int count = 0;
        int closingLineSamples = 0;
        for (PaperTradeLearningSample row : rows) {
            if (row == null) {
                continue;
            }
            double stake = row.getStake();
            if (stake <= 0.0) {
                continue;
            }
            stakeSum += stake;
            pnlSum += row.getProfitLoss();
            double perBetClv = perBetClv(row);
            clvNumerator += stake * perBetClv;
            if (hasClosingLine(row)) {
                closingLineSamples++;
            }
            count++;
        }
        if (stakeSum <= 0.0 || count == 0) {
            return ClvSnapshot.empty();
        }
        double clv = clvNumerator / stakeSum;
        double coverageRatio = (double) closingLineSamples / count;
        return new ClvSnapshot(clv, count, stakeSum, pnlSum, closingLineSamples, coverageRatio);
    }

    /**
     * Per-bet CLV. Uses true (impliedClose − impliedTaken) / impliedTaken when
     * the closing-line snapshot is present and both prices are well-formed.
     * Otherwise falls back to {@code profitLoss / stake} so older rows still
     * contribute a sensible signal to the rolling gauge.
     */
    private static double perBetClv(PaperTradeLearningSample row) {
        Double closingDecimal = row.getClosingDecimalOdds();
        double impliedAtBet = row.getImpliedProbability();
        if (closingDecimal != null
                && Double.isFinite(closingDecimal)
                && closingDecimal > 1.0
                && Double.isFinite(impliedAtBet)
                && impliedAtBet > 0.0) {
            double impliedAtClose = 1.0 / closingDecimal;
            return (impliedAtClose - impliedAtBet) / impliedAtBet;
        }
        double stake = row.getStake();
        return stake <= 0.0 ? 0.0 : row.getProfitLoss() / stake;
    }

    private static boolean hasClosingLine(PaperTradeLearningSample row) {
        Double closing = row.getClosingDecimalOdds();
        return closing != null && Double.isFinite(closing) && closing > 1.0;
    }

    private void update(ClvSnapshot snapshot) {
        clvGauge.set(snapshot.clv());
        sampleGauge.set((double) snapshot.sampleCount());
        coverageGauge.set(snapshot.coverageRatio());
    }
}
