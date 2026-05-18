package com.ttl.tabletennis.scrape;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum SourceId {
    HR_MKT(TrustTier.T1_SPORTSBOOK),
    HR_TGT(TrustTier.T1_SPORTSBOOK),
    HR_TREE(TrustTier.T1_SPORTSBOOK),
    SOFASCORE(TrustTier.T2_MIRROR),
    AISCORE(TrustTier.T2_MIRROR),
    BETSAPI(TrustTier.T2_MIRROR),
    STREAM_CV(TrustTier.T3_STREAM_CV),
    TTS_POST(TrustTier.T4_CONFIRMATION),
    TTS_PLAYER(TrustTier.T4_CONFIRMATION),
    TTS_H2H(TrustTier.T4_CONFIRMATION),
    ITTF_WTT(TrustTier.T4_CONFIRMATION),
    INTERNAL_DB(TrustTier.T4_CONFIRMATION);

    private final TrustTier tier;

    SourceId(TrustTier tier) {
        this.tier = tier;
    }

    public String id() {
        return name();
    }

    public TrustTier tier() {
        return tier;
    }

    public boolean matches(String rawValue) {
        return fromValue(rawValue).filter(candidate -> candidate == this).isPresent();
    }

    public static Optional<SourceId> fromValue(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalized = rawValue.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(sourceId -> sourceId.id().equals(normalized))
                .findFirst();
    }
}
