package com.ttl.tabletennis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.cv.CvAuditEvidenceStore;
import com.ttl.tabletennis.cv.CvAuditEvidenceUploader;
import com.ttl.tabletennis.cv.CvAuditFrameBuffer;
import com.ttl.tabletennis.cv.NoopCvAuditEvidenceUploader;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.SettlementAuditRecord;
import com.ttl.tabletennis.domain.SettlementEvidenceRecord;
import com.ttl.tabletennis.repository.SettlementAuditRecordRepository;
import com.ttl.tabletennis.repository.SettlementContradictionRecordRepository;
import com.ttl.tabletennis.repository.SettlementEvidenceRecordRepository;
import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.Contradiction;
import com.ttl.tabletennis.settlement.ContradictionKind;
import com.ttl.tabletennis.settlement.CoverageState;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.IdentityLock;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.TrackedEventId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementShadowAuditServiceCvAuditTests {

    private static final Instant BUNDLE_AS_OF = Instant.parse("2026-04-19T20:00:00Z");

    @Test
    void contradictionTriggersCvAuditUploadAndPopulatesEvidenceRefs() throws Exception {
        SettlementAuditRecordRepository auditRepository = mock(SettlementAuditRecordRepository.class);
        SettlementEvidenceRecordRepository evidenceRepository = mock(SettlementEvidenceRecordRepository.class);
        SettlementContradictionRecordRepository contradictionRepository = mock(SettlementContradictionRecordRepository.class);
        when(evidenceRepository.findFirstByBetIdAndBundleAsOf(any(), any())).thenReturn(Optional.empty());
        when(evidenceRepository.save(any(SettlementEvidenceRecord.class))).thenAnswer(invocation -> {
            SettlementEvidenceRecord record = invocation.getArgument(0);
            setId(record, 900L);
            return record;
        });
        when(auditRepository.save(any(SettlementAuditRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        buffer.push("tracked-101", BUNDLE_AS_OF.minusSeconds(2), new byte[]{1});
        buffer.push("tracked-101", BUNDLE_AS_OF.minusSeconds(1), new byte[]{2});

        RecordingUploader uploader = new RecordingUploader(List.of(
                "s3://ttl-cv-audit/tracked-101/20260419T2000/00.jpg",
                "s3://ttl-cv-audit/tracked-101/20260419T2000/01.jpg"));
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(buffer, uploader, new ObjectMapper());

        SettlementShadowAuditService service = new SettlementShadowAuditService(
                evidenceRepository, contradictionRepository, auditRepository, new ObjectMapper(), Optional.of(store));

        service.recordAttempt(bet(101L),
                evidenceWithContradiction(101L),
                new HoldOpen(evidenceWithContradiction(101L), SettlementReason.MANUAL_REVIEW_AWAITING, "contradiction"));

        ArgumentCaptor<SettlementAuditRecord> captor = ArgumentCaptor.forClass(SettlementAuditRecord.class);
        verify(auditRepository).save(captor.capture());
        SettlementAuditRecord row = captor.getValue();
        assertEquals(
                "[\"s3://ttl-cv-audit/tracked-101/20260419T2000/00.jpg\","
                        + "\"s3://ttl-cv-audit/tracked-101/20260419T2000/01.jpg\"]",
                row.getEvidenceRefs());
        assertEquals(1, uploader.calls.size());
        assertEquals("tracked-101", uploader.calls.get(0).matchId());
    }

    @Test
    void noContradictionsLeavesEvidenceRefsNull() throws Exception {
        SettlementAuditRecordRepository auditRepository = mock(SettlementAuditRecordRepository.class);
        SettlementEvidenceRecordRepository evidenceRepository = mock(SettlementEvidenceRecordRepository.class);
        SettlementContradictionRecordRepository contradictionRepository = mock(SettlementContradictionRecordRepository.class);
        when(evidenceRepository.findFirstByBetIdAndBundleAsOf(any(), any())).thenReturn(Optional.empty());
        when(evidenceRepository.save(any(SettlementEvidenceRecord.class))).thenAnswer(invocation -> {
            SettlementEvidenceRecord record = invocation.getArgument(0);
            setId(record, 900L);
            return record;
        });
        when(auditRepository.save(any(SettlementAuditRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RecordingUploader uploader = new RecordingUploader(List.of("ref"));
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(
                new CvAuditFrameBuffer(5), uploader, new ObjectMapper());

        SettlementShadowAuditService service = new SettlementShadowAuditService(
                evidenceRepository, contradictionRepository, auditRepository, new ObjectMapper(), Optional.of(store));

        service.recordAttempt(bet(101L),
                evidenceWithoutContradiction(101L),
                new HoldOpen(evidenceWithoutContradiction(101L), SettlementReason.MANUAL_REVIEW_AWAITING, "still live"));

        ArgumentCaptor<SettlementAuditRecord> captor = ArgumentCaptor.forClass(SettlementAuditRecord.class);
        verify(auditRepository).save(captor.capture());
        assertNull(captor.getValue().getEvidenceRefs());
        assertEquals(0, uploader.calls.size());
    }

    @Test
    void disabledStoreLeavesEvidenceRefsNull() throws Exception {
        SettlementAuditRecordRepository auditRepository = mock(SettlementAuditRecordRepository.class);
        SettlementEvidenceRecordRepository evidenceRepository = mock(SettlementEvidenceRecordRepository.class);
        SettlementContradictionRecordRepository contradictionRepository = mock(SettlementContradictionRecordRepository.class);
        when(evidenceRepository.findFirstByBetIdAndBundleAsOf(any(), any())).thenReturn(Optional.empty());
        when(evidenceRepository.save(any(SettlementEvidenceRecord.class))).thenAnswer(invocation -> {
            SettlementEvidenceRecord record = invocation.getArgument(0);
            setId(record, 900L);
            return record;
        });
        when(auditRepository.save(any(SettlementAuditRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CvAuditEvidenceStore store = new CvAuditEvidenceStore(
                new CvAuditFrameBuffer(5), new NoopCvAuditEvidenceUploader(), new ObjectMapper());

        SettlementShadowAuditService service = new SettlementShadowAuditService(
                evidenceRepository, contradictionRepository, auditRepository, new ObjectMapper(), Optional.of(store));

        service.recordAttempt(bet(101L),
                evidenceWithContradiction(101L),
                new HoldOpen(evidenceWithContradiction(101L), SettlementReason.MANUAL_REVIEW_AWAITING, "contradiction"));

        ArgumentCaptor<SettlementAuditRecord> captor = ArgumentCaptor.forClass(SettlementAuditRecord.class);
        verify(auditRepository).save(captor.capture());
        assertNull(captor.getValue().getEvidenceRefs());
    }

    private PaperTradeBet bet(long id) {
        PaperTradeBet bet = new PaperTradeBet();
        try {
            Field field = PaperTradeBet.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(bet, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        bet.setEventKey("event-" + id);
        bet.setDedupeKey("dedupe-" + id);
        bet.setPlacedAt(LocalDateTime.of(2026, 4, 19, 16, 0));
        bet.setPlayer1Id(10L);
        bet.setPlayer2Id(20L);
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        return bet;
    }

    private SettlementEvidence evidenceWithContradiction(long betId) {
        LiveObservation live = liveObservation();
        Contradiction contradiction = new Contradiction(live, live, ContradictionKind.WINNER_DISAGREE, 0.76);
        return baseEvidence(betId, List.of(live), List.of(contradiction));
    }

    private SettlementEvidence evidenceWithoutContradiction(long betId) {
        return baseEvidence(betId, List.of(liveObservation()), List.of());
    }

    private LiveObservation liveObservation() {
        return new LiveObservation(
                SourceId.HR_TGT,
                BUNDLE_AS_OF.minusSeconds(45),
                0.91,
                MatchPhase.LIVE_LATE,
                new ScoreState(2, 1, 10, 7, ""),
                "raw-live",
                true,
                "booker-event-1",
                "market-event-1",
                false,
                true
        );
    }

    private SettlementEvidence baseEvidence(long betId,
                                            List<LiveObservation> live,
                                            List<Contradiction> contradictions) {
        return new SettlementEvidence(
                betId,
                new TrackedEventId("tracked-" + betId),
                new IdentityLock(10L, 20L, BUNDLE_AS_OF.minus(Duration.ofMinutes(30)),
                        Duration.ofMinutes(90), "booker-event-1", "market-event-1"),
                List.copyOf(live),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.FULL,
                contradictions,
                0.2,
                0.91,
                BUNDLE_AS_OF
        );
    }

    private void setId(Object target, Long id) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }

    private static final class RecordingUploader implements CvAuditEvidenceUploader {
        record Call(String matchId, Instant at, List<CvAuditFrameBuffer.AuditFrame> frames) { }

        final List<Call> calls = new ArrayList<>();
        private final List<String> refs;

        RecordingUploader(List<String> refs) {
            this.refs = refs;
        }

        @Override
        public List<String> upload(String matchId, Instant at, List<CvAuditFrameBuffer.AuditFrame> frames) {
            calls.add(new Call(matchId, at, frames));
            return refs;
        }
    }
}
