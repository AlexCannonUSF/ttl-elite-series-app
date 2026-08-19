package com.ttl.tabletennis.prediction.staking;

import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches the five §11 production-soak exit gates and publishes them
 * as Micrometer gauges + a snapshot DTO ({@link Soak11Status}).
 *
 * <p>The five gates (per the Phase 06 / 07 closeout):
 *
 * <ol>
 *   <li>Zero new {@code Bug-A}-style contradictions during the soak window.</li>
 *   <li>Staking v3 caps never breached during the soak.</li>
 *   <li>Rolling CLV ≥ {@code ttl.soak11.clvTargetRatio} (default 2.0%).</li>
 *   <li>{@code policy.yaml} hot-reload drill — at least 2 successful reloads
 *       during the soak, zero {@code RELOAD_FAILED}.</li>
 *   <li>Stream-CV coverage drill — at least 1 {@code SCORE_BACKED_ONLY} hold
 *       recorded during the soak.</li>
 * </ol>
 *
 * <p>{@code ttl.soak11.startAt} configures the soak start (ISO instant).
 * When unset, the soak is treated as "not started"; gates report observed
 * values but {@code overallPass} stays {@code false} until an operator
 * sets the start date.
 */
@Component
public class Soak11Monitor {

    public static final String METRIC_DAYS_ELAPSED = "ttl.soak11.days_elapsed";
    public static final String METRIC_GATE_CONTRADICTIONS = "ttl.soak11.gate_contradictions";
    public static final String METRIC_GATE_EXPOSURE = "ttl.soak11.gate_exposure_cap_breaches";
    public static final String METRIC_GATE_CLV = "ttl.soak11.gate_clv";
    public static final String METRIC_GATE_POLICY_RELOAD = "ttl.soak11.gate_policy_reload";
    public static final String METRIC_GATE_STREAM_CV = "ttl.soak11.gate_stream_cv_coverage";
    public static final String METRIC_OVERALL_PASS = "ttl.soak11.overall_pass";

    private static final Logger log = LoggerFactory.getLogger(Soak11Monitor.class);
    private static final String CONTRADICTION_KIND = "CONTRADICTION";
    private static final long SOAK_DAYS_REQUIRED = 14;
    private static final long MIN_POLICY_RELOADS = 2;

    private final SettlementDiffLogRepository diffLogRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private final AtomicReference<Double> daysElapsed = new AtomicReference<>(0.0);
    private final AtomicReference<Double> gateContradictions = new AtomicReference<>(0.0);
    private final AtomicReference<Double> gateExposure = new AtomicReference<>(0.0);
    private final AtomicReference<Double> gateClv = new AtomicReference<>(0.0);
    private final AtomicReference<Double> gatePolicyReload = new AtomicReference<>(0.0);
    private final AtomicReference<Double> gateStreamCv = new AtomicReference<>(0.0);
    private final AtomicReference<Double> overallPass = new AtomicReference<>(0.0);
    private final AtomicReference<Soak11Status> latest = new AtomicReference<>(Soak11Status.uninitialised());

    @Value("${ttl.soak11.startAt:}")
    private String configuredSoakStart;

    @Value("${ttl.soak11.clvTargetRatio:0.02}")
    private double clvTargetRatio;

    @Autowired
    public Soak11Monitor(SettlementDiffLogRepository diffLogRepository,
                         MeterRegistry meterRegistry) {
        this(diffLogRepository, meterRegistry, Clock.systemUTC());
    }

    Soak11Monitor(SettlementDiffLogRepository diffLogRepository,
                  MeterRegistry meterRegistry,
                  Clock clock) {
        this.diffLogRepository = diffLogRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        if (meterRegistry != null) {
            registerGauges(meterRegistry);
        }
    }

