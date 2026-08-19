package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shadow-only evaluation of what the latest score would have predicted.
 *
 * <p>These guesses never settle a bet or train the production model. Once
 * trusted outcome evidence arrives, each observation is labelled correct or
 * incorrect so score-state rules can be evaluated honestly before promotion.
 */
@Service
public class ProvisionalScoreOutcomeTracker {

    static final String TERMINAL_SCORE = "TERMINAL_SCORE";
    static final String DECISIVE_NEAR_FINAL = "DECISIVE_NEAR_FINAL";
    static final String CURRENT_SCORE_LEADER = "CURRENT_SCORE_LEADER";

    private final ScoreWinnerResolver scoreWinnerResolver;
    private final TrackedMatchObservationRepository observationRepository;

    public ProvisionalScoreOutcomeTracker(ScoreWinnerResolver scoreWinnerResolver,
                                          TrackedMatchObservationRepository observationRepository) {
        this.scoreWinnerResolver = scoreWinnerResolver;
        this.observationRepository = observationRepository;
    }

    public void annotate(TrackedMatchObservation observation) {
        if (observation == null || !StringUtils.hasText(observation.getLiveScore())
                || observation.getPlayer1Id() == null || observation.getPlayer2Id() == null) {
            return;
        }
        estimate(
                observation.getLiveScore(),
                observation.getMatchPhase(),
                observation.getPlayer1Id(),
                observation.getPlayer2Id()
        ).ifPresent(estimate -> {
            observation.setProvisionalWinnerPlayerId(estimate.winnerPlayerId());
            observation.setProvisionalOutcomeMethod(estimate.method());
            observation.setProvisionalOutcomeConfidence(estimate.confidence());
        });
    }

    public void resolve(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null || bet.getWinnerPlayerId() == null) {
            return;
        }
        List<TrackedMatchObservation> observations =
                observationRepository.findByBetIdOrderByObservedAtAsc(bet.getId());
        if (observations == null || observations.isEmpty()) {
            return;
        }
        LocalDateTime now = bet.getSettledAt() == null ? LocalDateTime.now() : bet.getSettledAt();
        boolean changed = false;
        for (TrackedMatchObservation observation : observations) {
            if (observation == null || observation.getProvisionalWinnerPlayerId() == null
                    || observation.getProvisionalResolvedAt() != null) {
                continue;
            }
            observation.setResolvedWinnerPlayerId(bet.getWinnerPlayerId());
            observation.setProvisionalCorrect(
                    Objects.equals(observation.getProvisionalWinnerPlayerId(), bet.getWinnerPlayerId())
            );
            observation.setProvisionalResolvedAt(now);
            changed = true;
        }
        if (changed) {
            observationRepository.saveAll(observations);
        }
    }

    Optional<Estimate> estimate(String rawScore, String phase, Long player1Id, Long player2Id) {
        Optional<Long> terminal = scoreWinnerResolver.determineWinnerFromScore(
                rawScore,
                player1Id,
                player2Id,
                phase,
                false
        );
        if (terminal.isPresent()) {
            return Optional.of(new Estimate(terminal.get(), TERMINAL_SCORE, 0.99));
        }
        Optional<Long> nearFinal = scoreWinnerResolver.determineWinnerFromConfidenceState(
                rawScore,
                player1Id,
                player2Id,
                phase
        );
        if (nearFinal.isPresent()) {
            return Optional.of(new Estimate(nearFinal.get(), DECISIVE_NEAR_FINAL, 0.82));
        }

        List<ScorePair> pairs = ScorePair.parseAll(rawScore);
        int setIndex = ScoreWinnerResolver.findPrimarySetScorePairIndex(pairs, 3);
        if (setIndex < 0) {
            return Optional.empty();
        }
        ScorePair sets = pairs.get(setIndex);
        int setDelta = sets.left() - sets.right();
        ScorePair points = null;
        for (int i = pairs.size() - 1; i >= 0; i--) {
            if (i != setIndex) {
                points = pairs.get(i);
                break;
            }
        }
        int pointDelta = points == null ? 0 : points.left() - points.right();
        int direction = setDelta != 0 ? Integer.signum(setDelta) : Integer.signum(pointDelta);
        if (direction == 0) {
            return Optional.empty();
        }
        double confidence = 0.50
                + (Math.min(2, Math.abs(setDelta)) * 0.09)
                + (Math.min(8, Math.abs(pointDelta)) * 0.012);
        confidence = Math.min(0.79, confidence);
        return Optional.of(new Estimate(
                direction > 0 ? player1Id : player2Id,
                CURRENT_SCORE_LEADER,
                round4(confidence)
        ));
    }

    private double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    record Estimate(Long winnerPlayerId, String method, double confidence) {
    }
}
