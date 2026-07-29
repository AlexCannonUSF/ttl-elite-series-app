package com.ttl.tabletennis.dto;

public record LiveStudioIntegrityDto(long trackedObservations,
                                     long boardObservations,
                                     long scoreFeedObservations,
                                     long trackedAfterCloseObservations,
                                     long scoreBackedSettlements,
                                     long targetedCompletionSettlements,
                                     long officialResultSettlements,
                                     long databaseSettlements,
                                     long heuristicSettlements,
                                     long voidedSettlements,
                                     SettlementRates rates) {

    /**
     * #120 — Backward-compat constructor for tests + callers that don't
     * supply per-path rates. Computes them on the fly.
     */
    public LiveStudioIntegrityDto(long trackedObservations,
                                   long boardObservations,
                                   long scoreFeedObservations,
                                   long trackedAfterCloseObservations,
                                   long scoreBackedSettlements,
                                   long targetedCompletionSettlements,
                                   long officialResultSettlements,
                                   long databaseSettlements,
                                   long heuristicSettlements,
                                   long voidedSettlements) {
        this(trackedObservations, boardObservations, scoreFeedObservations,
                trackedAfterCloseObservations, scoreBackedSettlements,
                targetedCompletionSettlements, officialResultSettlements,
                databaseSettlements, heuristicSettlements, voidedSettlements,
                SettlementRates.from(scoreBackedSettlements, targetedCompletionSettlements,
                        officialResultSettlements, databaseSettlements,
                        heuristicSettlements, voidedSettlements));
    }

    /**
     * #120 — Per-settlement-path rates as percentages of total closed bets
     * (sum of scoreBacked + targetedCompletion + official + database +
     * heuristic + voided). Lets operators see at a glance which path is
     * doing the work and when one breaks (e.g. score-feed=0% with high
     * observation rate = the identity-drift bug #113 that prompted this
     * whole audit).
     */
    public record SettlementRates(long totalClosed,
                                   double scoreBackedPct,
                                   double targetedCompletionPct,
                                   double officialResultPct,
                                   double databasePct,
                                   double heuristicPct,
                                   double voidedPct) {

        public static SettlementRates from(long scoreBacked,
                                            long targetedCompletion,
                                            long officialResult,
                                            long databaseResult,
                                            long heuristic,
                                            long voided) {
            long total = scoreBacked + targetedCompletion + officialResult
                    + databaseResult + heuristic + voided;
            if (total <= 0L) {
                return new SettlementRates(0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            }
            double scale = 100.0 / total;
            return new SettlementRates(
                    total,
                    round2(scoreBacked * scale),
                    round2(targetedCompletion * scale),
                    round2(officialResult * scale),
                    round2(databaseResult * scale),
                    round2(heuristic * scale),
                    round2(voided * scale));
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
