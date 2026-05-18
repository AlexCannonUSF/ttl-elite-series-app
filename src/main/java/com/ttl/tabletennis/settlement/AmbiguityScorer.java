package com.ttl.tabletennis.settlement;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Component
public class AmbiguityScorer {

    private static final double MATCHING_CANDIDATE_WEIGHT = 1.0;
    private static final double EXACT_BOOKER_EVENT_BONUS = 0.4;
    private static final double NORMALIZATION_CAP = 3.0;

    public AmbiguityAssessment assess(SettlementEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");

        IdentityLock identityLock = evidence.identityLock();
        int matchingCandidates = 0;
        int exactBookerMatches = 0;
        double rawScore = 0.0;

        for (OfficialCandidate candidate : evidence.officialCandidates()) {
            if (!matchesIdentityWindow(identityLock, candidate.player1Id(), candidate.player2Id(), candidate.matchDate())) {
                continue;
            }
            matchingCandidates++;
            rawScore += MATCHING_CANDIDATE_WEIGHT;
            if (bookerEventMatches(identityLock.bookerEventId(), candidate.bookerEventId())) {
                exactBookerMatches++;
                rawScore -= EXACT_BOOKER_EVENT_BONUS;
            }
        }

        for (DatabaseCandidate candidate : evidence.databaseCandidates()) {
            if (!matchesIdentityWindow(identityLock, candidate.player1Id(), candidate.player2Id(), candidate.matchDate())) {
                continue;
            }
            matchingCandidates++;
            rawScore += MATCHING_CANDIDATE_WEIGHT;
            if (bookerEventMatches(identityLock.bookerEventId(), candidate.bookerEventId())) {
                exactBookerMatches++;
                rawScore -= EXACT_BOOKER_EVENT_BONUS;
            }
        }

        double normalized = normalize(rawScore);
        return new AmbiguityAssessment(normalized, bandFor(normalized), matchingCandidates, exactBookerMatches);
    }

    public double score(SettlementEvidence evidence) {
        return assess(evidence).score();
    }

    public AmbiguityBand bandFor(double score) {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0.0 and 1.0");
        }
        if (score < 0.3) {
            return AmbiguityBand.UNAMBIGUOUS;
        }
        if (score < 0.7) {
            return AmbiguityBand.REQUIRES_STRONG_EVIDENCE;
        }
        return AmbiguityBand.MANUAL_REVIEW;
    }

    private boolean matchesIdentityWindow(IdentityLock identityLock,
                                          long candidatePlayer1Id,
                                          long candidatePlayer2Id,
                                          LocalDate candidateDate) {
        if (!samePlayerPair(identityLock.player1Id(), identityLock.player2Id(), candidatePlayer1Id, candidatePlayer2Id)) {
            return false;
        }

        LocalDate placementDate = LocalDate.ofInstant(identityLock.placementTime(), ZoneOffset.UTC);
        long allowedDayDistance = allowedDayDistance(identityLock);
        long dayDistance = Math.abs(ChronoUnit.DAYS.between(placementDate, candidateDate));
        return dayDistance <= allowedDayDistance;
    }

    private long allowedDayDistance(IdentityLock identityLock) {
        double hours = Math.max(0.0, identityLock.ambiguityWindow().toMinutes() / 60.0);
        return Math.max(0L, (long) Math.ceil(hours / 24.0) - 1L);
    }

    private boolean samePlayerPair(long lockPlayer1Id,
                                   long lockPlayer2Id,
                                   long candidatePlayer1Id,
                                   long candidatePlayer2Id) {
        return (lockPlayer1Id == candidatePlayer1Id && lockPlayer2Id == candidatePlayer2Id)
                || (lockPlayer1Id == candidatePlayer2Id && lockPlayer2Id == candidatePlayer1Id);
    }

    private boolean bookerEventMatches(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return !left.isBlank() && left.trim().equalsIgnoreCase(right.trim());
    }

    private double normalize(double rawScore) {
        double bounded = Math.max(0.0, rawScore);
        return Math.min(1.0, bounded / NORMALIZATION_CAP);
    }
}
