package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.SettlementDiffLog;
import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import com.ttl.tabletennis.settlement.Contradiction;
import com.ttl.tabletennis.settlement.ContradictionGuard;
import com.ttl.tabletennis.settlement.Decision;
import com.ttl.tabletennis.settlement.ContradictionKind;
import com.ttl.tabletennis.settlement.CoverageState;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.IdentityLock;
import com.ttl.tabletennis.settlement.ManualReview;
import com.ttl.tabletennis.settlement.SettlementEngine;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementEvidenceBuilder;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SettlementDiffLogServiceTests {

    @Test
    void recordsAgreeRowsForTrackedAttemptsIncludingOpenBets() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        SettlementDiffLogService service = new SettlementDiffLogService(
                repository,
                mock(SettlementEvidenceBuilder.class),
                mock(ContradictionGuard.class),
                mock(SettlementEngine.class),
                mock(SettlementShadowAuditService.class)
        );

        PaperTradeBet settledBet = new PaperTradeBet();
        setId(settledBet, 11L);
        settledBet.setStatus(PaperTradeBet.STATUS_WON);
        settledBet.setSettlementReason("SETTLED_FROM_FINISHED_LIVE_SCORE");
        settledBet.setWinnerPlayerId(99L);
        settledBet.setSettledAt(LocalDateTime.of(2026, 4, 16, 12, 30));

        PaperTradeBet stillOpenBet = new PaperTradeBet();
        setId(stillOpenBet, 12L);
        stillOpenBet.setStatus(PaperTradeBet.STATUS_OPEN);

        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        int recorded = service.recordIdentityReplay(List.of(settledBet, stillOpenBet));

        assertEquals(2, recorded);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        @SuppressWarnings("unchecked")
        List<SettlementDiffLog> rows = (List<SettlementDiffLog>) captor.getValue();
        assertEquals(2, rows.size());
        assertEquals(11L, rows.get(0).getBetId());
        assertEquals(SettlementDiffLog.DIFF_KIND_AGREE, rows.get(0).getDiffKind());
        assertEquals("SETTLED_FROM_FINISHED_LIVE_SCORE", rows.get(0).getOldReason());
        assertEquals("SETTLED_FROM_FINISHED_LIVE_SCORE", rows.get(0).getNewReason());
        assertEquals(99L, rows.get(0).getOldWinner());
        assertEquals(99L, rows.get(0).getNewWinner());
        assertEquals(LocalDateTime.of(2026, 4, 16, 12, 30), rows.get(0).getDecidedAt());
        assertEquals(12L, rows.get(1).getBetId());
        assertEquals(SettlementDiffLog.DIFF_KIND_AGREE, rows.get(1).getDiffKind());
        assertEquals(SettlementDiffLogService.STATUS_HOLD_OPEN, rows.get(1).getOldReason());
        assertEquals(SettlementDiffLogService.STATUS_HOLD_OPEN, rows.get(1).getNewReason());
        assertNotNull(rows.get(1).getDecidedAt());
    }

    @Test
    void skipsRepositoryWorkWhenNoTrackedBetsExist() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        SettlementDiffLogService service = new SettlementDiffLogService(
                repository,
                mock(SettlementEvidenceBuilder.class),
                mock(ContradictionGuard.class),
                mock(SettlementEngine.class),
                mock(SettlementShadowAuditService.class)
        );

        assertEquals(0, service.recordIdentityReplay(List.of()));
        verifyNoInteractions(repository);
    }

    @Test
    void repeatedDiffFingerprintDoesNotAppendAnotherRow() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        SettlementDiffLogService service = new SettlementDiffLogService(
                repository,
                mock(SettlementEvidenceBuilder.class),
                mock(ContradictionGuard.class),
                mock(SettlementEngine.class),
                mock(SettlementShadowAuditService.class)
        );
        PaperTradeBet bet = new PaperTradeBet();
        setId(bet, 13L);
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        when(repository.existsByDiffFingerprint(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        int recorded = service.recordIdentityReplay(List.of(bet));

        assertEquals(0, recorded);
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void recordsContradictionRowsWhenScoreTruthBlocksLegacyOutcome() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        ContradictionGuard contradictionGuard = mock(ContradictionGuard.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        SettlementDiffLogService service = new SettlementDiffLogService(repository, builder, contradictionGuard, engine, auditService);

        PaperTradeBet settledBet = new PaperTradeBet();
        setId(settledBet, 21L);
        settledBet.setStatus(PaperTradeBet.STATUS_WON);
        settledBet.setSettlementReason("SETTLED_FROM_OFFICIAL_RESULT");
        settledBet.setWinnerPlayerId(10L);
        settledBet.setSettledAt(LocalDateTime.of(2026, 4, 16, 13, 0));

        SettlementEvidence evidence = new SettlementEvidence(
                21L,
                new TrackedEventId("tracked-21"),
                new IdentityLock(
                        10L,
                        20L,
                        Instant.parse("2026-04-16T12:00:00Z"),
                        Duration.ofMinutes(90),
                        "booker-21",
                        "market-21"
                ),
                List.of(new LiveObservation(
                        com.ttl.tabletennis.scrape.SourceId.HR_TGT,
                        Instant.parse("2026-04-16T12:59:00Z"),
                        0.9,
                        MatchPhase.LIVE_LATE,
                        new ScoreState(2, 2, 8, 10, ""),
                        "raw-live",
                        false,
                        "booker-21",
                        "market-21",
                        false,
                        false
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.1,
                0.9,
                Instant.parse("2026-04-16T13:00:00Z")
        );
        Contradiction contradiction = new Contradiction(
                evidence.liveObservations().get(0),
                evidence.liveObservations().get(0),
                ContradictionKind.WINNER_DISAGREE,
                0.76
        );
        Decision shadowDecision = new ManualReview(evidence, SettlementReason.MANUAL_REVIEW_AWAITING, List.of(contradiction));

        when(builder.buildForBet(settledBet)).thenReturn(Optional.of(evidence));
        when(engine.decide(org.mockito.ArgumentMatchers.eq(evidence), org.mockito.ArgumentMatchers.any())).thenReturn(shadowDecision);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        int recorded = service.recordScoreTruthReplay(List.of(settledBet));

        assertEquals(1, recorded);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        @SuppressWarnings("unchecked")
        List<SettlementDiffLog> rows = (List<SettlementDiffLog>) captor.getValue();
        assertEquals(1, rows.size());
        assertEquals(SettlementDiffLog.DIFF_KIND_CONTRADICTION, rows.get(0).getDiffKind());
        assertEquals("SETTLED_FROM_OFFICIAL_RESULT", rows.get(0).getOldReason());
        assertEquals("MANUAL_REVIEW_AWAITING", rows.get(0).getNewReason());
        assertEquals(10L, rows.get(0).getOldWinner());
    }

    @Test
    void recordsAgreeForOpenLegacyBetWhenShadowAlsoHoldsOpen() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementDiffLogService service = new SettlementDiffLogService(
                repository,
                builder,
                mock(ContradictionGuard.class),
                engine,
                mock(SettlementShadowAuditService.class)
        );

        PaperTradeBet openBet = new PaperTradeBet();
        setId(openBet, 31L);
        openBet.setStatus(PaperTradeBet.STATUS_OPEN);

        SettlementEvidence evidence = new SettlementEvidence(
                31L,
                new TrackedEventId("tracked-31"),
                new IdentityLock(
                        10L,
                        20L,
                        Instant.parse("2026-04-16T12:00:00Z"),
                        Duration.ofMinutes(90),
                        "booker-31",
                        "market-31"
                ),
                List.of(new LiveObservation(
                        com.ttl.tabletennis.scrape.SourceId.HR_TGT,
                        Instant.parse("2026-04-16T12:10:00Z"),
                        0.91,
                        MatchPhase.LIVE_MID,
                        new ScoreState(1, 1, 8, 7, ""),
                        "raw-live",
                        false,
                        "booker-31",
                        "market-31",
                        true,
                        false
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.PARTIAL,
                List.of(),
                0.0,
                0.91,
                Instant.parse("2026-04-16T12:10:30Z")
        );

        when(builder.buildForBet(openBet)).thenReturn(Optional.of(evidence));
        when(engine.decide(org.mockito.ArgumentMatchers.eq(evidence), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new HoldOpen(evidence, SettlementReason.MANUAL_REVIEW_AWAITING, "still live"));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        int recorded = service.recordScoreTruthReplay(List.of(openBet));

        assertEquals(1, recorded);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        @SuppressWarnings("unchecked")
        List<SettlementDiffLog> rows = (List<SettlementDiffLog>) captor.getValue();
        assertEquals(SettlementDiffLog.DIFF_KIND_AGREE, rows.get(0).getDiffKind());
        assertEquals(SettlementDiffLogService.STATUS_HOLD_OPEN, rows.get(0).getOldReason());
        assertEquals(SettlementReason.MANUAL_REVIEW_AWAITING.name(), rows.get(0).getNewReason());
    }

    @Test
    void recordsShadowSkippedRowWhenEvidenceBundleCannotBeBuilt() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementDiffLogService service = new SettlementDiffLogService(
                repository,
                builder,
                mock(ContradictionGuard.class),
                mock(SettlementEngine.class),
                mock(SettlementShadowAuditService.class)
        );

        PaperTradeBet openBet = new PaperTradeBet();
        setId(openBet, 41L);
        openBet.setStatus(PaperTradeBet.STATUS_OPEN);

        when(builder.buildForBet(openBet)).thenReturn(Optional.empty());
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        int recorded = service.recordScoreTruthReplay(List.of(openBet));

        assertEquals(1, recorded);
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        @SuppressWarnings("unchecked")
        List<SettlementDiffLog> rows = (List<SettlementDiffLog>) captor.getValue();
        assertEquals(SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF, rows.get(0).getDiffKind());
        assertEquals(SettlementDiffLogService.STATUS_HOLD_OPEN, rows.get(0).getOldReason());
        assertEquals(SettlementDiffLogService.SHADOW_SKIPPED_NO_EVIDENCE, rows.get(0).getNewReason());
    }

    private void setId(PaperTradeBet bet, Long id) {
        try {
            Field field = PaperTradeBet.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(bet, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("unable to set bet id for test", ex);
        }
    }
}
