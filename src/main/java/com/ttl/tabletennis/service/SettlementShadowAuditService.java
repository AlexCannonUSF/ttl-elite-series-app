package com.ttl.tabletennis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.ttl.tabletennis.settlement.VoidDecision;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SettlementShadowAuditService {

    static final String DECISION_SETTLE = "SETTLE";
    static final String DECISION_HOLD_OPEN = "HOLD_OPEN";
    static final String DECISION_ESCALATE = "ESCALATE";
    static final String DECISION_VOID = "VOID";
    static final String DECISION_MANUAL_REVIEW = "MANUAL_REVIEW";

    private final SettlementEvidenceRecordRepository settlementEvidenceRecordRepository;
    private final SettlementContradictionRecordRepository settlementContradictionRecordRepository;
    private final SettlementAuditRecordRepository settlementAuditRecordRepository;
    private final ObjectMapper objectMapper;

    public SettlementShadowAuditService(SettlementEvidenceRecordRepository settlementEvidenceRecordRepository,
                                        SettlementContradictionRecordRepository settlementContradictionRecordRepository,
                                        SettlementAuditRecordRepository settlementAuditRecordRepository,
                                        ObjectMapper objectMapper) {
        this.settlementEvidenceRecordRepository = settlementEvidenceRecordRepository;
        this.settlementContradictionRecordRepository = settlementContradictionRecordRepository;
        this.settlementAuditRecordRepository = settlementAuditRecordRepository;
        this.objectMapper = objectMapper;
    }

    public void recordAttempt(PaperTradeBet bet, SettlementEvidence evidence, Decision decision) {
        SettlementEvidenceRecord evidenceRecord = findOrCreateEvidenceRecord(evidence);
        recordAudit(bet, evidence, decision, evidenceRecord.getId(), serializeDecisionPayload(bet, evidence, decision));
    }

    public void recordNoEvidenceAttempt(PaperTradeBet bet, String reason) {
        SettlementAuditRecord auditRecord = new SettlementAuditRecord();
        auditRecord.setBetId(bet.getId());
        auditRecord.setTrackedEventId(resolveTrackedEventId(bet, null));
        auditRecord.setDecision(DECISION_MANUAL_REVIEW);
        auditRecord.setReason(reason);
        auditRecord.setConfidence(null);
        auditRecord.setEvidenceId(null);
        auditRecord.setDecidedAt(firstNonNull(bet.getSettledAt(), bet.getLastObservedAt(), bet.getPlacedAt(), LocalDateTime.now()));
        auditRecord.setPayloadJson(serialize(Map.of(
                "betId", bet.getId(),
                "trackedEventId", resolveTrackedEventId(bet, null),
                "reason", reason
        )));
        settlementAuditRecordRepository.save(auditRecord);
    }

    private SettlementEvidenceRecord findOrCreateEvidenceRecord(SettlementEvidence evidence) {
        LocalDateTime bundleAsOf = toLocalDateTime(evidence.bundleAsOf());
        return settlementEvidenceRecordRepository.findFirstByBetIdAndBundleAsOf(evidence.betId(), bundleAsOf)
                .orElseGet(() -> createEvidenceRecord(evidence, bundleAsOf));
    }

    private SettlementEvidenceRecord createEvidenceRecord(SettlementEvidence evidence, LocalDateTime bundleAsOf) {
        SettlementEvidenceRecord record = new SettlementEvidenceRecord();
        record.setBetId(evidence.betId());
        record.setTrackedEventId(evidence.trackedEventId().value());
        record.setBundleAsOf(bundleAsOf);
        record.setCoverageState(evidence.coverageState().name());
        record.setAmbiguityScore(evidence.ambiguityScore());
        record.setConfidence(evidence.confidence());
        record.setPayloadJson(serialize(evidence));
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

    private void recordAudit(PaperTradeBet bet,
                             SettlementEvidence evidence,
                             Decision decision,
                             Long evidenceId,
                             String payloadJson) {
        SettlementAuditRecord auditRecord = new SettlementAuditRecord();
        auditRecord.setBetId(bet.getId());
        auditRecord.setTrackedEventId(resolveTrackedEventId(bet, evidence));
        auditRecord.setDecision(decisionType(decision));
        auditRecord.setReason(decision.reason().name());
        auditRecord.setConfidence(decisionConfidence(decision));
        auditRecord.setEvidenceId(evidenceId);
        auditRecord.setDecidedAt(toLocalDateTime(evidence.bundleAsOf()));
        auditRecord.setPayloadJson(payloadJson);
        settlementAuditRecordRepository.save(auditRecord);
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
}
