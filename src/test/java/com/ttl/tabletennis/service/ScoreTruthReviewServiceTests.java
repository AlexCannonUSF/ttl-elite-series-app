package com.ttl.tabletennis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.SettlementAuditRecord;
import com.ttl.tabletennis.dto.ScoreTruthReviewActionDto;
import com.ttl.tabletennis.dto.ScoreTruthReviewActionRequest;
import com.ttl.tabletennis.dto.ScoreTruthReviewQueueDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.SettlementAuditRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreTruthReviewServiceTests {

    @Test
    void queuePaginatesManualReviewDecisionsAndMarksLatestAction() {
        SettlementAuditRecordRepository repository = mock(SettlementAuditRecordRepository.class);
        SettlementAuditRecord manualReview = manualReviewRecord(701L, 41L);
        SettlementAuditRecord accepted = actionRecord(801L, 41L, ScoreTruthReviewService.DECISION_ACCEPTED, "ACCEPT", "ops-lead", "confirmed", 701L);

        when(repository.findByDecisionOrderByDecidedAtDescIdDesc(
                eq(ScoreTruthReviewService.DECISION_MANUAL_REVIEW),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(manualReview), PageRequest.of(1, 10), 21));
        when(repository.findByBetIdAndDecisionInOrderByDecidedAtDescIdDesc(eq(41L), any(), any(Pageable.class)))
                .thenReturn(List.of(accepted));

        ScoreTruthReviewService service = new ScoreTruthReviewService(repository, new ObjectMapper());

        ScoreTruthReviewQueueDto queue = service.queue(1, 10);

        assertEquals(1, queue.page());
        assertEquals(10, queue.size());
        assertEquals(21L, queue.totalItems());
        assertEquals(3, queue.totalPages());
        assertEquals(1, queue.items().size());
        assertEquals(701L, queue.items().get(0).decisionId());
        assertEquals("ACCEPTED", queue.items().get(0).reviewStatus());
        assertEquals("ops-lead", queue.items().get(0).reviewer());
        assertEquals("confirmed", queue.items().get(0).reviewComment());
        assertEquals(801L, queue.items().get(0).reviewActionId());
        verify(repository).findByDecisionOrderByDecidedAtDescIdDesc(
                eq(ScoreTruthReviewService.DECISION_MANUAL_REVIEW),
                any(Pageable.class)
        );
    }

    @Test
    void queueMarksManualReviewOpenWhenNoActionReferencesIt() {
        SettlementAuditRecordRepository repository = mock(SettlementAuditRecordRepository.class);
        SettlementAuditRecord manualReview = manualReviewRecord(702L, 42L);
        SettlementAuditRecord unrelatedAction = actionRecord(802L, 42L, ScoreTruthReviewService.DECISION_COMMENT, "COMMENT", "ops", "wrong row", 999L);

        when(repository.findByDecisionOrderByDecidedAtDescIdDesc(any(String.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(manualReview)));
        when(repository.findByBetIdAndDecisionInOrderByDecidedAtDescIdDesc(eq(42L), any(), any(Pageable.class)))
                .thenReturn(List.of(unrelatedAction));

        ScoreTruthReviewService service = new ScoreTruthReviewService(repository, new ObjectMapper());

        ScoreTruthReviewQueueDto queue = service.queue(null, null);

        assertEquals(25, queue.size());
        assertEquals("OPEN", queue.items().get(0).reviewStatus());
        assertEquals(null, queue.items().get(0).reviewActionId());
    }

    @Test
    void unresolvedQueueDepthCountsOpenAndCommentedReviewsOnly() {
        SettlementAuditRecordRepository repository = mock(SettlementAuditRecordRepository.class);
        SettlementAuditRecord open = manualReviewRecord(701L, 41L);
        SettlementAuditRecord acceptedSource = manualReviewRecord(702L, 42L);
        SettlementAuditRecord commentedSource = manualReviewRecord(703L, 43L);
        SettlementAuditRecord accepted = actionRecord(801L, 42L, ScoreTruthReviewService.DECISION_ACCEPTED, "ACCEPT", "ops", "confirmed", 702L);
        SettlementAuditRecord comment = actionRecord(802L, 43L, ScoreTruthReviewService.DECISION_COMMENT, "COMMENT", "ops", "needs another look", 703L);

        when(repository.findByDecisionOrderByDecidedAtDescIdDesc(
                eq(ScoreTruthReviewService.DECISION_MANUAL_REVIEW),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(open, acceptedSource, commentedSource)));
        when(repository.findByBetIdAndDecisionInOrderByDecidedAtDescIdDesc(eq(41L), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(repository.findByBetIdAndDecisionInOrderByDecidedAtDescIdDesc(eq(42L), any(), any(Pageable.class)))
                .thenReturn(List.of(accepted));
        when(repository.findByBetIdAndDecisionInOrderByDecidedAtDescIdDesc(eq(43L), any(), any(Pageable.class)))
                .thenReturn(List.of(comment));

        ScoreTruthReviewService service = new ScoreTruthReviewService(repository, new ObjectMapper());

        assertEquals(2L, service.unresolvedQueueDepth());
    }

    @Test
    void recordActionAppendsOperatorAuditRecord() throws Exception {
        SettlementAuditRecordRepository repository = mock(SettlementAuditRecordRepository.class);
        SettlementAuditRecord manualReview = manualReviewRecord(701L, 41L);

        when(repository.findById(701L)).thenReturn(Optional.of(manualReview));
        when(repository.save(any(SettlementAuditRecord.class))).thenAnswer(invocation -> {
            SettlementAuditRecord saved = invocation.getArgument(0);
            setId(saved, SettlementAuditRecord.class, 901L);
            return saved;
        });

        ScoreTruthReviewService service = new ScoreTruthReviewService(repository, new ObjectMapper());

        ScoreTruthReviewActionDto response = service.recordAction(
                701L,
                new ScoreTruthReviewActionRequest("reject", "scoreboard contradicts source", "ops-reviewer")
        );

        ArgumentCaptor<SettlementAuditRecord> captor = ArgumentCaptor.forClass(SettlementAuditRecord.class);
        verify(repository).save(captor.capture());
        SettlementAuditRecord saved = captor.getValue();

        assertEquals(901L, response.id());
        assertEquals(701L, response.decisionId());
        assertEquals("REJECT", response.action());
        assertEquals("ops-reviewer", response.reviewer());
        assertEquals(41L, saved.getBetId());
        assertEquals(ScoreTruthReviewService.DECISION_REJECTED, saved.getDecision());
        assertEquals("OPERATOR_REJECTED", saved.getReason());
        assertEquals(501L, saved.getEvidenceId());
        assertNotNull(saved.getDecidedAt());
        assertEquals(701L, new ObjectMapper().readTree(saved.getPayloadJson()).path("reviewDecisionId").asLong());
        assertEquals("scoreboard contradicts source", new ObjectMapper().readTree(saved.getPayloadJson()).path("comment").asText());
    }

    @Test
    void recordActionRejectsNonManualReviewDecision() {
        SettlementAuditRecordRepository repository = mock(SettlementAuditRecordRepository.class);
        SettlementAuditRecord settled = manualReviewRecord(701L, 41L);
        settled.setDecision("SETTLE");

        when(repository.findById(701L)).thenReturn(Optional.of(settled));

        ScoreTruthReviewService service = new ScoreTruthReviewService(repository, new ObjectMapper());

        assertThrows(ResourceNotFoundException.class, () -> service.recordAction(
                701L,
                new ScoreTruthReviewActionRequest("ACCEPT", null, "ops")
        ));
    }

    @Test
    void recordActionRequiresCommentForCommentAction() {
        SettlementAuditRecordRepository repository = mock(SettlementAuditRecordRepository.class);
        SettlementAuditRecord manualReview = manualReviewRecord(701L, 41L);

        when(repository.findById(701L)).thenReturn(Optional.of(manualReview));

        ScoreTruthReviewService service = new ScoreTruthReviewService(repository, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> service.recordAction(
                701L,
                new ScoreTruthReviewActionRequest("COMMENT", " ", "ops")
        ));
    }

    private SettlementAuditRecord manualReviewRecord(Long id, Long betId) {
        SettlementAuditRecord record = new SettlementAuditRecord();
        setId(record, SettlementAuditRecord.class, id);
        record.setBetId(betId);
        record.setTrackedEventId("sr:match:" + betId);
        record.setDecision(ScoreTruthReviewService.DECISION_MANUAL_REVIEW);
        record.setReason("MANUAL_REVIEW_AWAITING");
        record.setConfidence(null);
        record.setEvidenceId(501L);
        record.setDecidedAt(LocalDateTime.of(2026, 4, 19, 18, 46));
        record.setPayloadJson("{\"contradictionCount\":1,\"coverageState\":\"PARTIAL\"}");
        return record;
    }

    private SettlementAuditRecord actionRecord(Long id,
                                               Long betId,
                                               String decision,
                                               String action,
                                               String reviewer,
                                               String comment,
                                               Long reviewDecisionId) {
        SettlementAuditRecord record = new SettlementAuditRecord();
        setId(record, SettlementAuditRecord.class, id);
        record.setBetId(betId);
        record.setTrackedEventId("sr:match:" + betId);
        record.setDecision(decision);
        record.setReason("OPERATOR_REVIEW");
        record.setConfidence(null);
        record.setEvidenceId(501L);
        record.setDecidedAt(LocalDateTime.of(2026, 4, 19, 19, 15));
        record.setPayloadJson("""
                {"reviewDecisionId":%d,"action":"%s","reviewer":"%s","comment":"%s"}
                """.formatted(reviewDecisionId, action, reviewer, comment));
        return record;
    }

    private void setId(Object target, Class<?> type, Long id) {
        try {
            Field field = type.getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("unable to set id for test", ex);
        }
    }
}
