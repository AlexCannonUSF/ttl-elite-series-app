package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.Observation;
import com.ttl.tabletennis.settlement.observation.ScoreState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure score-evidence analysis shared by settlement, persistence, and UI
 * telemetry. It recognizes only states that prove a completed match; a
 * commanding but unfinished live score remains visible as progress and never
 * becomes an automatic winner.
 */
public final class ScoreEvidenceAnalyzer {

    private static final int WINNING_SETS = 3;
    private static final int GAME_POINTS = 11;
    private static final int GAME_MARGIN = 2;

    private ScoreEvidenceAnalyzer() {
    }

    public static ScoreEvidenceAssessment assess(SettlementEvidence evidence) {
        if (evidence == null || evidence.identityLock() == null) {
            return ScoreEvidenceAssessment.none();
        }
        List<Observation> scoreObservations = scoreObservations(evidence);
        if (scoreObservations.isEmpty()) {
            return ScoreEvidenceAssessment.none();
        }

        Observation latest = scoreObservations.stream()
                .max(Comparator.comparing(Observation::observedAt))
                .orElse(scoreObservations.get(scoreObservations.size() - 1));
        List<ScoreClaim> claims = scoreObservations.stream()
                .map(observation -> claim(observation, evidence.identityLock()).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        Set<SourceId> sources = new LinkedHashSet<>();
        scoreObservations.forEach(observation -> sources.add(observation.source()));
        int completions = (int) scoreObservations.stream().filter(Observation::completionSignal).count();
        double observedConfidence = weightedConfidence(scoreObservations);

        if (claims.isEmpty()) {
            ScoreEvidenceAssessment.Quality quality = observedConfidence >= 0.80
                    ? ScoreEvidenceAssessment.Quality.PARTIAL
                    : ScoreEvidenceAssessment.Quality.WEAK;
            return new ScoreEvidenceAssessment(
                    quality,
                    ScoreEvidenceAssessment.Finality.LIVE_PROGRESS,
                    observedConfidence,
                    scoreObservations.size(),
                    sources.size(),
                    0,
                    completions,
                    null,
                    canonicalScore(latest.score()),
                    latest.phase().name(),
                    false
            );
        }

        Map<Long, List<ScoreClaim>> byWinner = new HashMap<>();
        claims.forEach(claim -> byWinner.computeIfAbsent(claim.winnerPlayerId(), ignored -> new ArrayList<>()).add(claim));
        Map.Entry<Long, List<ScoreClaim>> best = byWinner.entrySet().stream()
                .max(Comparator.<Map.Entry<Long, List<ScoreClaim>>>comparingInt(entry -> distinctSources(entry.getValue()).size())
                        .thenComparingDouble(entry -> weightedClaimConfidence(entry.getValue())))
                .orElseThrow();
        boolean contradictory = byWinner.size() > 1;
        List<ScoreClaim> agreeing = best.getValue();
        int agreeingSources = distinctSources(agreeing).size();
        double claimConfidence = weightedClaimConfidence(agreeing);
        ScoreEvidenceAssessment.Finality finality = agreeing.stream()
                .map(ScoreClaim::finality)
                .max(Comparator.comparingInt(ScoreEvidenceAnalyzer::finalityRank))
                .orElse(ScoreEvidenceAssessment.Finality.LIVE_PROGRESS);
        boolean targetedCompletion = agreeing.stream().anyMatch(ScoreClaim::targetedCompletion);
        boolean decisionGrade = !contradictory
                && ((targetedCompletion && claimConfidence >= 0.95)
                || (agreeingSources >= 2 && claimConfidence >= 0.90));
        ScoreEvidenceAssessment.Quality quality = decisionGrade
                ? ScoreEvidenceAssessment.Quality.DECISION_GRADE
                : (!contradictory && claimConfidence >= 0.85
                ? ScoreEvidenceAssessment.Quality.STRONG
                : ScoreEvidenceAssessment.Quality.PARTIAL);

        return new ScoreEvidenceAssessment(
                quality,
                finality,
                contradictory ? Math.max(0.0, claimConfidence - 0.25) : claimConfidence,
                scoreObservations.size(),
                sources.size(),
                agreeingSources,
                completions,
                best.getKey(),
                canonicalScore(latest.score()),
                latest.phase().name(),
                contradictory
        );
    }

    public static Optional<ScoreClaim> claim(Observation observation, IdentityLock identityLock) {
        if (observation == null || identityLock == null || observation.score() == null) {
            return Optional.empty();
        }
        ScoreState score = observation.score();
        Integer gamesP1 = score.gamesP1();
        Integer gamesP2 = score.gamesP2();
        Integer pointsP1 = score.pointsP1();
        Integer pointsP2 = score.pointsP2();

        if (different(gamesP1, gamesP2) && Math.max(gamesP1, gamesP2) >= WINNING_SETS) {
            return Optional.of(claimForLeader(
                    gamesP1,
                    gamesP2,
                    observation,
                    identityLock,
                    ScoreEvidenceAssessment.Finality.MATHEMATICAL_FINAL_SCORE
            ));
        }

        boolean completion = observation.completionSignal() || observation.phase().isFinished();
        if (completion && different(gamesP1, gamesP2)) {
            return Optional.of(claimForLeader(
                    gamesP1,
                    gamesP2,
                    observation,
                    identityLock,
                    ScoreEvidenceAssessment.Finality.COMPLETION_SIGNAL
            ));
        }
        if (completion && gamesP1 == null && gamesP2 == null && terminalGame(pointsP1, pointsP2)) {
            return Optional.of(claimForLeader(
                    pointsP1,
                    pointsP2,
                    observation,
                    identityLock,
                    ScoreEvidenceAssessment.Finality.COMPLETION_SIGNAL
            ));
        }

        if (different(gamesP1, gamesP2)
                && Math.max(gamesP1, gamesP2) == WINNING_SETS - 1
                && terminalGame(pointsP1, pointsP2)
                && sameLeader(gamesP1, gamesP2, pointsP1, pointsP2)
                && EnumSet.of(MatchPhase.LIVE_MID, MatchPhase.LIVE_LATE, MatchPhase.FINISHED)
                .contains(observation.phase())) {
            return Optional.of(claimForLeader(
                    gamesP1,
                    gamesP2,
                    observation,
                    identityLock,
                    ScoreEvidenceAssessment.Finality.EFFECTIVE_FINAL_SCORE
            ));
        }
        if (gamesP1 != null
                && gamesP2 != null
                && gamesP1 == WINNING_SETS - 1
                && gamesP2 == WINNING_SETS - 1
                && terminalGame(pointsP1, pointsP2)
                && EnumSet.of(MatchPhase.LIVE_LATE, MatchPhase.FINISHED).contains(observation.phase())) {
            return Optional.of(claimForLeader(
                    pointsP1,
                    pointsP2,
                    observation,
                    identityLock,
                    ScoreEvidenceAssessment.Finality.EFFECTIVE_FINAL_SCORE
            ));
        }
        return Optional.empty();
    }

    private static ScoreClaim claimForLeader(int left,
                                             int right,
                                             Observation observation,
                                             IdentityLock identityLock,
                                             ScoreEvidenceAssessment.Finality finality) {
        long winner = left > right ? identityLock.player1Id() : identityLock.player2Id();
        boolean targeted = observation instanceof LiveObservation live
                && live.source() == SourceId.HR_TGT
                && (live.completionSignal() || live.phase().isFinished());
        return new ScoreClaim(
                winner,
                observation.source(),
                observation.confidence(),
                finality,
                targeted,
                observation.observedAt()
        );
    }

    private static List<Observation> scoreObservations(SettlementEvidence evidence) {
        List<Observation> observations = new ArrayList<>();
        observations.addAll(evidence.liveObservations());
        observations.addAll(evidence.mirrorObservations());
        observations.addAll(evidence.streamObservations());
        return observations.stream()
                .filter(observation -> observation.score() != null && observation.score().hasAnyScore())
                .sorted(Comparator.comparing(Observation::observedAt))
                .toList();
    }

    private static boolean different(Integer left, Integer right) {
        return left != null && right != null && !left.equals(right);
    }

    private static boolean terminalGame(Integer left, Integer right) {
        return different(left, right)
                && Math.max(left, right) >= GAME_POINTS
                && Math.abs(left - right) >= GAME_MARGIN;
    }

    private static boolean sameLeader(int gamesP1, int gamesP2, int pointsP1, int pointsP2) {
        return (gamesP1 > gamesP2) == (pointsP1 > pointsP2);
    }

    private static Set<SourceId> distinctSources(List<ScoreClaim> claims) {
        Set<SourceId> sources = new LinkedHashSet<>();
        claims.forEach(claim -> sources.add(claim.source()));
        return Set.copyOf(sources);
    }

    private static double weightedClaimConfidence(List<ScoreClaim> claims) {
        if (claims.isEmpty()) {
            return 0.0;
        }
        double numerator = 0.0;
        double denominator = 0.0;
        Instant latest = claims.stream().map(ScoreClaim::observedAt).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        for (ScoreClaim claim : claims) {
            double ageSeconds = Math.max(0.0, java.time.Duration.between(claim.observedAt(), latest).toSeconds());
            double weight = Math.exp(-ageSeconds / 600.0);
            numerator += claim.confidence() * weight;
            denominator += weight;
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    private static double weightedConfidence(List<Observation> observations) {
        if (observations.isEmpty()) {
            return 0.0;
        }
        return observations.stream().mapToDouble(Observation::confidence).average().orElse(0.0);
    }

    private static int finalityRank(ScoreEvidenceAssessment.Finality finality) {
        return switch (finality) {
            case NONE -> 0;
            case LIVE_PROGRESS -> 1;
            case EFFECTIVE_FINAL_SCORE -> 2;
            case MATHEMATICAL_FINAL_SCORE -> 3;
            case COMPLETION_SIGNAL -> 4;
        };
    }

    public static String canonicalScore(ScoreState score) {
        if (score == null || !score.hasAnyScore()) {
            return "";
        }
        String games = score.gamesP1() == null || score.gamesP2() == null
                ? ""
                : score.gamesP1() + "-" + score.gamesP2();
        String points = score.pointsP1() == null || score.pointsP2() == null
                ? ""
                : score.pointsP1() + "-" + score.pointsP2();
        if (!games.isEmpty() && !points.isEmpty()) {
            return games + " (" + points + ")";
        }
        return !games.isEmpty() ? games : points;
    }

    public record ScoreClaim(long winnerPlayerId,
                             SourceId source,
                             double confidence,
                             ScoreEvidenceAssessment.Finality finality,
                             boolean targetedCompletion,
                             Instant observedAt) {
    }
}
