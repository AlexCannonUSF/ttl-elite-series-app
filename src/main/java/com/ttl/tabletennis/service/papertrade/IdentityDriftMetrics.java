package com.ttl.tabletennis.service.papertrade;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * #116 — Identity-drift Micrometer counters.
 *
 * <p>Before this component, identity-drift attempts (when a score-feed
 * observation can't be matched to its locked bet) only emitted log lines.
 * The session 65 production incident sat undetected for 5+ hours because
 * 14+12 drift attempts left no operational signal.
 *
 * <p>This component publishes:
 * <ul>
 *   <li>{@code ttl.identity.drift.attempts}{reason} — every time a candidate
 *       row/observation is rejected as drift, tagged by the reject reason
 *       ({@code CONFLICTING_OBSERVATION_RECORD}, etc.).</li>
 *   <li>{@code ttl.identity.fallback.rescued} — every time #114's name+time
 *       fallback saved an observation that the legacy ID-only check would
 *       have rejected. Lets operators detect a new cross-feed ID family
 *       drift the moment it starts happening, before settlement degrades.</li>
 * </ul>
 *
 * <p>{@link BetIdentityLockManager} is a static utility class (intentional;
 * it's a pure read/mutate helper called from many sites). We bridge the two
 * worlds via a static {@link Observer} setter on the manager — this
 * component installs itself at boot.
 */
@Component
public class IdentityDriftMetrics implements BetIdentityLockManager.Observer {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> driftCountersByReason = new ConcurrentHashMap<>();
    private final Counter fallbackRescuedCounter;

    public IdentityDriftMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.fallbackRescuedCounter = Counter.builder("ttl.identity.fallback.rescued")
                .description("Observations rescued by #114's name+time fallback after ID-based match failed")
                .register(meterRegistry);
    }

    @PostConstruct
    void install() {
        BetIdentityLockManager.setObserver(this);
    }

    @Override
    public void onDriftAttempt(String reason) {
        String safeReason = reason == null || reason.isBlank() ? "UNKNOWN" : reason;
        driftCountersByReason.computeIfAbsent(safeReason, this::buildDriftCounter).increment();
    }

    @Override
    public void onFallbackRescued() {
        fallbackRescuedCounter.increment();
    }

    private Counter buildDriftCounter(String reason) {
        return Counter.builder("ttl.identity.drift.attempts")
                .description("Identity-drift rejection attempts during bet observation matching")
                .tag("reason", reason)
                .register(meterRegistry);
    }
}
