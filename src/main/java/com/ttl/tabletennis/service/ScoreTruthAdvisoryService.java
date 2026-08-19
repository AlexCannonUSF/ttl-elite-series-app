package com.ttl.tabletennis.service;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.settlement.BetSettlementPolicyCatalog;
import com.ttl.tabletennis.settlement.Decision;
import com.ttl.tabletennis.settlement.Escalate;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.ManualReview;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEngine;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementEvidenceBuilder;
import com.ttl.tabletennis.settlement.SettlementPolicy;
import com.ttl.tabletennis.settlement.VoidDecision;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class ScoreTruthAdvisoryService {

    public static final String ADVISORY_NO_EVIDENCE = "ADVISORY_NO_EVIDENCE";
    public static final String PENDING_EVIDENCE_AWAITING_EVIDENCE = "PENDING_EVIDENCE_AWAITING_EVIDENCE";
    public static final String PENDING_EVIDENCE_TTL_EXPIRED = "PENDING_EVIDENCE_TTL_EXPIRED";
    public static final String PENDING_EVIDENCE_REVIEW_READY = "PENDING_EVIDENCE_REVIEW_READY";

    private static final Logger log = LoggerFactory.getLogger(ScoreTruthAdvisoryService.class);
    private static final int DEFAULT_HOLD_OPEN_TTL_MINUTES = 180;
    private static final int DEFAULT_HOLD_OPEN_POLL_INTERVAL_MINUTES = 5;
    private static final int DEFAULT_HOLD_OPEN_MAX_POLL_BATCH_SIZE = 50;
    private static final int PENDING_REASON_MAX_LENGTH = 96;
    private static final int PENDING_NOTE_MAX_LENGTH = 256;

    private final FeatureFlagCatalog featureFlagCatalog;
    private final SettlementEvidenceBuilder settlementEvidenceBuilder;
    private final SettlementEngine settlementEngine;
    private final SettlementShadowAuditService settlementShadowAuditService;
    private final MeterRegistry meterRegistry;
    private final PaperTradeBetRepository betRepository;
    private final BetSettlementPolicyCatalog betSettlementPolicyCatalog;
    private final Clock clock;

    @Value("${ttl.score-truth.hold-open.ttlMinutes:180}")
    private int holdOpenTtlMinutes = DEFAULT_HOLD_OPEN_TTL_MINUTES;

    @Value("${ttl.score-truth.hold-open.pollIntervalMinutes:5}")
    private int holdOpenPollIntervalMinutes = DEFAULT_HOLD_OPEN_POLL_INTERVAL_MINUTES;

    @Value("${ttl.score-truth.hold-open.maxPollBatchSize:50}")
    private int holdOpenMaxPollBatchSize = DEFAULT_HOLD_OPEN_MAX_POLL_BATCH_SIZE;

    @Autowired
    public ScoreTruthAdvisoryService(FeatureFlagCatalog featureFlagCatalog,
                                     SettlementEvidenceBuilder settlementEvidenceBuilder,
                                     SettlementEngine settlementEngine,
                                     SettlementShadowAuditService settlementShadowAuditService,
                                     MeterRegistry meterRegistry,
                                     PaperTradeBetRepository betRepository,
                                     BetSettlementPolicyCatalog betSettlementPolicyCatalog) {
        this(
                featureFlagCatalog,
                settlementEvidenceBuilder,
                settlementEngine,
                settlementShadowAuditService,
                meterRegistry,
                betRepository,
                betSettlementPolicyCatalog,
                Clock.systemDefaultZone()
        );
    }

    ScoreTruthAdvisoryService(FeatureFlagCatalog featureFlagCatalog,
                              SettlementEvidenceBuilder settlementEvidenceBuilder,
                              SettlementEngine settlementEngine,
                              SettlementShadowAuditService settlementShadowAuditService,
                              MeterRegistry meterRegistry,
                              PaperTradeBetRepository betRepository,
                              BetSettlementPolicyCatalog betSettlementPolicyCatalog,
                              Clock clock) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.settlementEvidenceBuilder = settlementEvidenceBuilder;
        this.settlementEngine = settlementEngine;
        this.settlementShadowAuditService = settlementShadowAuditService;
        this.meterRegistry = meterRegistry;
        this.betRepository = betRepository;
        this.betSettlementPolicyCatalog = betSettlementPolicyCatalog;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    ScoreTruthAdvisoryService(FeatureFlagCatalog featureFlagCatalog,
                              SettlementEvidenceBuilder settlementEvidenceBuilder,
                              SettlementEngine settlementEngine,
                              SettlementShadowAuditService settlementShadowAuditService,
                              MeterRegistry meterRegistry,
                              PaperTradeBetRepository betRepository,
                              Clock clock) {
        this(
                featureFlagCatalog,
                settlementEvidenceBuilder,
                settlementEngine,
                settlementShadowAuditService,
                meterRegistry,
                betRepository,
                null,
                clock
        );
    }

    public boolean active() {
        String state = featureFlagCatalog == null
                ? "off"
                : featureFlagCatalog.stateOf(FeatureFlagCatalog.SCORE_TRUTH_FLAG);
        return "advisory".equals(state) || "primary".equals(state);
    }

    @Transactional
    public int recordAdvisoryDecisions(List<PaperTradeBet> trackedOpenBets) {
        if (!active()
                || trackedOpenBets == null
                || trackedOpenBets.isEmpty()
                || settlementEvidenceBuilder == null
                || settlementEngine == null
                || settlementShadowAuditService == null) {
            return 0;
        }

        int recorded = 0;
        for (PaperTradeBet bet : trackedOpenBets) {
            if (bet == null || bet.getId() == null) {
                continue;
            }
            try {
                Optional<SettlementEvidence> evidence = settlementEvidenceBuilder.buildForBet(bet);
                if (evidence.isEmpty()) {
                    settlementShadowAuditService.recordNoEvidenceAttempt(bet, ADVISORY_NO_EVIDENCE);
                    recordMetric("NO_EVIDENCE");
                    recorded++;
                    continue;
                }

                Decision decision = settlementEngine.decide(evidence.get(), currentPolicy());
                if (!actionableAdvisoryDecision(decision)) {
                    recordMetric("OBSERVED_" + decisionType(decision));
                    continue;
                }

                settlementShadowAuditService.recordAttempt(bet, evidence.get(), decision);
                if (decision instanceof HoldOpen holdOpen) {
                    persistHoldOpen(bet, holdOpen, now(), true);
                }
                recordMetric(decisionType(decision));
                recorded++;
            } catch (RuntimeException ex) {
                log.warn("[score-truth-advisory] unable to record advisory decision for bet {}", bet.getId(), ex);
                recordMetric("ERROR");
            }
        }
        return recorded;
    }

    @Scheduled(fixedDelayString = "${ttl.score-truth.hold-open.pollFixedDelayMs:30000}")
    @Transactional
    public int pollPendingEvidence() {
        if (!active() || betRepository == null) {
            return 0;
        }

        LocalDateTime now = now();
        int limit = Math.max(1, holdOpenMaxPollBatchSize <= 0
                ? DEFAULT_HOLD_OPEN_MAX_POLL_BATCH_SIZE
                : holdOpenMaxPollBatchSize);
        List<PaperTradeBet> dueBets = betRepository
                .findByStatusAndPendingEvidenceNextPollAtLessThanEqualOrderByPendingEvidenceNextPollAtAsc(
                        PaperTradeBet.STATUS_PENDING_EVIDENCE,
                        now,
                        PageRequest.of(0, limit)
                );

        int processed = 0;
        for (PaperTradeBet bet : dueBets) {
            try {
                if (reevaluatePendingEvidenceBet(bet, now)) {
                    processed++;
                }
            } catch (RuntimeException ex) {
                log.warn("[score-truth-advisory] unable to poll pending evidence for bet {}", bet == null ? null : bet.getId(), ex);
                recordHoldOpenMetric("error");
            }
        }
        return processed;
    }

    private boolean reevaluatePendingEvidenceBet(PaperTradeBet bet, LocalDateTime now) {
        if (bet == null
                || bet.getId() == null
                || !PaperTradeBet.STATUS_PENDING_EVIDENCE.equalsIgnoreCase(safeText(bet.getStatus()))) {
            return false;
        }

        if (expired(bet, now)) {
            markPendingEvidence(
                    bet,
                    PENDING_EVIDENCE_TTL_EXPIRED,
                    "TTL expired awaiting score-truth evidence",
                    null,
                    now
            );
            if (settlementShadowAuditService != null) {
                settlementShadowAuditService.recordNoEvidenceAttempt(bet, PENDING_EVIDENCE_TTL_EXPIRED);
            }
            recordHoldOpenMetric("ttl_expired");
            return true;
        }

        Optional<SettlementEvidence> evidence = settlementEvidenceBuilder == null
                ? Optional.empty()
                : settlementEvidenceBuilder.buildForBet(bet);
        if (evidence.isEmpty() || settlementEngine == null) {
            markPendingEvidence(
                    bet,
                    PENDING_EVIDENCE_AWAITING_EVIDENCE,
                    "Awaiting score-truth evidence",
                    nextPollAt(now),
                    now
            );
            recordHoldOpenMetric("rescheduled_no_evidence");
            return true;
        }

        Decision decision = settlementEngine.decide(evidence.get(), currentPolicy());
        if (decision instanceof HoldOpen holdOpen) {
            if (settlementShadowAuditService != null) {
                settlementShadowAuditService.recordAttempt(bet, evidence.get(), holdOpen);
            }
            persistHoldOpen(bet, holdOpen, now, false);
            recordMetric("POLL_HOLD_OPEN");
            return true;
        }

        if (decision instanceof ManualReview || decision instanceof Escalate) {
            if (settlementShadowAuditService != null) {
                settlementShadowAuditService.recordAttempt(bet, evidence.get(), decision);
            }
            boolean keepPolling = decision instanceof Escalate;
            markPendingEvidence(
                    bet,
                    decision.reason().name(),
                    decisionType(decision) + " requires operator review",
                    keepPolling ? nextPollAt(now) : null,
                    now
            );
            recordMetric("POLL_" + decisionType(decision));
            recordHoldOpenMetric(keepPolling ? "escalate_rescheduled" : "manual_review");
            return true;
        }

        markPendingEvidence(
                bet,
                PENDING_EVIDENCE_REVIEW_READY,
                decisionType(decision) + " requires operator confirmation",
                null,
                now
        );
        recordMetric("POLL_OBSERVED_" + decisionType(decision));
        recordHoldOpenMetric("review_ready");
        return true;
    }

    private boolean actionableAdvisoryDecision(Decision decision) {
        return decision instanceof ManualReview
                || decision instanceof HoldOpen
                || decision instanceof Escalate;
    }

    private SettlementPolicy currentPolicy() {
        return betSettlementPolicyCatalog == null ? SettlementPolicy.defaults() : betSettlementPolicyCatalog.currentPolicy();
    }

    private String decisionType(Decision decision) {
        if (decision instanceof ManualReview) {
            return "MANUAL_REVIEW";
        }
        if (decision instanceof HoldOpen) {
            return "HOLD_OPEN";
        }
        if (decision instanceof Escalate) {
            return "ESCALATE";
        }
        if (decision instanceof Settle) {
            return "SETTLE";
        }
        if (decision instanceof VoidDecision) {
            return "VOID";
        }
        return decision == null ? "UNKNOWN" : decision.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private void persistHoldOpen(PaperTradeBet bet,
                                 HoldOpen holdOpen,
                                 LocalDateTime now,
                                 boolean refreshTtl) {
        if (bet == null || holdOpen == null) {
            return;
        }
        if (refreshTtl || bet.getPendingEvidenceUntil() == null) {
            bet.setPendingEvidenceUntil(now.plusMinutes(positiveMinutes(holdOpenTtlMinutes, DEFAULT_HOLD_OPEN_TTL_MINUTES)));
        }
        markPendingEvidence(
                bet,
                holdOpen.reason().name(),
                holdOpen.note(),
                nextPollAt(now),
                now
        );
        recordHoldOpenMetric(refreshTtl ? "saved" : "rescheduled");
    }

    private void markPendingEvidence(PaperTradeBet bet,
                                     String reason,
                                     String note,
                                     LocalDateTime nextPollAt,
                                     LocalDateTime now) {
        bet.setStatus(PaperTradeBet.STATUS_PENDING_EVIDENCE);
        bet.setPendingEvidenceReason(truncate(reason, PENDING_REASON_MAX_LENGTH));
        bet.setPendingEvidenceNote(truncate(note, PENDING_NOTE_MAX_LENGTH));
        bet.setPendingEvidenceNextPollAt(nextPollAt);
        bet.setPendingEvidenceUpdatedAt(now);
        if (betRepository != null) {
            betRepository.save(bet);
        }
    }

    private LocalDateTime nextPollAt(LocalDateTime now) {
        return now.plusMinutes(positiveMinutes(holdOpenPollIntervalMinutes, DEFAULT_HOLD_OPEN_POLL_INTERVAL_MINUTES));
    }

    private boolean expired(PaperTradeBet bet, LocalDateTime now) {
        return bet.getPendingEvidenceUntil() != null && !bet.getPendingEvidenceUntil().isAfter(now);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private int positiveMinutes(int value, int fallback) {
        if (value > 0) {
            return value;
        }
        return fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private void recordMetric(String decision) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                "ttl.score_truth.advisory.decisions.persisted",
                "decision",
                decision == null || decision.isBlank() ? "UNKNOWN" : decision
        ).increment();
    }

    private void recordHoldOpenMetric(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                "ttl.score_truth.hold_open.pending",
                "outcome",
                outcome == null || outcome.isBlank() ? "unknown" : outcome
        ).increment();
    }
}
