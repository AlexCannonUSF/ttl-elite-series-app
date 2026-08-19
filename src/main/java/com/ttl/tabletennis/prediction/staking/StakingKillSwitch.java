package com.ttl.tabletennis.prediction.staking;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Operator kill-switch for the v3 staking layer (Phase 06 item 2).
 *
 * <p>When active, {@link StakingPolicy} short-circuits every request to
 * a {@code NO_BET} decision with {@code KILL_SWITCH_ACTIVE}. The flag
 * lives in-process; persistence is via the
 * {@link StakingPolicyCatalog} audit table so operators can replay
 * toggles after restart.
 */
@Component
public class StakingKillSwitch {

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicReference<Status> status;
    private final StakingPolicyCatalog catalog;
    private final Clock clock;

    @Autowired
    public StakingKillSwitch(StakingPolicyCatalog catalog, MeterRegistry meterRegistry) {
        this(catalog, meterRegistry, Clock.systemUTC());
    }

    StakingKillSwitch(StakingPolicyCatalog catalog, MeterRegistry meterRegistry, Clock clock) {
        this.catalog = catalog;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.status = new AtomicReference<>(new Status(false, "", "", this.clock.instant()));
        if (meterRegistry != null) {
            Gauge.builder("ttl.staking.kill_switch_active", active, b -> b.get() ? 1.0 : 0.0)
                    .description("1 when the v3 staking kill-switch is engaged, 0 otherwise")
                    .register(meterRegistry);
        }
    }

    public boolean isActive() {
        return active.get();
    }

    public Status status() {
        return status.get();
    }

    public synchronized Status activate(String triggeredBy, String reason) {
        Status next = new Status(true, safe(triggeredBy), safe(reason), clock.instant());
        boolean changed = !active.getAndSet(true);
        status.set(next);
        if (changed && catalog != null) {
            catalog.recordKillSwitchEvent(true, next.triggeredBy(), next.reason());
        }
        return next;
    }

    public synchronized Status deactivate(String triggeredBy, String reason) {
        Status next = new Status(false, safe(triggeredBy), safe(reason), clock.instant());
        boolean changed = active.getAndSet(false);
        status.set(next);
        if (changed && catalog != null) {
            catalog.recordKillSwitchEvent(false, next.triggeredBy(), next.reason());
        }
        return next;
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    public Optional<Status> ifActive() {
        return active.get() ? Optional.of(status.get()) : Optional.empty();
    }

    public record Status(boolean active, String triggeredBy, String reason, Instant at) { }
}