    private void registerGauges(MeterRegistry registry) {
        registerGauge(registry, METRIC_DAYS_ELAPSED, daysElapsed,
                "Days elapsed in the rolling §11 soak window (0 when not started)");
        registerGauge(registry, METRIC_GATE_CONTRADICTIONS, gateContradictions,
                "1 = no new contradictions since soak start; 0 = at least one new row");
        registerGauge(registry, METRIC_GATE_EXPOSURE, gateExposure,
                "1 = no exposure-cap breach since soak start; 0 = at least one");
        registerGauge(registry, METRIC_GATE_CLV, gateClv,
                "1 = rolling CLV >= target (default 2%); 0 otherwise");
        registerGauge(registry, METRIC_GATE_POLICY_RELOAD, gatePolicyReload,
                "1 = >=2 successful policy.yaml hot-reloads + 0 failures during soak; 0 otherwise");
        registerGauge(registry, METRIC_GATE_STREAM_CV, gateStreamCv,
                "1 = at least one SCORE_BACKED_ONLY hold during soak; 0 otherwise");
        registerGauge(registry, METRIC_OVERALL_PASS, overallPass,
                "1 when all five §11 exit gates pass and 14 days have elapsed");
    }

    private void registerGauge(MeterRegistry registry,
                               String name,
                               AtomicReference<Double> ref,
                               String description) {
        Gauge.builder(name, ref, r -> r.get() == null ? 0.0 : r.get())
                .description(description)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${ttl.soak11.refreshFixedDelayMs:60000}")
    public Soak11Status refresh() {
        try {
            Soak11Status status = computeNow();
            latest.set(status);
            daysElapsed.set((double) status.daysElapsed());
            gateContradictions.set(boolDouble(status.contradictions().passing()));
            gateExposure.set(boolDouble(status.exposureCapBreaches().passing()));
            gateClv.set(boolDouble(status.clv().passing()));
            gatePolicyReload.set(boolDouble(status.policyReloadDrill().passing()));
            gateStreamCv.set(boolDouble(status.streamCvCoverage().passing()));
            overallPass.set(boolDouble(status.allGreen()));
            return status;
        } catch (RuntimeException ex) {
            log.warn("[soak11] refresh failed: {}", ex.getMessage());
            return latest.get();
        }
    }

    public Soak11Status currentStatus() {
        return latest.get();
    }

    private Soak11Status computeNow() {
        Optional<Instant> soakStartOpt = parseSoakStart();
        Instant now = clock.instant();
        if (soakStartOpt.isEmpty()) {
            return Soak11Status.uninitialised().withNote(
                    "ttl.soak11.startAt not set — soak gates report observed values but overallPass stays false");
        }
        Instant soakStart = soakStartOpt.get();
        LocalDateTime soakStartLocal = LocalDateTime.ofInstant(soakStart, ZoneOffset.UTC);
        long daysElapsed = Math.max(0, Duration.between(soakStart, now).toDays());

        long newContradictions = diffLogRepository.countByDiffKindAndDecidedAtAfter(
                CONTRADICTION_KIND, soakStartLocal);
        GateStatus contradictions = new GateStatus(
                "contradictions",
                newContradictions == 0,
                newContradictions,
                0,
                newContradictions == 0
                        ? "no new contradictions since soak start"
                        : newContradictions + " new contradiction row(s) — triage in /v3/ops/diffs"
        );

        double capBreaches = sumCounterValue("ttl.staking.exposure_cap_breach_total");
        GateStatus exposure = new GateStatus(
                "exposureCapBreaches",
                capBreaches == 0,
                capBreaches,
                0,
                capBreaches == 0
                        ? "no exposure-cap breach"
                        : "lifetime breach counter at " + capBreaches + " (acceptable if all pre-soak — reset on next deploy)"
        );

        double clvValue = readGaugeValue("ttl.staking.clv_7d");
        GateStatus clv = new GateStatus(
                "clv",
                clvValue >= clvTargetRatio,
                clvValue,
                clvTargetRatio,
                String.format("ttl.staking.clv_7d=%.4f, target >= %.4f", clvValue, clvTargetRatio)
        );

        double policyReloadFailures = sumTaggedCounter("ttl.staking.policy.reloads", "status", "RELOAD_FAILED");
        double policyReloadSuccess = sumTaggedCounter("ttl.staking.policy.reloads", "status", "RELOADED");
        boolean reloadDrillPassing = policyReloadFailures == 0 && policyReloadSuccess >= MIN_POLICY_RELOADS;
        GateStatus policyDrill = new GateStatus(
                "policyReloadDrill",
                reloadDrillPassing,
                policyReloadSuccess,
                MIN_POLICY_RELOADS,
                policyReloadFailures == 0
                        ? "no RELOAD_FAILED; " + (int) policyReloadSuccess + " successful reload(s) of >=2 required"
                        : "policy reload had " + (int) policyReloadFailures + " failure(s) — see audit table"
        );

        double scoreBackedOnly = sumTaggedCounter("ttl.score_truth.primary.closures", "outcome", "SCORE_BACKED_ONLY");
        GateStatus streamCv = new GateStatus(
                "streamCvCoverage",
                scoreBackedOnly >= 1,
                scoreBackedOnly,
                1,
                scoreBackedOnly >= 1
                        ? "v3 held " + (int) scoreBackedOnly + " bet(s) as SCORE_BACKED_ONLY — Stream-CV gate exercised"
                        : "no SCORE_BACKED_ONLY holds recorded — Stream-CV gate not yet exercised"
        );

        boolean daysMet = daysElapsed >= SOAK_DAYS_REQUIRED;
        boolean allGatesPass = contradictions.passing()
                && exposure.passing()
                && clv.passing()
                && policyDrill.passing()
                && streamCv.passing();
        boolean overall = daysMet && allGatesPass;

        return new Soak11Status(
                now,
                soakStart,
                daysElapsed,
                SOAK_DAYS_REQUIRED,
                contradictions,
                exposure,
                clv,
                policyDrill,
                streamCv,
                overall,
                allGatesPass && !daysMet
                        ? "all five gates currently passing — " + (SOAK_DAYS_REQUIRED - daysElapsed) + " day(s) of wall-clock remain"
                        : null
        );
    }

    private Optional<Instant> parseSoakStart() {
        if (configuredSoakStart == null || configuredSoakStart.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(configuredSoakStart.trim()));
        } catch (RuntimeException ex) {
            log.warn("[soak11] ttl.soak11.startAt is set but unparseable: '{}' — treating as unset", configuredSoakStart);
            return Optional.empty();
        }
    }

