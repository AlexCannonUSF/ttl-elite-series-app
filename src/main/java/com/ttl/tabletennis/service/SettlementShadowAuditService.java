package com.ttl.tabletennis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.cv.CvAuditEvidenceStore;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.SettlementAuditRecord;
import com.ttl.tabletennis.domain.SettlementContradictionRecord;
import com.ttl.tabletennis.domain.SettlementEvidenceRecord;
import com.ttl.tabletennis.repository.SettlementAuditRecordRepository;
import com.ttl.tabletennis.repository.SettlementContradictionRecordRepository;
import com.ttl.tabletennis.repository.SettlementEvidenceRecordRepository;
import com.ttl.tabletennis.settlement.Contradiction;
import com.ttl.tabletennis.settlement.Decision;
import com.ttl.tabletennis.settlement.Escalate;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.ManualReview;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.ScoreEvidenceAnalyzer;
import com.ttl.tabletennis.settlement.ScoreEvidenceAssessment;
import com.ttl.tabletennis.settlement.VoidDecision;
import com.ttl.tabletennis.service.papertrade.LearningSampleQuality;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SettlementShadowAuditService {

    static final String DECISION_SETTLE = "SETTLE";
    static final String DECISION_HOLD_OPEN = "HOLD_OPEN";
    static final String DECISION_ESCALATE = "ESCALATE";
    static final String DECISION_VOID = "VOID";
    static final String DECISION_MANUAL_REVIEW = "MANUAL_REVIEW";
    static final String REVIEW_OPEN = "OPEN";
    static final String REVIEW_ACCEPTED = "ACCEPTED";
    static final String REVIEW_REJECTED = "REJECTED";
    static final String REVIEW_SUPERSEDED = "SUPERSEDED";
    static final String REVIEW_RESOLVED = "RESOLVED";

    private final SettlementEvidenceRecordRepository settlementEvidenceRecordRepository;
    private final SettlementContradictionRecordRepository settlementContradictionRecordRepository;
    private final SettlementAuditRecordRepository settlementAuditRecordRepository;
    private final ObjectMapper objectMapper;
    private final Optional<CvAuditEvidenceStore> cvAuditEvidenceStore;

    @Autowired
    public SettlementShadowAuditService(SettlementEvidenceRecordRepository settlementEvidenceRecordRepository,
                                        SettlementContradictionRecordRepository settlementContradictionRecordRepository,
                                        SettlementAuditRecordRepository settlementAuditRecordRepository,
                                        ObjectMapper objectMapper,
                                        Optional<CvAuditEvidenceStore> cvAuditEvidenceStore) {
        this.settlementEvidenceRecordRepository = settlementEvidenceRecordRepository;
        this.settlementContradictionRecordRepository = settlementContradictionRecordRepository;
        this.settlementAuditRecordRepository = settlementAuditRecordRepository;
        this.objectMapper = objectMapper;
        this.cvAuditEvidenceStore = cvAuditEvidenceStore == null ? Optional.empty() : cvAuditEvidenceStore;
    }

    public SettlementShadowAuditService(SettlementEvidenceRecordRepository settlementEvidenceRecordRepository,
                                        SettlementContradictionRecordRepository settlementContradictionRecordRepository,
                                        SettlementAuditRecordRepository settlementAuditRecordRepository,
                                        ObjectMapper objectMapper) {
        this(settlementEvidenceRecordRepository,
                settlementContradictionRecordRepository,
                settlementAuditRecordRepository,
                objectMapper,
                Optional.empty());
    }

    @Transactional
    public AuditWriteResult recordAttempt(PaperTradeBet bet, SettlementEvidence evidence, Decision decision) {
        String evidenceFingerprint = SettlementFingerprint.evidence(evidence);
        SettlementEvidenceRecord evidenceRecord = findOrCreateEvidenceRecord(evidence, evidenceFingerprint);
        String decisionFingerprint = SettlementFingerprint.decision(bet, evidenceFingerprint, decision);
        if (settlementAuditRecordRepository.existsByDecisionFingerprint(decisionFingerprint)) {
            return new AuditWriteResult(false, null, evidenceRecord.getId(), evidenceFingerprint, decisionFingerprint);
        }
        String evidenceRefs = collectCvAuditEvidenceRefs(evidence);
        SettlementAuditRecord audit = recordAudit(
                bet,
                evidence,
                decision,
                evidenceRecord.getId(),
                serializeDecisionPayload(bet, evidence, decision),
                evidenceRefs,
                decisionFingerprint
        );
        return new AuditWriteResult(true, audit.getId(), evidenceRecord.getId(), evidenceFingerprint, decisionFingerprint);
    }

    @Transactional
    public AuditWriteResult recordNoEvidenceAttempt(PaperTradeBet bet, String reason) {
        String decisionFingerprint = SettlementFingerprint.noEvidenceDecision(bet, reason);
        if (settlementAuditRecordRepository.existsByDecisionFingerprint(decisionFingerprint)) {
            return new AuditWriteResult(false, null, null, null, decisionFingerprint);
        }
        supersedeOpenReviews(bet == null ? null : bet.getId(), REVIEW_SUPERSEDED);
        SettlementAuditRecord auditRecord = new SettlementAuditRecord();
        auditRecord.setBetId(bet.getId());
        auditRecord.setTrackedEventId(resolveTrackedEventId(bet, null));
        auditRecord.setDecision(DECISION_MANUAL_REVIEW);
        auditRecord.setReason(reason);
        auditRecord.setConfidence(null);
        auditRecord.setEvidenceId(null);
        auditRecord.setDecisionFingerprint(decisionFingerprint);
        auditRecord.setReviewStatus(REVIEW_OPEN);
        auditRecord.setDecidedAt(firstNonNull(bet.getSettledAt(), bet.getLastObservedAt(), bet.getPlacedAt(), LocalDateTime.now()));
        auditRecord.setPayloadJson(serialize(Map.of(
                "betId", bet.getId(),
                "trackedEventId", resolveTrackedEventId(bet, null),
                "reason", reason
        )));
        SettlementAuditRecord saved = settlementAuditRecordRepository.save(auditRecord);
        return new AuditWriteResult(true, saved.getId(), null, null, decisionFingerprint);
    }

    /**
     * Finalizes whether the persisted evidence may become a model label. This
     * is deliberately called only after the bet has received its terminal
     * outcome, because identity validity and binary status are part of the
     * eligibility contract.
     */
    @Transactional
    public void recordLearningEligibility(Long evidenceId, PaperTradeBet settledBet) {
        if (evidenceId == null || settledBet == null) {
            return;
        }
        settlementEvidenceRecordRepository.findById(evidenceId).ifPresent(record -> {
            LearningSampleQuality.Assessment assessment = LearningSampleQuality.assess(settledBet);
            record.setLearningEligible(assessment.learningEligible());
            record.setLearningExclusionReason(assessment.exclusionReason());
            settlementEvidenceRecordRepository.save(record);
        });
    }

    private SettlementEvidenceRecord findOrCreateEvidenceRecord(SettlementEvidence evidence,
                                                                String evidenceFingerprint) {
        Optional<SettlementEvidenceRecord> fingerprintMatch =
                settlementEvidenceRecordRepository.findByEvidenceFingerprint(evidenceFingerprint);
        if (fingerprintMatch.isPresent()) {
            return backfillScoreEvidence(fingerprintMatch.get(), evidence);
        }
        LocalDateTime bundleAsOf = toLocalDateTime(evidence.bundleAsOf());
        Optional<SettlementEvidenceRecord> timestampMatch =
                settlementEvidenceRecordRepository.findFirstByBetIdAndBundleAsOf(evidence.betId(), bundleAsOf);
        if (timestampMatch.isPresent()) {
            SettlementEvidenceRecord existing = timestampMatch.get();
            if (existing.getEvidenceFingerprint() == null || existing.getEvidenceFingerprint().isBlank()) {
                existing.setEvidenceFingerprint(evidenceFingerprint);
            }
            return backfillScoreEvidence(existing, evidence);
        }
        return createEvidenceRecord(evidence, bundleAsOf, evidenceFingerprint);
    }

    private SettlementEvidenceRecord backfillScoreEvidence(SettlementEvidenceRecord record,
                                                            SettlementEvidence evidence) {
        if (record.getScoreEvidenceQuality() != null
                && record.getScoreEvidenceFinality() != null
                && record.getScoreEvidenceConfidence() != null
                && record.getScoreObservationCount() != null
                && record.getScoreSourceCount() != null
                && record.getScoreCompletionSignalCount() != null) {
            return record;
        }
        ScoreEvidenceAssessment scoreEvidence = ScoreEvidenceAnalyzer.assess(evidence);
        record.setScoreEvidenceQuality(scoreEvidence.quality().name());
        record.setScoreEvidenceFinality(scoreEvidence.finality().name());
        record.setScoreEvidenceConfidence(scoreEvidence.confidence());
        record.setScoreObservationCount(scoreEvidence.observationCount());
        record.setScoreSourceCount(scoreEvidence.distinctSourceCount());
        record.setScoreCompletionSignalCount(scoreEvidence.completionSignalCount());
        record.setScoreInferredWinnerId(scoreEvidence.inferredWinnerPlayerId());
        return settlementEvidenceRecordRepository.save(record);
    }

    private SettlementEvidenceRecord createEvidenceRecord(SettlementEvidence evidence,
                                                          LocalDateTime bundleAsOf,
                                                          String evidenceFingerprint) {
        SettlementEvidenceRecord record = new SettlementEvidenceRecord();
        record.setBetId(evidence.betId());
        record.setTrackedEventId(evidence.trackedEventId().value());
        record.setBundleAsOf(bundleAsOf);
        record.setCoverageState(evidence.coverageState().name());
        record.setAmbiguityScore(evidence.ambiguityScore());
        record.setConfidence(evidence.confidence());
        record.setPayloadJson(serialize(evidence));
        record.setEvidenceFingerprint(evidenceFingerprint);
        ScoreEvidenceAssessment scoreEvidence = ScoreEvidenceAnalyzer.assess(evidence);
        record.setScoreEvidenceQuality(scoreEvidence.quality().name());
        record.setScoreEvidenceFinality(scoreEvidence.finality().name());
        record.setScoreEvidenceConfidence(scoreEvidence.confidence());
        record.setScoreObservationCount(scoreEvidence.observationCount());
        record.setScoreSourceCount(scoreEvidence.distinctSourceCount());
        record.setScoreCompletionSignalCount(scoreEvidence.completionSignalCount());
        record.setScoreInferredWinnerId(scoreEvidence.inferredWinnerPlayerId());
        record.setLearningEligible(false);
        record.setLearningExclusionReason("PENDING_SETTLEMENT_DECISION");
        SettlementEvidenceRecord saved = settlementEvidenceRecordRepository.save(record);

        if (!evidence.contradictions().isEmpty()) {
            settlementContradictionRecordRepository.saveAll(evidence.contradictions().stream()
                    .map(contradiction -> toContradictionRecord(saved.getId(), evidence.betId(), contradiction))
                    .toList());
        }
        return saved;
    }

    private SettlementContradictionRecord toContradictionRecord(Long evidenceId,
                                                                long betId,
                                                                Contradiction contradiction) {
        SettlementContradictionRecord record = new SettlementContradictionRecord();
        record.setEvidenceId(evidenceId);
        record.setBetId(betId);
        record.setObservedAt(toLocalDateTime(latestObservedAt(contradiction)));
        record.setKind(contradiction.kind().name());
        record.setSeverity(contradiction.severity());
        record.setResolved(false);
        record.setPayloadJson(serialize(Map.of(
                "kind", contradiction.kind().name(),
                "severity", contradiction.severity(),
                "left", contradiction.a(),
                "right", contradiction.b()
        )));
        return record;
    }

    private SettlementAuditRecord recordAudit(PaperTradeBet bet,
                                              SettlementEvidence evidence,
                                              Decision decision,
                                              Long evidenceId,
                                              String payloadJson,
                                              String evidenceRefs,
                                              String decisionFingerprint) {
        String decisionType = decisionType(decision);
        if (DECISION_MANUAL_REVIEW.equals(decisionType)) {
            supersedeOpenReviews(bet.getId(), REVIEW_SUPERSEDED);
        } else if (DECISION_SETTLE.equals(decisionType) || DECISION_VOID.equals(decisionType)) {
            supersedeOpenReviews(bet.getId(), REVIEW_RESOLVED);
        }
        SettlementAuditRecord auditRecord = new SettlementAuditRecord();
        auditRecord.setBetId(bet.getId());
        auditRecord.setTrackedEventId(resolveTrackedEventId(bet, evidence));
        auditRecord.setDecision(decisionType);
        auditRecord.setReason(decision.reason().name());
        auditRecord.setConfidence(decisionConfidence(decision));
        auditRecord.setEvidenceId(evidenceId);
        auditRecord.setDecisionFingerprint(decisionFingerprint);
        if (DECISION_MANUAL_REVIEW.equals(decisionType)) {
            auditRecord.setReviewStatus(REVIEW_OPEN);
        }
        auditRecord.setDecidedAt(toLocalDateTime(evidence.bundleAsOf()));
        auditRecord.setPayloadJson(payloadJson);
        auditRecord.setEvidenceRefs(evidenceRefs);
        return settlementAuditRecordRepository.save(auditRecord);
    }

    private void supersedeOpenReviews(Long betId, String newStatus) {
        if (betId == null) {
            return;
        }
        List<SettlementAuditRecord> openReviews = settlementAuditRecordRepository
                .findByBetIdAndDecisionAndReviewStatusOrderByDecidedAtDescIdDesc(
                        betId,
                        DECISION_MANUAL_REVIEW,
                        REVIEW_OPEN
                );
        if (openReviews == null || openReviews.isEmpty()) {
            return;
        }
        openReviews.forEach(review -> review.setReviewStatus(newStatus));
        settlementAuditRecordRepository.saveAll(openReviews);
    }

    String collectCvAuditEvidenceRefs(SettlementEvidence evidence) {
        if (cvAuditEvidenceStore.isEmpty() || evidence == null || evidence.contradictions().isEmpty()) {
            return null;
        }
        CvAuditEvidenceStore store = cvAuditEvidenceStore.get();
        if (!store.isEnabled()) {
            return null;
        }
        String matchId = evidence.trackedEventId() == null ? null : evidence.trackedEventId().value();
        if (matchId == null || matchId.isBlank()) {
            return null;
        }
        List<String> refs = store.uploadForContradiction(matchId, evidence.bundleAsOf());
        return store.serializeRefs(refs).orElse(null);
    }

    private String serializeDecisionPayload(PaperTradeBet bet,
                                            SettlementEvidence evidence,
                                            Decision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("betId", bet.getId());
        payload.put("trackedEventId", resolveTrackedEventId(bet, evidence));
        payload.put("decisionType", decisionType(decision));
        payload.put("reason", decision.reason().name());
        payload.put("bundleAsOf", evidence.bundleAsOf());
        payload.put("coverageState", evidence.coverageState().name());
        payload.put("ambiguityScore", evidence.ambiguityScore());
        payload.put("contradictionCount", evidence.contradictions().size());
        payload.put("evidenceConfidence", evidence.confidence());
        payload.put("scoreEvidence", ScoreEvidenceAnalyzer.assess(evidence));
        if (decision instanceof Settle settle) {
            payload.put("winnerPlayerId", settle.winnerPlayerId());
            payload.put("decisionConfidence", settle.confidence());
        } else if (decision instanceof Escalate escalate) {
            payload.put("nextSources", escalate.nextSources());
        } else if (decision instanceof HoldOpen holdOpen) {
            payload.put("note", holdOpen.note());
        } else if (decision instanceof ManualReview manualReview) {
            payload.put("manualContradictions", manualReview.contradictions().size());
        } else if (decision instanceof VoidDecision) {
            payload.put("decisionConfidence", null);
        }
        return serialize(payload);
    }

    private String decisionType(Decision decision) {
        if (decision instanceof Settle) {
            return DECISION_SETTLE;
        }
        if (decision instanceof HoldOpen) {
            return DECISION_HOLD_OPEN;
        }
        if (decision instanceof Escalate) {
            return DECISION_ESCALATE;
        }
        if (decision instanceof VoidDecision) {
            return DECISION_VOID;
        }
        if (decision instanceof ManualReview) {
            return DECISION_MANUAL_REVIEW;
        }
        return "UNKNOWN";
    }

    private Double decisionConfidence(Decision decision) {
        if (decision instanceof Settle settle) {
            return settle.confidence();
        }
        return null;
    }

    private Instant latestObservedAt(Contradiction contradiction) {
        Instant left = contradiction.a().observedAt();
        Instant right = contradiction.b().observedAt();
        return left.isAfter(right) ? left : right;
    }

    private String resolveTrackedEventId(PaperTradeBet bet, SettlementEvidence evidence) {
        if (evidence != null && evidence.trackedEventId() != null) {
            return evidence.trackedEventId().value();
        }
        if (bet.getEventKey() != null && !bet.getEventKey().isBlank()) {
            return bet.getEventKey();
        }
        if (bet.getDedupeKey() != null && !bet.getDedupeKey().isBlank()) {
            return bet.getDedupeKey();
        }
        return "bet-" + bet.getId();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private LocalDateTime firstNonNull(LocalDateTime first,
                                       LocalDateTime second,
                                       LocalDateTime third,
                                       LocalDateTime fallback) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        if (third != null) {
            return third;
        }
        return fallback;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + sanitize(ex.getMessage()) + "\"}";
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("\\", "\\\\").replace("\"", "'");
    }

    public record AuditWriteResult(boolean recorded,
                                   Long auditId,
                                   Long evidenceId,
                                   String evidenceFingerprint,
                                   String decisionFingerprint) {
    }
}
