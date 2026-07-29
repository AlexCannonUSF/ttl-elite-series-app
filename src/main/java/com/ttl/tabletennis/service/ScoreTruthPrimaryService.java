package com.ttl.tabletennis.service;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.settlement.BetSettlementPolicyCatalog;
import com.ttl.tabletennis.settlement.Decision;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.ManualReview;
import com.ttl.tabletennis.settlement.Escalate;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEngine;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementEvidenceBuilder;
import com.ttl.tabletennis.settlement.SettlementPolicy;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.VoidDecision;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Phase 06 item 3: when {@code features.score-truth == primary}, the v3
 * SettlementEngine actually closes bets. The legacy 2.0 path becomes
 * cold-standby code — reachable only via feature-flag rollback (set the
 * flag back to {@code advisory} or {@code off}).
 *
 * <p>This service mutates {@link PaperTradeBet} state for terminal
 * decisions (Settle, VoidDecision) and writes the audit trail through
 * the existing {@link SettlementShadowAuditService}. Non-terminal
 * decisions (HoldOpen, Escalate, ManualReview) keep their advisory
 * semantics — bets stay open / pending / queued.
 */
@Service
public class ScoreTruthPrimaryService {

    public static final String PRIMARY_STATE = "primary";

    private static final Logger log = LoggerFactory.getLogger(ScoreTruthPrimaryService.class);

    private final FeatureFlagCatalog featureFlagCatalog;
    private final SettlementEvidenceBuilder evidenceBuilder;
    private final SettlementEngine settlementEngine;
    private final SettlementShadowAuditService auditService;
    private final BetSettlementPolicyCatalog policyCatalog;
    private final PaperTradeBetRepository betRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public ScoreTruthPrimaryService(FeatureFlagCatalog featureFlagCatalog,
                                    SettlementEvidenceBuilder evidenceBuilder,
                                    SettlementEngine settlementEngine,
                                    SettlementShadowAuditService auditService,
                                    BetSettlementPolicyCatalog policyCatalog,
                                    PaperTradeBetRepository betRepository,
                                    MeterRegistry meterRegistry) {
        this(featureFlagCatalog, evidenceBuilder, settlementEngine, auditService,
                policyCatalog, betRepository, meterRegistry, Clock.systemDefaultZone());
    }

    ScoreTruthPrimaryService(FeatureFlagCatalog featureFlagCatalog,
                             SettlementEvidenceBuilder evidenceBuilder,
                             SettlementEngine settlementEngine,
                             SettlementShadowAuditService auditService,
                             BetSettlementPolicyCatalog policyCatalog,
                             PaperTradeBetRepository betRepository,
                             MeterRegistry meterRegistry,
                             Clock clock) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.evidenceBuilder = evidenceBuilder;
        this.settlementEngine = settlementEngine;
        this.auditService = auditService;
        this.policyCatalog = policyCatalog;
        this.betRepository = betRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public boolean active() {
        if (featureFlagCatalog == null) {
            return false;
        }
        return PRIMARY_STATE.equals(featureFlagCatalog.stateOf(FeatureFlagCatalog.SCORE_TRUTH_FLAG));
    }