    private double sumCounterValue(String name) {
        if (meterRegistry == null) {
            return 0.0;
        }
        Search search = meterRegistry.find(name);
        List<Counter> counters = search.counters().stream().toList();
        if (counters.isEmpty()) {
            return 0.0;
        }
        return counters.stream().mapToDouble(Counter::count).sum();
    }

    private double sumTaggedCounter(String name, String tagKey, String tagValue) {
        if (meterRegistry == null) {
            return 0.0;
        }
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double readGaugeValue(String name) {
        if (meterRegistry == null) {
            return 0.0;
        }
        Gauge gauge = meterRegistry.find(name).gauge();
        return gauge == null ? 0.0 : gauge.value();
    }

    private static double boolDouble(boolean passing) {
        return passing ? 1.0 : 0.0;
    }

    public record GateStatus(String name,
                             boolean passing,
                             double observed,
                             double threshold,
                             String detail) {
    }

    public record Soak11Status(Instant generatedAt,
                               Instant soakStartedAt,
                               long daysElapsed,
                               long daysRequired,
                               GateStatus contradictions,
                               GateStatus exposureCapBreaches,
                               GateStatus clv,
                               GateStatus policyReloadDrill,
                               GateStatus streamCvCoverage,
                               boolean allGreen,
                               String notes) {

        public static Soak11Status uninitialised() {
            GateStatus pending = new GateStatus("pending", false, 0, 0, "soak not started");
            return new Soak11Status(
                    Instant.EPOCH,
                    null,
                    0,
                    SOAK_DAYS_REQUIRED,
                    pending,
                    pending,
                    pending,
                    pending,
                    pending,
                    false,
                    "soak not started"
            );
        }

        public Soak11Status withNote(String note) {
            return new Soak11Status(
                    generatedAt, soakStartedAt, daysElapsed, daysRequired,
                    contradictions, exposureCapBreaches, clv, policyReloadDrill, streamCvCoverage,
                    allGreen, note);
        }

        /** Surfaces every gate's passing/observed/threshold for the API. */
        public Map<String, GateStatus> gateMap() {
            Map<String, GateStatus> map = new LinkedHashMap<>();
            map.put("contradictions", contradictions);
            map.put("exposureCapBreaches", exposureCapBreaches);
            map.put("clv", clv);
            map.put("policyReloadDrill", policyReloadDrill);
            map.put("streamCvCoverage", streamCvCoverage);
            return map;
        }
    }
}
