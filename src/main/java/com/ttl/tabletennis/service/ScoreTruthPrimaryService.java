package com.ttl.tabletennis.service;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.prediction.staking.ClosingLineLookupService;
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
import com.ttl.tabletennis.settlement.ScoreEvidenceAnalyzer;
import com.ttl.tabletennis.settlement.ScoreEvidenceAssessment;
import com.ttl.tabletennis.settlement.VoidDecision;
import com.ttl.tabletennis.service.papertrade.LearningSampleQuality;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
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
    public static final String LEARNING_SETTLEMENT_METRIC = "ttl.model.learning.settled_samples";

    private static final Logger log = LoggerFactory.getLogger(ScoreTruthPrimaryService.class);

    private final FeatureFlagCatalog featureFlagCatalog;
    private final SettlementEvidenceBuilder evidenceBuilder;
    private final SettlementEngine settlementEngine;
    private final SettlementShadowAuditService auditService;
    private final BetSettlementPolicyCatalog policyCatalog;
    private final PaperTradeBetRepository betRepository;
    private final ClosingLineLookupService closingLineLookupService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public ScoreTruthPrimaryService(FeatureFlagCatalog featureFlagCatalog,
                                    SettlementEvidenceBuilder evidenceBuilder,
                                    SettlementEngine settlementEngine,
                                    SettlementShadowAuditService auditService,
                                    BetSettlementPolicyCatalog policyCatalog,
                                    PaperTradeBetRepository betRepository,
                                    ClosingLineLookupService closingLineLookupService,
                                    MeterRegistry meterRegistry) {
        this(featureFlagCatalog, evidenceBuilder, settlementEngine, auditService,
                policyCatalog, betRepository, closingLineLookupService, meterRegistry, Clock.systemDefaultZone());
    }

    ScoreTruthPrimaryService(FeatureFlagCatalog featureFlagCatalog,
                             SettlementEvidenceBuilder evidenceBuilder,
                             SettlementEngine settlementEngine,
                             SettlementShadowAuditService auditService,
                             BetSettlementPolicyCatalog policyCatalog,
                             PaperTradeBetRepository betRepository,
                             MeterRegistry meterRegistry,
                             Clock clock) {
        this(featureFlagCatalog, evidenceBuilder, settlementEngine, auditService,
                policyCatalog, betRepository, null, meterRegistry, clock);
    }

    ScoreTruthPrimaryService(FeatureFlagCatalog featureFlagCatalog,
                             SettlementEvidenceBuilder evidenceBuilder,
                             SettlementEngine settlementEngine,
                             SettlementShadowAuditService auditService,
                             BetSettlementPolicyCatalog policyCatalog,
                             PaperTradeBetRepository betRepository,
                             ClosingLineLookupService closingLineLookupService,
                             MeterRegistry meterRegistry,
                             Clock clock) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.evidenceBuilder = evidenceBuilder;
        this.settlementEngine = settlementEngine;
        this.auditService = auditService;
        this.policyCatalog = policyCatalog;
        this.betRepository = betRepository;
        this.closingLineLookupService = closingLineLookupService;
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
                ScoreEvidenceAssessment scoreEvidence = ScoreEvidenceAnalyzer.assess(settlementEvidence);
                boolean scoreEvidenceChanged = applyScoreEvidence(bet, scoreEvidence);
                Decision decision = settlementEngine.decide(settlementEvidence, policy);
                decision = enforcePostCloseStreamCvPolicy(bet, settlementEvidence, decision);
                SettlementShadowAuditService.AuditWriteResult audit =
                        auditService.recordAttempt(bet, settlementEvidence, decision);

                if (decision instanceof Settle settle) {
                    applySettle(bet, settle, audit, now);
                    settled++;
                    recordOutcome("WIN_OR_LOSS");
                } else if (decision instanceof VoidDecision voidDecision) {
                    applyVoid(bet, voidDecision, audit, now);
                    voided++;
                    recordOutcome("VOID");
                } else if (decision instanceof HoldOpen holdOpen) {
                    if (scoreEvidenceChanged) {
                        save(bet);
                    }
                    held++;
                    recordOutcome(holdOpen.reason() == SettlementReason.SCORE_BACKED_ONLY
                            ? "SCORE_BACKED_ONLY"
                            : "HOLD_OPEN");
                } else if (decision instanceof Escalate) {
                    if (scoreEvidenceChanged) {
                        save(bet);
                    }
                    reviewed++;
                    recordOutcome("ESCALATE");
                } else if (decision instanceof ManualReview) {
                    if (scoreEvidenceChanged) {
                        save(bet);
                    }
                    reviewed++;
                    recordOutcome("MANUAL_REVIEW");
                } else {
                    if (scoreEvidenceChanged) {
                        save(bet);
                    }
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
        return bet != null
                && bet.isTrackedAfterClose()
                && featureFlagCatalog != null
                && "on".equals(featureFlagCatalog.stateOf(FeatureFlagCatalog.STREAM_CV_FLAG));
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
                    STREAM_CV_CONSENSUS,
                    LAST_SCORE_HEURISTIC -> true;
            default -> false;
        };
    }

    private void applySettle(PaperTradeBet bet,
                             Settle decision,
                             SettlementShadowAuditService.AuditWriteResult audit,
                             LocalDateTime settledAt) {
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
        bet.setResultMatchId(resolveResultMatchId(decision));
        applyProvenance(bet, decision.evidence(), decision.confidence(), audit);
        captureClosingLine(bet);
        recordScoreEvidence(bet, decision.reason());
        save(bet);
        recordLearningEligibility(bet, audit);
    }

    private void applyVoid(PaperTradeBet bet,
                           VoidDecision decision,
                           SettlementShadowAuditService.AuditWriteResult audit,
                           LocalDateTime settledAt) {
        bet.setStatus(PaperTradeBet.STATUS_VOIDED);
        bet.setProfitLoss(0.0);
        bet.setSettledAt(settledAt);
        bet.setWinnerPlayerId(null);
        // #122 — same as applySettle, persist source/reason so the integrity
        // dashboard's voidedSettlements counter sees v3 voids.
        bet.setSettlementSource("V3_PRIMARY_VOID");
        bet.setSettlementReason(decision.reason() == null
                ? "V3_PRIMARY_VOID"
                : "V3_PRIMARY_" + decision.reason().name());
        applyProvenance(bet, decision.evidence(), decision.evidence().confidence(), audit);
        captureClosingLine(bet);
        recordScoreEvidence(bet, decision.reason());
        save(bet);
        recordLearningEligibility(bet, audit);
    }

    private boolean applyScoreEvidence(PaperTradeBet bet, ScoreEvidenceAssessment assessment) {
        if (bet == null || assessment == null) {
            return false;
        }
        boolean changed = !Objects.equals(bet.getScoreEvidenceQuality(), assessment.quality().name())
                || !Objects.equals(bet.getScoreEvidenceFinality(), assessment.finality().name())
                || !sameDouble(bet.getScoreEvidenceConfidence(), assessment.confidence())
                || !Objects.equals(bet.getScoreEvidenceObservationCount(), assessment.observationCount())
                || !Objects.equals(bet.getScoreEvidenceSourceCount(), assessment.distinctSourceCount())
                || !Objects.equals(bet.getScoreEvidenceAgreeingSources(), assessment.agreeingSourceCount())
                || !Objects.equals(bet.getScoreEvidenceCompletionSignals(), assessment.completionSignalCount())
                || !Objects.equals(bet.getScoreEvidenceInferredWinnerId(), assessment.inferredWinnerPlayerId())
                || !Objects.equals(bet.getScoreEvidenceLatestScore(), emptyToNull(assessment.latestScore()))
                || !Objects.equals(bet.getScoreEvidenceLatestPhase(), emptyToNull(assessment.latestPhase()))
                || bet.isScoreEvidenceContradictory() != assessment.contradictory();
        if (!changed) {
            return false;
        }
        bet.setScoreEvidenceQuality(assessment.quality().name());
        bet.setScoreEvidenceFinality(assessment.finality().name());
        bet.setScoreEvidenceConfidence(assessment.confidence());
        bet.setScoreEvidenceObservationCount(assessment.observationCount());
        bet.setScoreEvidenceSourceCount(assessment.distinctSourceCount());
        bet.setScoreEvidenceAgreeingSources(assessment.agreeingSourceCount());
        bet.setScoreEvidenceCompletionSignals(assessment.completionSignalCount());
        bet.setScoreEvidenceInferredWinnerId(assessment.inferredWinnerPlayerId());
        bet.setScoreEvidenceLatestScore(emptyToNull(assessment.latestScore()));
        bet.setScoreEvidenceLatestPhase(emptyToNull(assessment.latestPhase()));
        bet.setScoreEvidenceContradictory(assessment.contradictory());
        return true;
    }

    private boolean sameDouble(Double left, double right) {
        return left != null && Math.abs(left - right) < 0.000001;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void applyProvenance(PaperTradeBet bet,
                                 SettlementEvidence evidence,
                                 double confidence,
                                 SettlementShadowAuditService.AuditWriteResult audit) {
        bet.setSettlementConfidence(clamp01(confidence));
        bet.setSettlementEvidenceId(audit == null ? null : audit.evidenceId());
        bet.setSettlementEvidenceFingerprint(audit == null
                ? SettlementFingerprint.evidence(evidence)
                : audit.evidenceFingerprint());
        bet.setSettlementEvidenceSourceCount(evidence == null ? 0 : evidence.distinctSources().size());
        bet.setSettlementCoverageState(evidence == null || evidence.coverageState() == null
                ? null
                : evidence.coverageState().name());
        bet.setSettlementAmbiguityScore(evidence == null ? null : evidence.ambiguityScore());
        bet.setSettlementObservedAt(evidence == null || evidence.bundleAsOf() == null
                ? null
                : LocalDateTime.ofInstant(evidence.bundleAsOf(), ZoneId.systemDefault()));
    }

    private Long resolveResultMatchId(Settle decision) {
        if (decision == null || decision.evidence() == null || decision.reason() == null) {
            return null;
        }
        long winner = decision.winnerPlayerId();
        if (decision.reason() == SettlementReason.OFFICIAL_RESULT_CONFIRMED) {
            return decision.evidence().officialCandidates().stream()
                    .filter(candidate -> candidate.completed() && Objects.equals(candidate.winnerPlayerId(), winner))
                    .max(Comparator.comparingDouble(com.ttl.tabletennis.settlement.OfficialCandidate::confidence)
                            .thenComparing(com.ttl.tabletennis.settlement.OfficialCandidate::observedAt))
                    .map(com.ttl.tabletennis.settlement.OfficialCandidate::matchId)
                    .orElse(null);
        }
        if (decision.reason() == SettlementReason.DATABASE_RESULT_CONFIRMED) {
            return decision.evidence().databaseCandidates().stream()
                    .filter(candidate -> candidate.completed() && Objects.equals(candidate.winnerPlayerId(), winner))
                    .max(Comparator.comparingDouble(com.ttl.tabletennis.settlement.DatabaseCandidate::confidence)
                            .thenComparing(com.ttl.tabletennis.settlement.DatabaseCandidate::observedAt))
                    .map(com.ttl.tabletennis.settlement.DatabaseCandidate::matchId)
                    .orElse(null);
        }
        return null;
    }

    private void captureClosingLine(PaperTradeBet bet) {
        if (closingLineLookupService == null || bet == null || bet.getClosingDecimalOdds() != null) {
            return;
        }
        closingLineLookupService.findFor(bet).ifPresent(line -> {
            bet.setClosingDecimalOdds(line.decimalOdds());
            bet.setClosingObservedAt(line.observedAt());
            bet.setClosingSource(line.sourceId());
            bet.setClosingMarketState(line.marketState());
        });
    }

    private double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
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
        betRepository.save(bet);
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

    private void recordScoreEvidence(PaperTradeBet bet, SettlementReason reason) {
        if (meterRegistry == null || bet == null) {
            return;
        }
        meterRegistry.counter(
                "ttl.score_truth.primary.score_evidence",
                "quality",
                bet.getScoreEvidenceQuality() == null ? "UNKNOWN" : bet.getScoreEvidenceQuality(),
                "finality",
                bet.getScoreEvidenceFinality() == null ? "UNKNOWN" : bet.getScoreEvidenceFinality(),
                "reason",
                reason == null ? "UNKNOWN" : reason.name()
        ).increment();
    }

    private void recordLearningEligibility(PaperTradeBet bet,
                                           SettlementShadowAuditService.AuditWriteResult audit) {
        LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(bet);
        if (auditService != null && audit != null) {
            auditService.recordLearningEligibility(audit.evidenceId(), bet);
        }
        if (meterRegistry != null) {
            meterRegistry.counter(
                    LEARNING_SETTLEMENT_METRIC,
                    "eligibility", assessment.learningEligible() ? "trusted" : "excluded",
                    "reason", assessment.exclusionReason() == null ? "ELIGIBLE" : assessment.exclusionReason()
            ).increment();
        }
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
