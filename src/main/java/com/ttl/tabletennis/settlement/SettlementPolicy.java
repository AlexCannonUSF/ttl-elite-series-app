package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.MatchPhase;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SettlementPolicy(Ambiguity ambiguity,
                               Settlement settlement,
                               StaleLiveRecovery staleLiveRecovery,
                               Heuristic heuristic) {

    public SettlementPolicy {
        ambiguity = Objects.requireNonNull(ambiguity, "ambiguity must not be null");
        settlement = Objects.requireNonNull(settlement, "settlement must not be null");
        staleLiveRecovery = Objects.requireNonNull(staleLiveRecovery, "staleLiveRecovery must not be null");
        heuristic = Objects.requireNonNull(heuristic, "heuristic must not be null");
    }

    public static SettlementPolicy defaults() {
        // #117 — phase-aware void timeouts. LIVE_LATE matches that have lost
        // their feed are almost certainly finished (a game-5 deuce rarely lasts
        // 90 minutes), so we void them aggressively to reclaim stake. Earlier
        // phases use the historical 240-min timeout to give pre-match and
        // early-set matches room to resume after a feed hiccup.
        Map<MatchPhase, Integer> phaseDefaults = Map.of(
                MatchPhase.LIVE_LATE, 90,
                MatchPhase.LIVE_MID, 150,
                MatchPhase.LIVE_EARLY, 200,
                MatchPhase.PREMATCH, 240
        );
        return new SettlementPolicy(
                new Ambiguity(0.7),
                new Settlement(0.85, 0.5, 2),
                new StaleLiveRecovery(10, 180, List.of(
                        SourceId.HR_TGT,
                        SourceId.SOFASCORE,
                        SourceId.AISCORE,
                        SourceId.BETSAPI,
                        SourceId.STREAM_CV
                )),
                new Heuristic(true, 240, phaseDefaults)
        );
    }

    public record Ambiguity(double maxAllowedWithoutTiebreaker) {
        public Ambiguity {
            if (maxAllowedWithoutTiebreaker < 0.0 || maxAllowedWithoutTiebreaker > 1.0) {
                throw new IllegalArgumentException("maxAllowedWithoutTiebreaker must be between 0.0 and 1.0");
            }
        }
    }

    public record Settlement(double minConfidenceToAutoSettle,
                             double contradictionBlockSeverity,
                             int requireSources) {
        public Settlement {
            if (minConfidenceToAutoSettle < 0.0 || minConfidenceToAutoSettle > 1.0) {
                throw new IllegalArgumentException("minConfidenceToAutoSettle must be between 0.0 and 1.0");
            }
            if (contradictionBlockSeverity < 0.0 || contradictionBlockSeverity > 1.0) {
                throw new IllegalArgumentException("contradictionBlockSeverity must be between 0.0 and 1.0");
            }
            if (requireSources < 1) {
                throw new IllegalArgumentException("requireSources must be at least 1");
            }
        }
    }

    public record StaleLiveRecovery(int enterAfterMinutesDark,
                                    int officialWindowMinutes,
                                    List<SourceId> escalationOrder) {
        public StaleLiveRecovery {
            if (enterAfterMinutesDark < 0) {
                throw new IllegalArgumentException("enterAfterMinutesDark must not be negative");
            }
            if (officialWindowMinutes < 0) {
                throw new IllegalArgumentException("officialWindowMinutes must not be negative");
            }
            escalationOrder = escalationOrder == null || escalationOrder.isEmpty() ? List.of() : List.copyOf(escalationOrder);
        }
    }

    public record Heuristic(boolean allowed,
                            int afterDarkMinutes,
                            Map<MatchPhase, Integer> phaseAfterDarkMinutes) {
        public Heuristic {
            if (afterDarkMinutes < 0) {
                throw new IllegalArgumentException("afterDarkMinutes must not be negative");
            }
            phaseAfterDarkMinutes = phaseAfterDarkMinutes == null
                    ? Map.of()
                    : Map.copyOf(phaseAfterDarkMinutes);
            for (Map.Entry<MatchPhase, Integer> entry : phaseAfterDarkMinutes.entrySet()) {
                if (entry.getValue() == null || entry.getValue() < 0) {
                    throw new IllegalArgumentException(
                            "phaseAfterDarkMinutes value for " + entry.getKey() + " must be non-null and non-negative");
                }
            }
        }

        /**
         * Back-compat constructor for callers that don't supply
         * phase-specific overrides. Falls through to a fixed
         * {@code afterDarkMinutes} for every phase.
         */
        public Heuristic(boolean allowed, int afterDarkMinutes) {
            this(allowed, afterDarkMinutes, Map.of());
        }

        /**
         * Returns the void timeout for the given match phase, falling back
         * to {@link #afterDarkMinutes()} when no override is configured for
         * the phase (or when {@code phase} is null).
         */
        public int afterDarkMinutesFor(MatchPhase phase) {
            if (phase == null) {
                return afterDarkMinutes;
            }
            Integer override = phaseAfterDarkMinutes.get(phase);
            return override == null ? afterDarkMinutes : override;
        }
    }
}
