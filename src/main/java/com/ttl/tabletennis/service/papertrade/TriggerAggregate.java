package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeBet;

/**
 * Per-trigger weighted aggregate accumulated as the adaptive-profile builder
 * walks the recent decision history. Each {@link #add(AdaptiveDecisionSample, double)}
 * call returns a new immutable instance — the builder folds them in a loop.
 *
 * <p>Lifted from a private nested record in {@code PaperTradingService} as
 * part of the §4 decomposition (slice A). Behaviour is verbatim — the
 * {@code add} step still keys "win" off {@link PaperTradeBet#STATUS_WON}.
 *
 * @param decisions             number of samples folded in
 * @param winsWeight            sum of weights for wins (used to compute weighted win rate)
 * @param modelProbabilitySum   weighted sum of model probabilities
 * @param pnlSum                weighted sum of pnl
 * @param stakeSum              weighted sum of stakes
 * @param weightSum             total weight applied
 */
public record TriggerAggregate(int decisions,
                               double winsWeight,
                               double modelProbabilitySum,
                               double pnlSum,
                               double stakeSum,
                               double weightSum) {

    public static TriggerAggregate empty() {
        return new TriggerAggregate(0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    public TriggerAggregate add(AdaptiveDecisionSample sample, double weight) {
        double win = PaperTradeBet.STATUS_WON.equals(sample.status()) ? weight : 0.0;
        return new TriggerAggregate(
                decisions + 1,
                winsWeight + win,
                modelProbabilitySum + (sample.modelProbability() * weight),
                pnlSum + (sample.profitLoss() * weight),
                stakeSum + (sample.stake() * weight),
                weightSum + weight
        );
    }
}
