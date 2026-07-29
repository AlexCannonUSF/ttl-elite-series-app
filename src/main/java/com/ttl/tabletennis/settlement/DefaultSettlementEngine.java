package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.DatabaseObservation;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.Observation;
import com.ttl.tabletennis.settlement.observation.OfficialObservation;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import com.ttl.tabletennis.settlement.observation.StreamObservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@Component
public class DefaultSettlementEngine implements SettlementEngine {

    private final AmbiguityScorer ambiguityScorer;
    private final ContradictionGuard contradictionGuard;
    /**
     * Wall-clock source for window-expiry checks. Injectable so tests can
     * pin time to their fixture timestamps; production uses the system clock.
     */
    private final Clock clock;

    @Autowired
    public DefaultSettlementEngine(AmbiguityScorer ambiguityScorer,
                                   ContradictionGuard contradictionGuard) {
        this(ambiguityScorer, contradictionGuard, Clock.systemDefaultZone());
    }

    public DefaultSettlementEngine(AmbiguityScorer ambiguityScorer,
                                   ContradictionGuard contradictionGuard,
                                   Clock clock) {
        this.ambiguityScorer = ambiguityScorer;
        this.contradictionGuard = contradictionGuard;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public Decision decide(SettlementEvidence evidence, SettlementPolicy policy) {
        if (evidence == null) {
            throw new IllegalArgumentException("evidence must not be null");
        }
        SettlementPolicy effectivePolicy = policy == null ? SettlementPolicy.defaults() : policy;

        AmbiguityAssessment ambiguity = ambiguityScorer.assess(evidence);
        List<Contradiction> contradictions = contradictionGuard.detect(evidence);
        SettlementEvidence enrichedEvidence = enrichedEvidence(evidence, ambiguity.score(), contradictions);

        double maxSeverity = contradictions.stream()
                .mapToDouble(Contradiction::severity)
                .max()
                .orElse(0.0);
        if (maxSeverity > effectivePolicy.settlement().contradictionBlockSeverity()) {
            return new ManualReview(enrichedEvidence, SettlementReason.MANUAL_REVIEW_AWAITING, contradictions);
        }

        if (ambiguity.score() > effectivePolicy.ambiguity().maxAllowedWithoutTiebreaker()) {
            return new ManualReview(enrichedEvidence, SettlementReason.MANUAL_REVIEW_AWAITING, contradictions);
        }

        Map<Outcome, ClaimAggregate> claims = buildClaims(enrichedEvidence);
        ClaimAggregate bestClaim = selectBestResolvedClaim(claims);
        if (bestClaim == null) {
            if (shouldEscalate(enrichedEvidence, effectivePolicy)) {
                return new Escalate(
                        enrichedEvidence,
                        SettlementReason.MANUAL_REVIEW_AWAITING,
                        remainingEscalationSources(enrichedEvidence, effectivePolicy)
                );
            }
            if (officialWindowExpired(enrichedEvidence, effectivePolicy)) {
                return new VoidDecision(enrichedEvidence, SettlementReason.VOIDED_NO_EVIDENCE);
            }
            return new HoldOpen(enrichedEvidence, SettlementReason.MANUAL_REVIEW_AWAITING, "insufficient completion evidence");
        }

        SettlementReason reason = reasonFromClaim(bestClaim);
        double requiredConfidence = Math.max(
                effectivePolicy.settlement().minConfidenceToAutoSettle(),
                reason.requiredConfidence() == null ? 0.0 : reason.requiredConfidence()
        );
        boolean enoughSources = bestClaim.distinctSources().size() >= requiredSourcesFor(reason, effectivePolicy);
        if (bestClaim.weightedConfidence() >= requiredConfidence && enoughSources) {
            return new Settle(enrichedEvidence, winnerPlayerId(bestClaim.outcome(), enrichedEvidence.identityLock()), reason, clamp(bestClaim.weightedConfidence()));
        }

        if (canUseHeuristic(bestClaim, enrichedEvidence, effectivePolicy)) {
            return new Settle(
                    enrichedEvidence,
                    winnerPlayerId(bestClaim.outcome(), enrichedEvidence.identityLock()),
                    SettlementReason.LAST_SCORE_HEURISTIC,
                    clamp(bestClaim.weightedConfidence())
            );
        }

        if (officialWindowExpired(enrichedEvidence, effectivePolicy)) {
            return new VoidDecision(enrichedEvidence, SettlementReason.VOIDED_NO_EVIDENCE);
        }

        return new HoldOpen(enrichedEvidence, SettlementReason.MANUAL_REVIEW_AWAITING, "insufficient independent evidence");
    }

    private SettlementEvidence enrichedEvidence(SettlementEvidence evidence,
                                                double ambiguityScore,
                                                List<Contradiction> contradictions) {
        return new SettlementEvidence(
                evidence.betId(),
                evidence.trackedEventId(),
                evidence.identityLock(),
                evidence.liveObservations(),
                evidence.mirrorObservations(),
                evidence.streamObservations(),
                evidence.officialCandidates(),
                evidence.databaseCandidates(),
                evidence.coverageState(),
                contradictions,
                ambiguityScore,
                evidence.confidence(),
                evidence.bundleAsOf()
        );
    }

    private Map<Outcome, ClaimAggregate> buildClaims(SettlementEvidence evidence) {
        Map<Outcome, MutableClaimAggregate> claims = new EnumMap<>(Outcome.class);
        for (Observation observation : evidence.allObservations()) {
            Outcome outcome = inferOutcome(observation, evidence.identityLock());
            MutableClaimAggregate aggregate = claims.computeIfAbsent(outcome, ignored -> new MutableClaimAggregate(outcome));
            aggregate.observations.add(observation);
            aggregate.distinctSources.add(observation.source());
            double weight = observationWeight(observation, evidence.bundleAsOf());
            aggregate.weightedConfidenceSum += weight * observation.confidence();
            aggregate.totalWeight += weight;
        }
        Map<Outcome, ClaimAggregate> out = new EnumMap<>(Outcome.class);
        claims.forEach((outcome, aggregate) -> out.put(outcome, aggregate.freeze()));
        return out;
    }

    private ClaimAggregate selectBestResolvedClaim(Map<Outcome, ClaimAggregate> claims) {
        return claims.values().stream()
                .filter(Predicate.not(claim -> claim.outcome() == Outcome.NOT_FINISHED))
                .max(Comparator.comparingDouble(ClaimAggregate::weightedConfidence)
                        .thenComparingInt(claim -> claim.distinctSources().size()))
                .orElse(null);
    }

    private Outcome inferOutcome(Observation observation, IdentityLock identityLock) {
        Long explicitWinner = explicitWinner(observation);
        if (explicitWinner != null) {
            if (explicitWinner == 0L) {
                return Outcome.PUSH;
            }
            if (explicitWinner.equals(identityLock.player1Id())) {
                return Outcome.PLAYER1_WINS;
            }
            if (explicitWinner.equals(identityLock.player2Id())) {
                return Outcome.PLAYER2_WINS;
            }
        }

        ScoreState score = observation.score();
        Integer gamesP1 = score.gamesP1();
        Integer gamesP2 = score.gamesP2();
        if (observation.completionSignal() || observation.phase().isFinished() || reachedWinningSets(gamesP1, gamesP2)) {
            if (gamesP1 != null && gamesP2 != null && !gamesP1.equals(gamesP2)) {
                return gamesP1 > gamesP2 ? Outcome.PLAYER1_WINS : Outcome.PLAYER2_WINS;
            }
            if (score.pointsP1() != null && score.pointsP2() != null && !score.pointsP1().equals(score.pointsP2())) {
                return score.pointsP1() > score.pointsP2() ? Outcome.PLAYER1_WINS : Outcome.PLAYER2_WINS;
            }
        }

        if (isDecisiveInProgressScore(score)) {
            if (gamesP1 != null && gamesP2 != null && !gamesP1.equals(gamesP2)) {
                return gamesP1 > gamesP2 ? Outcome.PLAYER1_WINS : Outcome.PLAYER2_WINS;
            }
            if (score.pointsP1() != null && score.pointsP2() != null && !score.pointsP1().equals(score.pointsP2())) {
                return score.pointsP1() > score.pointsP2() ? Outcome.PLAYER1_WINS : Outcome.PLAYER2_WINS;
            }
        }

        return Outcome.NOT_FINISHED;
    }

    private Long explicitWinner(Observation observation) {
        if (observation instanceof OfficialObservation officialObservation) {
            return officialObservation.winnerPlayerId();
        }
        if (observation instanceof DatabaseObservation databaseObservation) {
            return databaseObservation.winnerPlayerId();
        }
        return null;
    }

    private double observationWeight(Observation observation, Instant bundleAsOf) {
        double tierWeight = switch (observation.tier()) {
            case T1_SPORTSBOOK -> 0.35;
            case T2_MIRROR -> 0.30;
            case T3_STREAM_CV -> 0.25;
            case T4_CONFIRMATION -> 0.40;
        };
        double ageSeconds = Math.max(0.0, Duration.between(observation.observedAt(), bundleAsOf).toMillis() / 1000.0);
        double recencyDecay = Math.exp(-ageSeconds / 600.0);
        double completionBonus = observation.completionSignal() ? 1.25 : 1.0;
        return tierWeight * recencyDecay * completionBonus;
    }

    private SettlementReason reasonFromClaim(ClaimAggregate claim) {
        if (claim.observations().stream().anyMatch(OfficialObservation.class::isInstance)) {
            return SettlementReason.OFFICIAL_RESULT_CONFIRMED;
        }
        if (claim.observations().stream().anyMatch(DatabaseObservation.class::isInstance)) {
            return SettlementReason.DATABASE_RESULT_CONFIRMED;
        }
        if (claim.observations().stream().anyMatch(observation ->
                observation instanceof StreamObservation streamObservation && streamObservation.consensusFrames() >= 3)) {
            return SettlementReason.STREAM_CV_CONSENSUS;
        }
        if (claim.observations().stream().anyMatch(observation ->
                observation instanceof LiveObservation liveObservation
                        && liveObservation.source() == SourceId.HR_TGT
                        && liveObservation.completionSignal())) {
            return SettlementReason.TARGETED_COMPLETION_SIGNAL;
        }
        if (claim.observations().stream().anyMatch(observation ->
                observation.completionSignal() || observation.phase().isFinished() || reachedWinningSets(observation.score().gamesP1(), observation.score().gamesP2()))) {
            return SettlementReason.SCORE_BACKED_FINISHED;
        }
        return SettlementReason.SCORE_BACKED_DECISIVE;
    }

    private boolean shouldEscalate(SettlementEvidence evidence, SettlementPolicy policy) {
        if (evidence.coverageState() != CoverageState.DARK) {
            return false;
        }
        return minutesSincePlacement(evidence) >= policy.staleLiveRecovery().enterAfterMinutesDark();
    }

    private List<SourceId> remainingEscalationSources(SettlementEvidence evidence, SettlementPolicy policy) {
        Set<SourceId> present = evidence.distinctSources();
        return policy.staleLiveRecovery().escalationOrder().stream()
                .filter(sourceId -> !present.contains(sourceId))
                .toList();
    }

    private boolean officialWindowExpired(SettlementEvidence evidence, SettlementPolicy policy) {
        return minutesSincePlacement(evidence) >= policy.staleLiveRecovery().officialWindowMinutes();
    }

    private boolean canUseHeuristic(ClaimAggregate bestClaim,
                                    SettlementEvidence evidence,
                                    SettlementPolicy policy) {
        if (!policy.heuristic().allowed()) {
            return false;
        }
        if (bestClaim.outcome() == Outcome.NOT_FINISHED) {
            return false;
        }
        // #117 — phase-aware void timeout. Pulls the latest LiveObservation's
        // matchPhase (LIVE_LATE, LIVE_MID, LIVE_EARLY, PREMATCH) and lets the
        // policy pick a stage-appropriate threshold. Default policy voids
        // LIVE_LATE bets at 90 min (a game-5 deuce rarely runs that long) and
        // PREMATCH bets at 240 min (give a match still in pregame time to
        // resume after a feed hiccup). Falls back to the legacy single
        // afterDarkMinutes when the bet has no observable phase yet.
        MatchPhase latestPhase = latestObservedPhase(evidence);
        int effectiveTimeoutMin = policy.heuristic().afterDarkMinutesFor(latestPhase);
        return minutesSincePlacement(evidence) >= effectiveTimeoutMin
                && bestClaim.weightedConfidence() >= (SettlementReason.LAST_SCORE_HEURISTIC.requiredConfidence() == null
                ? 0.0
                : SettlementReason.LAST_SCORE_HEURISTIC.requiredConfidence());
    }

    /**
     * Latest {@link MatchPhase} observed across the evidence bundle's
     * {@code liveObservations}. Returns {@code null} when no live
     * observations are present (the policy will then use the default
     * fallback timeout).
     */
    private static MatchPhase latestObservedPhase(SettlementEvidence evidence) {
        if (evidence.liveObservations() == null || evidence.liveObservations().isEmpty()) {
            return null;
        }
        // liveObservations is sorted ascending by observedAt in the
        // SettlementEvidence canonical constructor, so the last entry is the
        // most recent.
        return evidence.liveObservations().get(evidence.liveObservations().size() - 1).phase();
    }

    /**
     * Returns minutes since the bet was placed, measured against the LATER of
     * {@code bundleAsOf} (when the evidence bundle was assembled) and the
     * current wall clock. The previous implementation used only {@code
     * bundleAsOf} (i.e. the latest observation time), which meant a bet whose
     * scoreboard feed went silent overnight could appear "young" forever and
     * never trigger {@code officialWindowExpired} → infinite HoldOpen. Using
     * wall-clock time fixes that without breaking the (rare) case where
     * bundleAsOf is in the future relative to {@code now}.
     */
    private long minutesSincePlacement(SettlementEvidence evidence) {
        Instant placement = evidence.identityLock().placementTime();
        Instant referenceTime = evidence.bundleAsOf();
        Instant now = clock.instant();
        if (referenceTime == null || referenceTime.isBefore(now)) {
            referenceTime = now;
        }
        return Duration.between(placement, referenceTime).toMinutes();
    }

    private int requiredSourcesFor(SettlementReason reason, SettlementPolicy policy) {
        return switch (reason) {
            case OFFICIAL_RESULT_CONFIRMED, DATABASE_RESULT_CONFIRMED, TARGETED_COMPLETION_SIGNAL -> 1;
            default -> policy.settlement().requireSources();
        };
    }

    private long winnerPlayerId(Outcome outcome, IdentityLock identityLock) {
        return switch (outcome) {
            case PLAYER1_WINS -> identityLock.player1Id();
            case PLAYER2_WINS -> identityLock.player2Id();
            case PUSH, NOT_FINISHED -> 0L;
        };
    }

    private boolean reachedWinningSets(Integer gamesP1, Integer gamesP2) {
        return (gamesP1 != null && gamesP1 >= 3) || (gamesP2 != null && gamesP2 >= 3);
    }

    private boolean isDecisiveInProgressScore(ScoreState score) {
        Integer gamesP1 = score.gamesP1();
        Integer gamesP2 = score.gamesP2();
        Integer pointsP1 = score.pointsP1();
        Integer pointsP2 = score.pointsP2();
        if (gamesP1 == null || gamesP2 == null || pointsP1 == null || pointsP2 == null || gamesP1.equals(gamesP2)) {
            return false;
        }
        int leaderSets = Math.max(gamesP1, gamesP2);
        int leaderPoints = gamesP1 > gamesP2 ? pointsP1 : pointsP2;
        int trailerPoints = gamesP1 > gamesP2 ? pointsP2 : pointsP1;
        return leaderSets >= 2 && leaderPoints >= 8 && (leaderPoints - trailerPoints) >= 3;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private enum Outcome {
        PLAYER1_WINS,
        PLAYER2_WINS,
        PUSH,
        NOT_FINISHED
    }

    private record ClaimAggregate(Outcome outcome,
                                  List<Observation> observations,
                                  Set<SourceId> distinctSources,
                                  double weightedConfidence) {
    }

    private static final class MutableClaimAggregate {
        private final Outcome outcome;
        private final List<Observation> observations = new ArrayList<>();
        private final Set<SourceId> distinctSources = new LinkedHashSet<>();
        private double weightedConfidenceSum;
        private double totalWeight;

        private MutableClaimAggregate(Outcome outcome) {
            this.outcome = outcome;
        }

        private ClaimAggregate freeze() {
            double normalizedConfidence = totalWeight <= 0.0 ? 0.0 : weightedConfidenceSum / totalWeight;
            return new ClaimAggregate(
                    outcome,
                    List.copyOf(observations),
                    Set.copyOf(distinctSources),
                    normalizedConfidence
            );
        }
    }
}
