package com.ttl.tabletennis.service.papertrade;

import java.time.LocalDateTime;

/**
 * Row in the adaptive learning history — one settled decision the placement
 * loop has weighed in. Bundles the bet's terminal characteristics
 * (status, prob, edge, stake, pnl, confidence width, settled-at timestamp)
 * so the {@code AdaptiveProfileBuilder} can compute a weighted calibration
 * snapshot.
 *
 * <p>Lifted from a private nested record in {@code PaperTradingService} as
 * part of the §4 decomposition (slice A). Behaviour is verbatim from the
 * original; this is a pure-data carrier with no methods.
 */
public record AdaptiveDecisionSample(Long betId,
                                     String topTrigger,
                                     String status,
                                     double modelProbability,
                                     double impliedProbability,
                                     double edge,
                                     double stake,
                                     double profitLoss,
                                     double confidenceWidth,
                                     LocalDateTime settledAt) {
}
