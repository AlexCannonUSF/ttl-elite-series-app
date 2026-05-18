package com.ttl.tabletennis.settlement;

public record AmbiguityAssessment(double score,
                                  AmbiguityBand band,
                                  int matchingCandidateCount,
                                  int exactBookerMatchCount) {

    public AmbiguityAssessment {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0.0 and 1.0");
        }
        if (matchingCandidateCount < 0) {
            throw new IllegalArgumentException("matchingCandidateCount must not be negative");
        }
        if (exactBookerMatchCount < 0) {
            throw new IllegalArgumentException("exactBookerMatchCount must not be negative");
        }
    }

    public boolean requiresTiebreaker() {
        return band == AmbiguityBand.MANUAL_REVIEW;
    }
}
