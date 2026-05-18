package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.DatabaseObservation;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.MirrorObservation;
import com.ttl.tabletennis.settlement.observation.Observation;
import com.ttl.tabletennis.settlement.observation.OfficialObservation;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import com.ttl.tabletennis.settlement.observation.StreamObservation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

@Component
public class ContradictionGuard {

    public static final double DEFAULT_BLOCK_THRESHOLD = 0.5;

    public List<Contradiction> detect(SettlementEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");

        List<Contradiction> contradictions = new ArrayList<>();
        List<Observation> observations = evidence.allObservations();

        addProgressionContradictions(contradictions, observations);
        addTimelineWinnerContradictions(contradictions, evidence);
        addConfirmationWinnerDisagreements(contradictions, evidence);
        addPhaseInversionContradictions(contradictions, evidence);

        return List.copyOf(contradictions);
    }

    public double maxSeverity(SettlementEvidence evidence) {
        return detect(evidence).stream()
                .mapToDouble(Contradiction::severity)
                .max()
                .orElse(0.0);
    }

    public boolean blocksAutoSettlement(SettlementEvidence evidence) {
        return blocksAutoSettlement(evidence, DEFAULT_BLOCK_THRESHOLD);
    }

    public boolean blocksAutoSettlement(SettlementEvidence evidence, double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be between 0.0 and 1.0");
        }
        return maxSeverity(evidence) > threshold;
    }

    private void addProgressionContradictions(List<Contradiction> contradictions,
                                              List<Observation> observations) {
        observations.stream()
                .filter(this::isTimelineObservation)
                .map(Observation::source)
                .distinct()
                .forEach(source -> {
                    List<Observation> bySource = observations.stream()
                            .filter(this::isTimelineObservation)
                            .filter(observation -> observation.source() == source)
                            .sorted(Comparator.comparing(Observation::observedAt))
                            .toList();
                    for (int i = 1; i < bySource.size(); i++) {
                        Observation previous = bySource.get(i - 1);
                        Observation current = bySource.get(i);
                        if (gamesRegressed(previous.score(), current.score())) {
                            contradictions.add(new Contradiction(previous, current, ContradictionKind.SCORE_DIVERGENCE, 0.85));
                            continue;
                        }
                        if (pointsRegressedWithoutNewGame(previous.score(), current.score())) {
                            contradictions.add(new Contradiction(previous, current, ContradictionKind.SCORE_DIVERGENCE, 0.60));
                        }
                    }
                });
    }

    private void addTimelineWinnerContradictions(List<Contradiction> contradictions,
                                                 SettlementEvidence evidence) {
        Optional<Observation> latestTimeline = evidence.allObservations().stream()
                .filter(this::isTimelineObservation)
                .filter(observation -> inferWinner(observation, evidence.identityLock()).isPresent())
                .max(Comparator.comparing(Observation::observedAt));
        if (latestTimeline.isEmpty()) {
            return;
        }

        OptionalLong timelineWinner = inferWinner(latestTimeline.get(), evidence.identityLock());
        if (timelineWinner.isEmpty()) {
            return;
        }

        for (Observation confirmation : confirmationObservations(evidence)) {
            OptionalLong confirmationWinner = inferWinner(confirmation, evidence.identityLock());
            if (confirmationWinner.isEmpty()) {
                continue;
            }
            if (timelineWinner.getAsLong() != confirmationWinner.getAsLong()) {
                contradictions.add(new Contradiction(
                        latestTimeline.get(),
                        confirmation,
                        ContradictionKind.WINNER_DISAGREE,
                        winnerDisagreementSeverity(latestTimeline.get())
                ));
            }
        }
    }

    private void addConfirmationWinnerDisagreements(List<Contradiction> contradictions,
                                                    SettlementEvidence evidence) {
        List<Observation> confirmations = confirmationObservations(evidence);
        for (int i = 0; i < confirmations.size(); i++) {
            Observation left = confirmations.get(i);
            OptionalLong leftWinner = inferWinner(left, evidence.identityLock());
            if (leftWinner.isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < confirmations.size(); j++) {
                Observation right = confirmations.get(j);
                OptionalLong rightWinner = inferWinner(right, evidence.identityLock());
                if (rightWinner.isEmpty()) {
                    continue;
                }
                if (leftWinner.getAsLong() != rightWinner.getAsLong()) {
                    contradictions.add(new Contradiction(left, right, ContradictionKind.WINNER_DISAGREE, 0.95));
                }
            }
        }
    }

    private void addPhaseInversionContradictions(List<Contradiction> contradictions,
                                                 SettlementEvidence evidence) {
        Optional<Observation> lastLiveLate = evidence.allObservations().stream()
                .filter(this::isTimelineObservation)
                .filter(observation -> observation.phase() == MatchPhase.LIVE_LATE)
                .max(Comparator.comparing(Observation::observedAt));
        if (lastLiveLate.isEmpty()) {
            return;
        }

        for (Observation confirmation : confirmationObservations(evidence)) {
            if (confirmation.observedAt().isBefore(lastLiveLate.get().observedAt())) {
                contradictions.add(new Contradiction(
                        confirmation,
                        lastLiveLate.get(),
                        ContradictionKind.PHASE_MISMATCH,
                        0.70
                ));
            }
        }
    }

    private List<Observation> confirmationObservations(SettlementEvidence evidence) {
        return evidence.allObservations().stream()
                .filter(observation -> observation instanceof OfficialObservation || observation instanceof DatabaseObservation)
                .filter(observation -> observation.phase().isFinished() || observation.completionSignal())
                .toList();
    }

    private OptionalLong inferWinner(Observation observation, IdentityLock identityLock) {
        if (observation instanceof OfficialObservation officialObservation) {
            return toOptionalLong(officialObservation.winnerPlayerId());
        }
        if (observation instanceof DatabaseObservation databaseObservation) {
            return toOptionalLong(databaseObservation.winnerPlayerId());
        }

        ScoreState score = observation.score();
        if (score.gamesP1() != null && score.gamesP2() != null && !score.gamesP1().equals(score.gamesP2())) {
            return OptionalLong.of(score.gamesP1() > score.gamesP2() ? identityLock.player1Id() : identityLock.player2Id());
        }
        if (score.pointsP1() != null && score.pointsP2() != null && !score.pointsP1().equals(score.pointsP2())) {
            return OptionalLong.of(score.pointsP1() > score.pointsP2() ? identityLock.player1Id() : identityLock.player2Id());
        }
        return OptionalLong.empty();
    }

    private OptionalLong toOptionalLong(Long value) {
        if (value == null || value == 0L) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(value);
    }

    private boolean isTimelineObservation(Observation observation) {
        return observation instanceof LiveObservation
                || observation instanceof MirrorObservation
                || observation instanceof StreamObservation;
    }

    private boolean gamesRegressed(ScoreState previous, ScoreState current) {
        return regressed(previous.gamesP1(), current.gamesP1())
                || regressed(previous.gamesP2(), current.gamesP2());
    }

    private boolean pointsRegressedWithoutNewGame(ScoreState previous, ScoreState current) {
        boolean sameGames = equalInts(previous.gamesP1(), current.gamesP1())
                && equalInts(previous.gamesP2(), current.gamesP2());
        if (!sameGames) {
            return false;
        }
        return regressed(previous.pointsP1(), current.pointsP1())
                || regressed(previous.pointsP2(), current.pointsP2());
    }

    private boolean regressed(Integer previous, Integer current) {
        return previous != null && current != null && current < previous;
    }

    private boolean equalInts(Integer left, Integer right) {
        return Objects.equals(left, right);
    }

    private double winnerDisagreementSeverity(Observation timelineObservation) {
        ScoreState score = timelineObservation.score();
        double severity = 0.55;
        if (score.gamesP1() != null && score.gamesP2() != null && !score.gamesP1().equals(score.gamesP2())) {
            severity = 0.75;
        }
        if (timelineObservation.phase().isFinished() || timelineObservation.completionSignal()) {
            severity = Math.min(1.0, severity + 0.15);
        }
        return severity;
    }
}