    /** Run the v3 closure path over the supplied open bets. Returns the
     *  closure statistics so the facade can report the same shape as the
     *  legacy 2.0 path. */
    @Transactional
    public ClosureStats closeOpenBets(List<PaperTradeBet> openBets) {
        if (!active() || openBets == null || openBets.isEmpty()) {
            return ClosureStats.empty();
        }
        if (evidenceBuilder == null || settlementEngine == null || auditService == null) {
            log.warn("[score-truth-primary] dependencies missing; skipping primary closure");
            return ClosureStats.empty();
        }

        int settled = 0;
        int voided = 0;
        int held = 0;
        int reviewed = 0;
        int skipped = 0;
        SettlementPolicy policy = currentPolicy();
        LocalDateTime now = LocalDateTime.now(clock);

        for (PaperTradeBet bet : openBets) {
            if (bet == null || bet.getId() == null) {
                continue;
            }
            if (!PaperTradeBet.STATUS_OPEN.equalsIgnoreCase(bet.getStatus())) {
                continue;
            }
            try {
                java.util.Optional<SettlementEvidence> evidence = evidenceBuilder.buildForBet(bet);
                if (evidence.isEmpty()) {
                    skipped++;
                    recordOutcome("NO_EVIDENCE");
                    continue;
                }
                SettlementEvidence settlementEvidence = evidence.get();
                Decision decision = settlementEngine.decide(settlementEvidence, policy);
                decision = enforcePostCloseStreamCvPolicy(bet, settlementEvidence, decision);
                auditService.recordAttempt(bet, settlementEvidence, decision);

                if (decision instanceof Settle settle) {
                    applySettle(bet, settle, now);
                    settled++;
                    recordOutcome("WIN_OR_LOSS");
                } else if (decision instanceof VoidDecision) {
                    // Don't apply the v3 void here. v3 voids when the
                    // observation pipeline has gone dark past the official
                    // window — but the bet's own {@code lastObservedScore}
                    // (read by the legacy fallthrough) often still has
                    // enough information to determine a W/L outcome from a
                    // partial-but-decisive final-set score. Leaving the bet
                    // OPEN here lets {@link SettlementFacade}'s legacy
                    // fallthrough take a swing at the heuristic settlement;
                    // legacy will void on its own (VOIDED_MISSING_BOARD_TIMEOUT)
                    // if even {@link ScoreWinnerResolver} can't read a winner.
                    held++;
                    recordOutcome("V3_VOID_DEFERRED_TO_LEGACY");
                } else if (decision instanceof HoldOpen holdOpen) {
                    held++;
                    recordOutcome(holdOpen.reason() == SettlementReason.SCORE_BACKED_ONLY
                            ? "SCORE_BACKED_ONLY"
                            : "HOLD_OPEN");
                } else if (decision instanceof Escalate) {
                    reviewed++;
                    recordOutcome("ESCALATE");
                } else if (decision instanceof ManualReview) {
                    reviewed++;
                    recordOutcome("MANUAL_REVIEW");
                } else {
                    skipped++;
                    recordOutcome("OTHER");
                }
            } catch (RuntimeException ex) {
                log.warn("[score-truth-primary] unable to close bet {}", bet.getId(), ex);
                skipped++;
                recordOutcome("ERROR");
            }
        }
        return new ClosureStats(settled, voided, held, reviewed, skipped);
    }

    private Decision enforcePostCloseStreamCvPolicy(PaperTradeBet bet,
                                                    SettlementEvidence evidence,
                                                    Decision decision) {
        if (!streamCvRequired(bet) || streamCvPresent(evidence) || !(decision instanceof Settle settle)) {
            return decision;
        }
        if (!scoreBackedPrimaryClosure(settle.reason())) {
            return decision;
        }
        return new HoldOpen(evidence, SettlementReason.SCORE_BACKED_ONLY,
                "stream-cv required before primary score-backed closure after market close");
    }

    private boolean streamCvRequired(PaperTradeBet bet) {
        return bet != null && bet.isTrackedAfterClose();
    }

    private boolean streamCvPresent(SettlementEvidence evidence) {
        return evidence != null && evidence.streamObservations() != null
                && !evidence.streamObservations().isEmpty();
    }

    private boolean scoreBackedPrimaryClosure(SettlementReason reason) {
        return switch (reason) {
            case SCORE_BACKED_DECISIVE,
                    SCORE_BACKED_FINISHED,
                    TARGETED_COMPLETION_SIGNAL,
                    LAST_SCORE_HEURISTIC -> true;
            default -> false;
        };
    }

