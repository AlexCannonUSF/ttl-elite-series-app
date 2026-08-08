package com.ttl.tabletennis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.SettlementAuditRecord;
import com.ttl.tabletennis.domain.SettlementContradictionRecord;
import com.ttl.tabletennis.domain.SettlementEvidenceRecord;
import com.ttl.tabletennis.dto.ScoreTruthDecisionsDto;
import com.ttl.tabletennis.dto.ScoreTruthEvidenceDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.SettlementAuditRecordRepository;
import com.ttl.tabletennis.repository.SettlementContradictionRecordRepository;
import com.ttl.tabletennis.repository.SettlementEvidenceRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreTruthQueryServiceTests {

    @Test
    void evidenceReturnsLatestBundleWithContradictionsAndDecisions() {
        SettlementEvidenceRecordRepository evidenceRepository = mock(SettlementEvidenceRecordRepository.class);
        SettlementContradictionRecordRepository contradictionRepository = mock(SettlementContradictionRecordRepository.class);
        SettlementAuditRecordRepository auditRepository = mock(SettlementAuditRecordRepository.class);

        SettlementEvidenceRecord evidenceRecord = new SettlementEvidenceRecord();
        setId(evidenceRecord, SettlementEvidenceRecord.class, 501L);
        evidenceRecord.setBetId(41L);
        evidenceRecord.setTrackedEventId("sr:match:41");
        evidenceRecord.setBundleAsOf(LocalDateTime.of(2026, 4, 19, 18, 45));
        evidenceRecord.setCoverageState("FULL");
        evidenceRecord.setAmbiguityScore(0.24);
        evidenceRecord.setConfidence(0.93);
        evidenceRecord.setLearningEligible(false);
        evidenceRecord.setLearningExclusionReason("AMBIGUOUS_ARCHIVE_SETTLEMENT");
        evidenceRecord.setPayloadJson("{\"observations\":3}");

        SettlementContradictionRecord contradictionRecord = new SettlementContradictionRecord();
        setId(contradictionRecord, SettlementContradictionRecord.class, 601L);
        contradictionRecord.setEvidenceId(501L);
        contradictionRecord.setBetId(41L);
        contradictionRecord.setObservedAt(LocalDateTime.of(2026, 4, 19, 18, 44));
        contradictionRecord.setKind("WINNER_DISAGREE");
        contradictionRecord.setSeverity(0.76);
        contradictionRecord.setResolved(false);
        contradictionRecord.setPayloadJson("{\"left\":\"HR_TGT\",\"right\":\"TTS_POST\"}");

        SettlementAuditRecord auditRecord = new SettlementAuditRecord();
        setId(auditRecord, SettlementAuditRecord.class, 701L);
        auditRecord.setBetId(41L);
        auditRecord.setTrackedEventId("sr:match:41");
        auditRecord.setDecision("MANUAL_REVIEW");
        auditRecord.setReason("MANUAL_REVIEW_AWAITING");
        auditRecord.setConfidence(null);
        auditRecord.setEvidenceId(501L);
        auditRecord.setDecidedAt(LocalDateTime.of(2026, 4, 19, 18, 46));
        auditRecord.setPayloadJson("{\"contradictionCount\":1}");

        when(evidenceRepository.findByTrackedEventIdOrderByBundleAsOfDesc(any(String.class), any(Pageable.class)))
                .thenReturn(List.of(evidenceRecord));
        when(contradictionRepository.findByEvidenceIdOrderByObservedAtDesc(any(Long.class), any(Pageable.class)))
                .thenReturn(List.of(contradictionRecord));
        when(auditRepository.findByTrackedEventIdOrderByDecidedAtDesc(any(String.class), any(Pageable.class)))
                .thenReturn(List.of(auditRecord));

        ScoreTruthQueryService service = new ScoreTruthQueryService(
                evidenceRepository,
                contradictionRepository,
                auditRepository,
                new ObjectMapper()
        );

        ScoreTruthEvidenceDto response = service.evidence("sr:match:41");

        assertEquals("sr:match:41", response.matchId());
        assertEquals(501L, response.evidence().evidenceId());
        assertEquals(41L, response.evidence().betId());
        assertEquals("FULL", response.evidence().coverageState());
        assertEquals(false, response.evidence().learningEligible());
        assertEquals("AMBIGUOUS_ARCHIVE_SETTLEMENT", response.evidence().learningExclusionReason());
        assertEquals(3, response.evidence().payload().path("observations").asInt());
        assertEquals(1, response.contradictions().size());
        assertEquals("WINNER_DISAGREE", response.contradictions().get(0).kind());
        assertEquals("HR_TGT", response.contradictions().get(0).payload().path("left").asText());
        assertEquals(1, response.decisions().size());
        assertEquals("MANUAL_REVIEW", response.decisions().get(0).decision());
        assertEquals(1, response.decisions().get(0).payload().path("contradictionCount").asInt());
    }

    @Test
    void evidenceThrowsWhenMatchIdHasNoSnapshots() {
        SettlementEvidenceRecordRepository evidenceRepository = mock(SettlementEvidenceRecordRepository.class);
        when(evidenceRepository.findByTrackedEventIdOrderByBundleAsOfDesc(any(String.class), any(Pageable.class)))
                .thenReturn(List.of());

        ScoreTruthQueryService service = new ScoreTruthQueryService(
                evidenceRepository,
                mock(SettlementContradictionRecordRepository.class),
                mock(SettlementAuditRecordRepository.class),
                new ObjectMapper()
        );

        assertThrows(ResourceNotFoundException.class, () -> service.evidence("missing-match"));
    }

    @Test
    void evidenceByBetIdReturnsLatestBundle() {
        SettlementEvidenceRecordRepository evidenceRepository = mock(SettlementEvidenceRecordRepository.class);
        SettlementAuditRecordRepository auditRepository = mock(SettlementAuditRecordRepository.class);
        SettlementContradictionRecordRepository contradictionRepository = mock(SettlementContradictionRecordRepository.class);
        SettlementEvidenceRecord evidenceRecord = new SettlementEvidenceRecord();
        setId(evidenceRecord, SettlementEvidenceRecord.class, 511L);
        evidenceRecord.setBetId(88L);
        evidenceRecord.setTrackedEventId("sr:match:88");
        evidenceRecord.setBundleAsOf(LocalDateTime.of(2026, 4, 19, 19, 15));
        evidenceRecord.setCoverageState("PARTIAL");
        evidenceRecord.setAmbiguityScore(0.11);
        evidenceRecord.setConfidence(0.82);
        evidenceRecord.setPayloadJson("{\"liveObservations\":[]}");

        SettlementAuditRecord auditRecord = new SettlementAuditRecord();
        setId(auditRecord, SettlementAuditRecord.class, 811L);
        auditRecord.setBetId(88L);
        auditRecord.setTrackedEventId("sr:match:88");
        auditRecord.setDecision("HOLD_OPEN");
        auditRecord.setReason("AWAIT_MORE_SCORE");
        auditRecord.setConfidence(null);
        auditRecord.setEvidenceId(511L);
        auditRecord.setDecidedAt(LocalDateTime.of(2026, 4, 19, 19, 16));
        auditRecord.setPayloadJson("{\"note\":\"still tracking\"}");

        when(evidenceRepository.findTopByBetIdOrderByBundleAsOfDesc(88L)).thenReturn(java.util.Optional.of(evidenceRecord));
        when(contradictionRepository.findByEvidenceIdOrderByObservedAtDesc(any(Long.class), any(Pageable.class))).thenReturn(List.of());
        when(auditRepository.findByBetIdOrderByDecidedAtDesc(any(Long.class), any(Pageable.class))).thenReturn(List.of(auditRecord));

        ScoreTruthQueryService service = new ScoreTruthQueryService(
                evidenceRepository,
                contradictionRepository,
                auditRepository,
                new ObjectMapper()
        );

        ScoreTruthEvidenceDto response = service.evidenceByBetId(88L);

        assertEquals("88", response.matchId());
        assertEquals(88L, response.evidence().betId());
        assertEquals("sr:match:88", response.evidence().trackedEventId());
        assertEquals(1, response.decisions().size());
        assertEquals("HOLD_OPEN", response.decisions().get(0).decision());
        verify(auditRepository).findByBetIdOrderByDecidedAtDesc(any(Long.class), any(Pageable.class));
    }

    @Test
    void decisionsUsesFromFilterWhenProvided() {
        SettlementAuditRecordRepository auditRepository = mock(SettlementAuditRecordRepository.class);
        SettlementAuditRecord auditRecord = new SettlementAuditRecord();
        setId(auditRecord, SettlementAuditRecord.class, 801L);
        auditRecord.setBetId(55L);
        auditRecord.setTrackedEventId("sr:match:55");
        auditRecord.setDecision("SETTLE");
        auditRecord.setReason("SCORE_BACKED_FINISHED");
        auditRecord.setConfidence(0.91);
        auditRecord.setEvidenceId(901L);
        auditRecord.setDecidedAt(LocalDateTime.of(2026, 4, 19, 19, 0));
        auditRecord.setPayloadJson("{\"winnerPlayerId\":10}");

        when(auditRepository.findByDecidedAtGreaterThanEqualOrderByDecidedAtDesc(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(auditRecord));

        ScoreTruthQueryService service = new ScoreTruthQueryService(
                mock(SettlementEvidenceRecordRepository.class),
                mock(SettlementContradictionRecordRepository.class),
                auditRepository,
                new ObjectMapper()
        );

        Instant from = Instant.parse("2026-04-19T18:30:00Z");
        ScoreTruthDecisionsDto response = service.decisions(from, 10);

        assertEquals(from, response.from());
        assertEquals(1, response.decisions().size());
        assertEquals("SETTLE", response.decisions().get(0).decision());
        assertEquals(10, response.decisions().get(0).payload().path("winnerPlayerId").asInt());
        verify(auditRepository).findByDecidedAtGreaterThanEqualOrderByDecidedAtDesc(any(LocalDateTime.class), any(Pageable.class));
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
