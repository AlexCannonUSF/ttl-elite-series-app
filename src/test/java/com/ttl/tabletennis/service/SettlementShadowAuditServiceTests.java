package com.ttl.tabletennis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.SettlementAuditRecord;
import com.ttl.tabletennis.domain.SettlementContradictionRecord;
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
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.TrackedEventId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementShadowAuditServiceTests {

    @Test
    void createsEvidenceOnceAndAppendsAuditAttempts() throws Exception {
        SettlementEvidenceRecordRepository evidenceRepository = mock(SettlementEvidenceRecordRepository.class);
        SettlementContradictionRecordRepository contradictionRepository = mock(SettlementContradictionRecordRepository.class);
        SettlementAuditRecordRepository auditRepository = mock(SettlementAuditRecordRepository.class);

        SettlementShadowAuditService service = new SettlementShadowAuditService(
                evidenceRepository,
                contradictionRepository,
                auditRepository,
                new ObjectMapper()
        );

        PaperTradeBet bet = bet(101L);
        SettlementEvidence evidence = evidence(101L, Instant.parse("2026-04-19T20:00:00Z"));
        Settle settle = new Settle(evidence, 10L, SettlementReason.SCORE_BACKED_FINISHED, 0.91);

        when(evidenceRepository.findFirstByBetIdAndBundleAsOf(101L, LocalDateTime.of(2026, 4, 19, 16, 0)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingEvidenceRecord(900L)));
        when(evidenceRepository.save(any(SettlementEvidenceRecord.class))).thenAnswer(invocation -> {
            SettlementEvidenceRecord record = invocation.getArgument(0);
            setId(record, SettlementEvidenceRecord.class, 900L);
            return record;
        });
        when(auditRepository.save(any(SettlementAuditRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordAttempt(bet, evidence, settle);

        service.recordAttempt(bet, evidence, new HoldOpen(evidence, SettlementReason.MANUAL_REVIEW_AWAITING, "still live"));

        verify(evidenceRepository, times(2)).findFirstByBetIdAndBundleAsOf(101L, LocalDateTime.of(2026, 4, 19, 16, 0));
        verify(evidenceRepository, times(1)).save(any(SettlementEvidenceRecord.class));
        verify(contradictionRepository, times(1)).saveAll(anyList());
        verify(auditRepository, times(2)).save(any(SettlementAuditRecord.class));
    }

    @Test
    void recordsManualAuditWhenNoEvidenceBundleExists() {
        SettlementShadowAuditService service = new SettlementShadowAuditService(
                mock(SettlementEvidenceRecordRepository.class),
                mock(SettlementContradictionRecordRepository.class),
                mock(SettlementAuditRecordRepository.class),
                new ObjectMapper()
        );
        SettlementAuditRecordRepository auditRepository = serviceAuditRepository(service);

        PaperTradeBet bet = bet(202L);

        when(auditRepository.save(any(SettlementAuditRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordNoEvidenceAttempt(bet, SettlementDiffLogService.SHADOW_SKIPPED_NO_EVIDENCE);

        verify(auditRepository).save(any(SettlementAuditRecord.class));
        verify(serviceEvidenceRepository(service), never()).save(any(SettlementEvidenceRecord.class));
        verify(serviceContradictionRepository(service), never()).saveAll(anyList());
    }

    private PaperTradeBet bet(Long id) {
        PaperTradeBet bet = new PaperTradeBet();
        setId(bet, PaperTradeBet.class, id);
        bet.setEventKey("event-" + id);
        bet.setDedupeKey("dedupe-" + id);
        bet.setPlacedAt(LocalDateTime.of(2026, 4, 19, 16, 0));
        bet.setPlayer1Id(10L);
        bet.setPlayer2Id(20L);
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        return bet;
    }

    private SettlementEvidence evidence(long betId, Instant bundleAsOf) {
        LiveObservation liveObservation = new LiveObservation(
                SourceId.HR_TGT,
                bundleAsOf.minusSeconds(45),
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
        Contradiction contradiction = new Contradiction(
                liveObservation,
                liveObservation,
                ContradictionKind.WINNER_DISAGREE,
                0.76
        );
        return new SettlementEvidence(
                betId,
                new TrackedEventId("tracked-" + betId),
                new IdentityLock(10L, 20L, bundleAsOf.minus(Duration.ofMinutes(30)), Duration.ofMinutes(90), "booker-event-1", "market-event-1"),
                List.of(liveObservation),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.FULL,
                List.of(contradiction),
                0.2,
                0.91,
                bundleAsOf
        );
    }

    private SettlementEvidenceRecord existingEvidenceRecord(Long id) {
        SettlementEvidenceRecord record = new SettlementEvidenceRecord();
        setId(record, SettlementEvidenceRecord.class, id);
        record.setBetId(101L);
        record.setTrackedEventId("tracked-101");
        record.setBundleAsOf(LocalDateTime.of(2026, 4, 19, 16, 0));
        record.setCoverageState("FULL");
        record.setAmbiguityScore(0.2);
        record.setConfidence(0.91);
        record.setPayloadJson("{}");
        return record;
    }

    private SettlementAuditRecordRepository serviceAuditRepository(SettlementShadowAuditService service) {
        return (SettlementAuditRecordRepository) getField(service, "settlementAuditRecordRepository");
    }

    private SettlementEvidenceRecordRepository serviceEvidenceRepository(SettlementShadowAuditService service) {
        return (SettlementEvidenceRecordRepository) getField(service, "settlementEvidenceRecordRepository");
    }

    private SettlementContradictionRecordRepository serviceContradictionRepository(SettlementShadowAuditService service) {
        return (SettlementContradictionRecordRepository) getField(service, "settlementContradictionRecordRepository");
    }

    private Object getField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("unable to access field " + fieldName, ex);
        }
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
