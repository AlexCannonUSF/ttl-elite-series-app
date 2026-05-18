package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;

import java.util.List;
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
                new Heuristic(true, 240)
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
                            int afterDarkMinutes) {
        public Heuristic {
            if (afterDarkMinutes < 0) {
                throw new IllegalArgumentException("afterDarkMinutes must not be negative");
            }
        }
    }
}
