package com.ttl.tabletennis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.ttl.tabletennis.domain.SettlementAuditRecord;
import com.ttl.tabletennis.dto.ScoreTruthReviewActionDto;
import com.ttl.tabletennis.dto.ScoreTruthReviewActionRequest;
import com.ttl.tabletennis.dto.ScoreTruthReviewItemDto;
import com.ttl.tabletennis.dto.ScoreTruthReviewQueueDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.SettlementAuditRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ScoreTruthReviewService {

    static final String DECISION_MANUAL_REVIEW = "MANUAL_REVIEW";
    static final String DECISION_ACCEPTED = "MANUAL_REVIEW_ACCEPTED";
    static final String DECISION_REJECTED = "MANUAL_REVIEW_REJECTED";
    static final String DECISION_COMMENT = "MANUAL_REVIEW_COMMENT";

    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 100;
    private static final Set<String> ACTION_DECISIONS = Set.of(
            DECISION_ACCEPTED,
            DECISION_REJECTED,
            DECISION_COMMENT
    );

    private final SettlementAuditRecordRepository settlementAuditRecordRepository;
    private final ObjectMapper objectMapper;

    public ScoreTruthReviewService(SettlementAuditRecordRepository settlementAuditRecordRepository,
                                   ObjectMapper objectMapper) {
        this.settlementAuditRecordRepository = settlementAuditRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ScoreTruthReviewQueueDto queue(Integer page, Integer size) {
        int normalizedPage = page == null || page < 0 ? 0 : page;
        int normalizedSize = normalizeSize(size);
        Page<SettlementAuditRecord> recordPage = settlementAuditRecordRepository
                .findByDecisionOrderByDecidedAtDescIdDesc(
                        DECISION_MANUAL_REVIEW,
                        PageRequest.of(normalizedPage, normalizedSize)
                );

        List<ScoreTruthReviewItemDto> items = recordPage.getContent().stream()
                .map(this::toReviewItem)
                .toList();

        return new ScoreTruthReviewQueueDto(
                Instant.now(),
                normalizedPage,
                normalizedSize,
                recordPage.getTotalElements(),
                recordPage.getTotalPages(),
                recordPage.hasPrevious(),
                recordPage.hasNext(),
                items
        );
    }

    @Transactional(readOnly = true)
    public long unresolvedQueueDepth() {
        long unresolved = 0L;
        int page = 0;
        Page<SettlementAuditRecord> recordPage;
        do {
            recordPage = settlementAuditRecordRepository
                    .findByDecisionOrderByDecidedAtDescIdDesc(
                            DECISION_MANUAL_REVIEW,
                            PageRequest.of(page, MAX_SIZE)
                    );
            for (SettlementAuditRecord record : recordPage.getContent()) {
                if (!resolved(latestAction(record))) {
                    unresolved++;
                }
            }
            page++;
        } while (recordPage.hasNext());
        return unresolved;
    }

    @Transactional
    public ScoreTruthReviewActionDto recordAction(long decisionId, ScoreTruthReviewActionRequest request) {
        SettlementAuditRecord source = settlementAuditRecordRepository.findById(decisionId)
                .orElseThrow(() -> new ResourceNotFoundException("Manual review decision not found: " + decisionId));
        if (!DECISION_MANUAL_REVIEW.equals(source.getDecision())) {
            throw new ResourceNotFoundException("Manual review decision not found: " + decisionId);
        }

        String action = normalizeAction(request == null ? null : request.action());
        String comment = trimToNull(request == null ? null : request.comment());
        if (("REJECT".equals(action) || "COMMENT".equals(action)) && comment == null) {
            throw new IllegalArgumentException("comment is required for " + action.toLowerCase(Locale.ROOT));
        }
        String reviewer = trimToNull(request == null ? null : request.reviewer());
        if (reviewer == null) {
            reviewer = "operator";
        }

        LocalDateTime reviewedAt = LocalDateTime.now();
        SettlementAuditRecord actionRecord = new SettlementAuditRecord();
        actionRecord.setBetId(source.getBetId());
        actionRecord.setTrackedEventId(source.getTrackedEventId());
        actionRecord.setDecision(toDecision(action));
        actionRecord.setReason(toReason(action));
        actionRecord.setConfidence(source.getConfidence());
        actionRecord.setEvidenceId(source.getEvidenceId());
        actionRecord.setDecidedAt(reviewedAt);
        actionRecord.setPayloadJson(serializeActionPayload(source, action, reviewer, comment, reviewedAt));

        SettlementAuditRecord saved = settlementAuditRecordRepository.save(actionRecord);
        return new ScoreTruthReviewActionDto(
                saved.getId(),
                decisionId,
                action,
                reviewer,
                comment,
                toInstant(saved.getDecidedAt())
        );
    }

    private ScoreTruthReviewItemDto toReviewItem(SettlementAuditRecord record) {
        ReviewActionSnapshot action = latestAction(record);
        return new ScoreTruthReviewItemDto(
                record.getId(),
                record.getBetId(),
                record.getTrackedEventId(),
                record.getReason(),
                record.getConfidence(),
                record.getEvidenceId(),
                toInstant(record.getDecidedAt()),
                parseJson(record.getPayloadJson()),
                action.status(),
                action.reviewer(),
                action.comment(),
                action.reviewedAt(),
                action.actionId()
        );
    }

    private ReviewActionSnapshot latestAction(SettlementAuditRecord record) {
        if (record.getBetId() == null || record.getId() == null) {
            return ReviewActionSnapshot.open();
        }

        return settlementAuditRecordRepository
                .findByBetIdAndDecisionInOrderByDecidedAtDescIdDesc(
                        record.getBetId(),
                        ACTION_DECISIONS,
                        PageRequest.of(0, 25)
                ).stream()
                .filter(candidate -> actionReferences(candidate, record.getId()))
                .findFirst()
                .map(this::toActionSnapshot)
                .orElseGet(ReviewActionSnapshot::open);
    }

    private boolean actionReferences(SettlementAuditRecord candidate, Long decisionId) {
        JsonNode payload = parseJson(candidate.getPayloadJson());
        JsonNode reviewDecisionId = payload.path("reviewDecisionId");
        return reviewDecisionId.canConvertToLong() && reviewDecisionId.asLong() == decisionId;
    }

    private ReviewActionSnapshot toActionSnapshot(SettlementAuditRecord record) {
        JsonNode payload = parseJson(record.getPayloadJson());
        String action = payload.path("action").asText("");
        String status = switch (action) {
            case "ACCEPT" -> "ACCEPTED";
            case "REJECT" -> "REJECTED";
            case "COMMENT" -> "COMMENTED";
            default -> statusForDecision(record.getDecision());
        };
        return new ReviewActionSnapshot(
                status,
                trimToNull(payload.path("reviewer").asText(null)),
                trimToNull(payload.path("comment").asText(null)),
                toInstant(record.getDecidedAt()),
                record.getId()
        );
    }

    private String statusForDecision(String decision) {
        return switch (decision) {
            case DECISION_ACCEPTED -> "ACCEPTED";
            case DECISION_REJECTED -> "REJECTED";
            case DECISION_COMMENT -> "COMMENTED";
            default -> "OPEN";
        };
    }

    private boolean resolved(ReviewActionSnapshot action) {
        return action != null
                && ("ACCEPTED".equals(action.status()) || "REJECTED".equals(action.status()));
    }

    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        return switch (action.trim().toUpperCase(Locale.ROOT)) {
            case "ACCEPT", "ACCEPTED" -> "ACCEPT";
            case "REJECT", "REJECTED" -> "REJECT";
            case "COMMENT", "COMMENTED" -> "COMMENT";
            default -> throw new IllegalArgumentException("unsupported review action: " + action);
        };
    }

    private String toDecision(String action) {
        return switch (action) {
            case "ACCEPT" -> DECISION_ACCEPTED;
            case "REJECT" -> DECISION_REJECTED;
            case "COMMENT" -> DECISION_COMMENT;
            default -> throw new IllegalArgumentException("unsupported review action: " + action);
        };
    }

    private String toReason(String action) {
        return switch (action) {
            case "ACCEPT" -> "OPERATOR_ACCEPTED";
            case "REJECT" -> "OPERATOR_REJECTED";
            case "COMMENT" -> "OPERATOR_COMMENT";
            default -> "OPERATOR_REVIEW";
        };
    }

    private String serializeActionPayload(SettlementAuditRecord source,
                                          String action,
                                          String reviewer,
                                          String comment,
                                          LocalDateTime reviewedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewDecisionId", source.getId());
        payload.put("action", action);
        payload.put("reviewer", reviewer);
        payload.put("comment", comment);
        payload.put("sourceDecision", source.getDecision());
        payload.put("sourceReason", source.getReason());
        payload.put("sourceDecidedAt", toInstant(source.getDecidedAt()) == null ? null : toInstant(source.getDecidedAt()).toString());
        payload.put("reviewedAt", toInstant(reviewedAt) == null ? null : toInstant(reviewedAt).toString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + sanitize(ex.getMessage()) + "\"}";
        }
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

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("\\", "\\\\").replace("\"", "'");
    }

    private record ReviewActionSnapshot(String status,
                                        String reviewer,
                                        String comment,
                                        Instant reviewedAt,
                                        Long actionId) {
        static ReviewActionSnapshot open() {
            return new ReviewActionSnapshot("OPEN", null, null, null, null);
        }
    }
}
