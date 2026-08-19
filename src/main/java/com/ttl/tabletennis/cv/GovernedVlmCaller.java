package com.ttl.tabletennis.cv;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

public class GovernedVlmCaller {

    private final VlmClient delegate;
    private final CostGovernor governor;
    private final VlmCallRecorder recorder;
    private final Clock clock;

    public GovernedVlmCaller(VlmClient delegate,
                             CostGovernor governor,
                             VlmCallRecorder recorder,
                             Clock clock) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (governor == null) {
            throw new IllegalArgumentException("governor must not be null");
        }
        if (recorder == null) {
            throw new IllegalArgumentException("recorder must not be null");
        }
        this.delegate = delegate;
        this.governor = governor;
        this.recorder = recorder;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Outcome call(String workerId, VlmRequest request) {
        Instant reservedAt = clock.instant();
        if (!delegate.isEnabled()) {
            return Outcome.skipped("vlm-disabled");
        }

        CostGovernor.Verdict verdict = governor.reserve(workerId, reservedAt);
        if (!verdict.allowed()) {
            return Outcome.blocked(verdict.reason());
        }

        VlmScoreReadingResult result = delegate.readScoreboard(request);
        Instant completedAt = clock.instant();
        governor.recordCall(workerId, delegate.engineId(), result, completedAt);
        recorder.record(workerId, request == null ? "" : request.matchId(),
                delegate.engineId(), request, result, completedAt);
        return Outcome.completed(result, verdict.overSoftCap());
    }

    public String engineId() {
        return delegate.engineId();
    }

    public record Outcome(Status status,
                          Optional<VlmScoreReadingResult> result,
                          String reason,
                          boolean overSoftCap) {

        public enum Status { COMPLETED, BLOCKED, SKIPPED }

        public static Outcome completed(VlmScoreReadingResult result, boolean overSoftCap) {
            return new Outcome(Status.COMPLETED, Optional.of(result), "", overSoftCap);
        }

        public static Outcome blocked(String reason) {
            return new Outcome(Status.BLOCKED, Optional.empty(), reason, false);
        }

        public static Outcome skipped(String reason) {
            return new Outcome(Status.SKIPPED, Optional.empty(), reason, false);
        }
    }
}
