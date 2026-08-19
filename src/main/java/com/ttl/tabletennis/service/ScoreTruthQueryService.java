package com.ttl.tabletennis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.ttl.tabletennis.domain.SettlementAuditRecord;
import com.ttl.tabletennis.domain.SettlementContradictionRecord;
import com.ttl.tabletennis.domain.SettlementEvidenceRecord;
import com.ttl.tabletennis.dto.ScoreTruthContradictionDto;
import com.ttl.tabletennis.dto.ScoreTruthDecisionDto;
import com.ttl.tabletennis.dto.ScoreTruthDecisionsDto;
import com.ttl.tabletennis.dto.ScoreTruthEvidenceDto;
import com.ttl.tabletennis.dto.ScoreTruthEvidenceSnapshotDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.SettlementAuditRecordRepository;
import com.ttl.tabletennis.repository.SettlementContradictionRecordRepository;
import com.ttl.tabletennis.repository.SettlementEvidenceRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class ScoreTruthQueryService {

    private static final int DEFAULT_DECISION_LIMIT = 25;
    private static final int DEFAULT_CONTRADICTION_LIMIT = 25;

    private final SettlementEvidenceRecordRepository settlementEvidenceRecordRepository;
    private final SettlementContradictionRecordRepository settlementContradictionRecordRepository;
    private final SettlementAuditRecordRepository settlementAuditRecordRepository;
    private final ObjectMapper objectMapper;

    public ScoreTruthQueryService(SettlementEvidenceRecordRepository settlementEvidenceRecordRepository,
                                  SettlementContradictionRecordRepository settlementContradictionRecordRepository,
                                  SettlementAuditRecordRepository settlementAuditRecordRepository,
                                  ObjectMapper objectMapper) {
        this.settlementEvidenceRecordRepository = settlementEvidenceRecordRepository;
        this.settlementContradictionRecordRepository = settlementContradictionRecordRepository;
        this.settlementAuditRecordRepository = settlementAuditRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ScoreTruthEvidenceDto evidence(String matchId) {
        if (matchId == null || matchId.isBlank()) {
            throw new IllegalArgumentException("matchId must not be blank");
        }
        String trimmedMatchId = matchId.trim();

        SettlementEvidenceRecord evidenceRecord = settlementEvidenceRecordRepository
                .findByTrackedEventIdOrderByBundleAsOfDesc(trimmedMatchId, PageRequest.of(0, 1)).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No score-truth evidence found for matchId " + trimmedMatchId));

        List<ScoreTruthDecisionDto> decisions = settlementAuditRecordRepository
                .findByTrackedEventIdOrderByDecidedAtDesc(trimmedMatchId, PageRequest.of(0, DEFAULT_DECISION_LIMIT)).stream()
                .map(this::toDecisionDto)
                .toList();

        return evidenceDto(trimmedMatchId, evidenceRecord, decisions);
    }

    @Transactional(readOnly = true)
    public ScoreTruthEvidenceDto evidenceByBetId(long betId) {
        if (betId <= 0L) {
            throw new IllegalArgumentException("betId must be positive");
        }
        SettlementEvidenceRecord evidenceRecord = settlementEvidenceRecordRepository
                .findTopByBetIdOrderByBundleAsOfDesc(betId)
                .orElseThrow(() -> new ResourceNotFoundException("No score-truth evidence found for betId " + betId));

        List<ScoreTruthDecisionDto> decisions = settlementAuditRecordRepository
                .findByBetIdOrderByDecidedAtDesc(betId, PageRequest.of(0, DEFAULT_DECISION_LIMIT)).stream()
                .map(this::toDecisionDto)
                .toList();

        return evidenceDto(String.valueOf(betId), evidenceRecord, decisions);
    }

    private ScoreTruthEvidenceDto evidenceDto(String matchId,
                                              SettlementEvidenceRecord evidenceRecord,
                                              List<ScoreTruthDecisionDto> decisions) {
        List<ScoreTruthContradictionDto> contradictions = settlementContradictionRecordRepository
                .findByEvidenceIdOrderByObservedAtDesc(evidenceRecord.getId(), PageRequest.of(0, DEFAULT_CONTRADICTION_LIMIT)).stream()
                .map(this::toContradictionDto)
                .toList();

        return new ScoreTruthEvidenceDto(
                Instant.now(),
                matchId,
                toEvidenceSnapshotDto(evidenceRecord),
                contradictions,
                decisions
        );
    }

    @Transactional(readOnly = true)
    public ScoreTruthDecisionsDto decisions(Instant from, int limit) {
        int effectiveLimit = limit <= 0 ? DEFAULT_DECISION_LIMIT : Math.min(limit, 200);
        List<SettlementAuditRecord> records = from == null
                ? settlementAuditRecordRepository.findAllByOrderByDecidedAtDesc(PageRequest.of(0, effectiveLimit))
                : settlementAuditRecordRepository.findByDecidedAtGreaterThanEqualOrderByDecidedAtDesc(
                LocalDateTime.ofInstant(from, ZoneId.systemDefault()),
                PageRequest.of(0, effectiveLimit)
        );

        return new ScoreTruthDecisionsDto(
                Instant.now(),
                from,
                records.stream().map(this::toDecisionDto).toList()
        );
    }

    private ScoreTruthEvidenceSnapshotDto toEvidenceSnapshotDto(SettlementEvidenceRecord record) {
        return new ScoreTruthEvidenceSnapshotDto(
                record.getId(),
                record.getBetId(),
                record.getTrackedEventId(),
                toInstant(record.getBundleAsOf()),
                record.getCoverageState(),
                record.getAmbiguityScore(),
                record.getConfidence(),
                record.isLearningEligible(),
                record.getLearningExclusionReason(),
                parseJson(record.getPayloadJson())
        );
    }

    private ScoreTruthContradictionDto toContradictionDto(SettlementContradictionRecord record) {
        return new ScoreTruthContradictionDto(
                record.getId(),
                record.getEvidenceId(),
                record.getBetId(),
                toInstant(record.getObservedAt()),
                record.getKind(),
                record.getSeverity(),
                record.isResolved(),
                record.getResolutionNote(),
                parseJson(record.getPayloadJson())
        );
    }

    private ScoreTruthDecisionDto toDecisionDto(SettlementAuditRecord record) {
        return new ScoreTruthDecisionDto(
                record.getId(),
                record.getBetId(),
                record.getTrackedEventId(),
                record.getDecision(),
                record.getReason(),
                record.getConfidence(),
                record.getEvidenceId(),
                toInstant(record.getDecidedAt()),
                parseJson(record.getPayloadJson())
        );
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private JsonNode parseJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException ex) {
            return NullNode.getInstance();
        }
    }
}
