package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.TrustTier;

import java.util.EnumSet;
import java.util.Set;

public enum SettlementReason {
    SCORE_BACKED_DECISIVE(EnumSet.of(TrustTier.T1_SPORTSBOOK, TrustTier.T2_MIRROR, TrustTier.T3_STREAM_CV), 0.85, true),
    SCORE_BACKED_FINISHED(EnumSet.of(TrustTier.T1_SPORTSBOOK, TrustTier.T2_MIRROR, TrustTier.T3_STREAM_CV), 0.90, true),
    TARGETED_COMPLETION_SIGNAL(EnumSet.of(TrustTier.T1_SPORTSBOOK), 0.95, true),
    OFFICIAL_RESULT_CONFIRMED(EnumSet.of(TrustTier.T4_CONFIRMATION), 0.90, true),
    DATABASE_RESULT_CONFIRMED(EnumSet.of(TrustTier.T4_CONFIRMATION), 0.85, true),
    STREAM_CV_CONSENSUS(EnumSet.of(TrustTier.T3_STREAM_CV), 0.80, true),
    LAST_SCORE_HEURISTIC(EnumSet.of(TrustTier.T1_SPORTSBOOK, TrustTier.T2_MIRROR), 0.50, false),
    STALE_ESCALATION_RECOVERED(EnumSet.allOf(TrustTier.class), null, true),
    VOIDED_NO_EVIDENCE(EnumSet.noneOf(TrustTier.class), null, false),
    VOIDED_AMBIGUOUS_UNRESOLVED(EnumSet.noneOf(TrustTier.class), null, false),
    MANUAL_REVIEW_AWAITING(EnumSet.noneOf(TrustTier.class), null, false);

    private final Set<TrustTier> supportedTiers;
    private final Double requiredConfidence;
    private final boolean learnable;

    SettlementReason(Set<TrustTier> supportedTiers, Double requiredConfidence, boolean learnable) {
        this.supportedTiers = supportedTiers.isEmpty() ? Set.of() : Set.copyOf(supportedTiers);
        this.requiredConfidence = requiredConfidence;
        this.learnable = learnable;
    }

    public Set<TrustTier> supportedTiers() {
        return supportedTiers;
    }

    public Double requiredConfidence() {
        return requiredConfidence;
    }

    public boolean learnable() {
        return learnable;
    }
}