    private void applySettle(PaperTradeBet bet, Settle decision, LocalDateTime settledAt) {
        long winner = decision.winnerPlayerId();
        boolean won = bet.getSidePlayerId() != null && Objects.equals(bet.getSidePlayerId(), winner);
        double stake = bet.getStake();
        double odds = bet.getDecimalOdds();
        double profit = won ? stake * Math.max(0.0, odds - 1.0) : -stake;
        bet.setStatus(won ? PaperTradeBet.STATUS_WON : PaperTradeBet.STATUS_LOST);
        bet.setWinnerPlayerId(winner);
        bet.setProfitLoss(profit);
        bet.setSettledAt(settledAt);
        // #122 — Persist settlementSource + settlementReason so per-path
        // integrity counters (IntegrityService.scoreBackedSettlements etc.)
        // see v3 closures. Previously v3 wrote neither field, so 245 lifetime
        // v3 closures showed as 0 in the integrity dashboard — operators had
        // no way to tell which settlement path was doing the work.
        bet.setSettlementSource(reasonToSource(decision.reason()));
        bet.setSettlementReason(decision.reason() == null
                ? "V3_PRIMARY"
                : "V3_PRIMARY_" + decision.reason().name());
        save(bet);
    }

    private void applyVoid(PaperTradeBet bet, LocalDateTime settledAt) {
        bet.setStatus(PaperTradeBet.STATUS_VOIDED);
        bet.setProfitLoss(0.0);
        bet.setSettledAt(settledAt);
        bet.setWinnerPlayerId(null);
        // #122 — same as applySettle, persist source/reason so the integrity
        // dashboard's voidedSettlements counter sees v3 voids.
        bet.setSettlementSource("V3_PRIMARY_VOID");
        bet.setSettlementReason("V3_PRIMARY_VOIDED_NO_EVIDENCE");
        save(bet);
    }

    /**
     * Map a v3 {@link SettlementReason} onto the legacy
     * {@code settlement_source} string format that {@link IntegrityService}
     * pattern-matches for its per-path counters.
     */
    private static String reasonToSource(SettlementReason reason) {
        if (reason == null) {
            return "V3_PRIMARY";
        }
        return switch (reason) {
            case OFFICIAL_RESULT_CONFIRMED -> "SETTLED_FROM_OFFICIAL_RESULT_V3";
            case DATABASE_RESULT_CONFIRMED -> "SETTLED_FROM_DATABASE_RESULT_V3";
            case TARGETED_COMPLETION_SIGNAL -> "SETTLED_FROM_TARGETED_MATCH_COMPLETED_V3";
            case SCORE_BACKED_DECISIVE, SCORE_BACKED_FINISHED -> "SETTLED_FROM_SCORE_BACKED_V3";
            case LAST_SCORE_HEURISTIC -> "SETTLED_FROM_HEURISTIC_V3";
            default -> "V3_PRIMARY_" + reason.name();
        };
    }

    private void save(PaperTradeBet bet) {
        if (betRepository == null) {
            return;
        }
        try {
            betRepository.save(bet);
        } catch (RuntimeException ex) {
            log.warn("[score-truth-primary] unable to persist bet {} after v3 closure: {}",
                    bet == null ? null : bet.getId(), ex.getMessage());
        }
    }

    private SettlementPolicy currentPolicy() {
        if (policyCatalog == null) {
            return SettlementPolicy.defaults();
        }
        try {
            SettlementPolicy policy = policyCatalog.currentPolicy();
            return policy == null ? SettlementPolicy.defaults() : policy;
        } catch (RuntimeException ex) {
            log.warn("[score-truth-primary] policy lookup failed: {}", ex.getMessage());
            return SettlementPolicy.defaults();
        }
    }

    private void recordOutcome(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("ttl.score_truth.primary.closures", "outcome", outcome).increment();
    }

    public record ClosureStats(int settled, int voided, int held, int reviewed, int skipped) {

        public static ClosureStats empty() {
            return new ClosureStats(0, 0, 0, 0, 0);
        }

        public int total() {
            return settled + voided + held + reviewed + skipped;
        }
    }
}
