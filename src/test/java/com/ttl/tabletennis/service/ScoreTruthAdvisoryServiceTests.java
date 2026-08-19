package com.ttl.tabletennis.service;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.BetSettlementPolicyCatalog;
import com.ttl.tabletennis.settlement.CoverageState;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.IdentityLock;
import com.ttl.tabletennis.settlement.ManualReview;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEngine;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementEvidenceBuilder;
import com.ttl.tabletennis.settlement.SettlementPolicy;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.TrackedEventId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScoreTruthAdvisoryServiceTests {

    private static final Instant NOW = Instant.parse("2026-04-19T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.of(2026, 4, 19, 20, 0);

    @TempDir
    Path tempDir;

    @Test
    void doesNothingWhenScoreTruthFlagIsOff() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        ScoreTruthAdvisoryService service = service("off", builder, engine, auditService, new SimpleMeterRegistry());

        assertFalse(service.active());
        assertEquals(0, service.recordAdvisoryDecisions(List.of(bet(101L))));

        verifyNoInteractions(builder, engine, auditService);
    }

    @Test
    void recordsManualReviewAdviceWithoutChangingBetStatus() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScoreTruthAdvisoryService service = service("advisory", builder, engine, auditService, meterRegistry);
        PaperTradeBet bet = bet(102L);
        SettlementEvidence evidence = evidence(102L);
        ManualReview manualReview = new ManualReview(evidence, SettlementReason.MANUAL_REVIEW_AWAITING, List.of());

        when(builder.buildForBet(bet)).thenReturn(Optional.of(evidence));
        when(engine.decide(any(SettlementEvidence.class), any())).thenReturn(manualReview);

        assertTrue(service.active());
        assertEquals(1, service.recordAdvisoryDecisions(List.of(bet)));

        verify(auditService).recordAttempt(bet, evidence, manualReview);
        assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
        assertEquals(1.0, meterRegistry.get("ttl.score_truth.advisory.decisions.persisted")
                .tag("decision", "MANUAL_REVIEW")
                .counter()
                .count());
    }

    @Test
    void recordsHoldOpenAdviceButNeverClosesBets() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        ScoreTruthAdvisoryService service = service("advisory", builder, engine, auditService, new SimpleMeterRegistry(), betRepository);
        PaperTradeBet bet = bet(103L);
        SettlementEvidence evidence = evidence(103L);
        HoldOpen holdOpen = new HoldOpen(evidence, SettlementReason.MANUAL_REVIEW_AWAITING, "awaiting independent source");

        when(builder.buildForBet(bet)).thenReturn(Optional.of(evidence));
        when(engine.decide(any(SettlementEvidence.class), any())).thenReturn(holdOpen);

        assertEquals(1, service.recordAdvisoryDecisions(List.of(bet)));

        verify(auditService).recordAttempt(bet, evidence, holdOpen);
        verify(betRepository).save(bet);
        assertEquals(PaperTradeBet.STATUS_PENDING_EVIDENCE, bet.getStatus());
        assertNull(bet.getSettledAt());
        assertEquals(LOCAL_NOW.plusMinutes(180), bet.getPendingEvidenceUntil());
        assertEquals(LOCAL_NOW.plusMinutes(5), bet.getPendingEvidenceNextPollAt());
        assertEquals(SettlementReason.MANUAL_REVIEW_AWAITING.name(), bet.getPendingEvidenceReason());
        assertEquals("awaiting independent source", bet.getPendingEvidenceNote());
        assertNotNull(bet.getPendingEvidenceUpdatedAt());
    }

    @Test
    void observesSettleDecisionWithoutPersistingClosureAdvice() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScoreTruthAdvisoryService service = service("advisory", builder, engine, auditService, meterRegistry);
        PaperTradeBet bet = bet(104L);
        SettlementEvidence evidence = evidence(104L);
        Settle settle = new Settle(evidence, 10L, SettlementReason.SCORE_BACKED_FINISHED, 0.91);

        when(builder.buildForBet(bet)).thenReturn(Optional.of(evidence));
        when(engine.decide(any(SettlementEvidence.class), any())).thenReturn(settle);

        assertEquals(0, service.recordAdvisoryDecisions(List.of(bet)));

        verify(auditService, never()).recordAttempt(any(), any(), any());
        assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
        assertEquals(1.0, meterRegistry.get("ttl.score_truth.advisory.decisions.persisted")
                .tag("decision", "OBSERVED_SETTLE")
                .counter()
                .count());
    }

    @Test
    void recordsNoEvidenceAsAdvisoryManualReview() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        ScoreTruthAdvisoryService service = service("advisory", builder, engine, auditService, new SimpleMeterRegistry());
        PaperTradeBet bet = bet(105L);

        when(builder.buildForBet(bet)).thenReturn(Optional.empty());

        assertEquals(1, service.recordAdvisoryDecisions(List.of(bet)));

        verify(auditService).recordNoEvidenceAttempt(bet, ScoreTruthAdvisoryService.ADVISORY_NO_EVIDENCE);
        verifyNoInteractions(engine);
    }

    @Test
    void usesHotReloadedPolicyForAdvisoryDecision() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        BetSettlementPolicyCatalog policyCatalog = mock(BetSettlementPolicyCatalog.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        SettlementPolicy policy = new SettlementPolicy(
                new SettlementPolicy.Ambiguity(0.61),
                new SettlementPolicy.Settlement(0.92, 0.45, 4),
                SettlementPolicy.defaults().staleLiveRecovery(),
                SettlementPolicy.defaults().heuristic()
        );
        ScoreTruthAdvisoryService service = new ScoreTruthAdvisoryService(
                featureCatalogWithScoreTruth("advisory"),
                builder,
                engine,
                auditService,
                new SimpleMeterRegistry(),
                betRepository,
                policyCatalog,
                CLOCK
        );
        PaperTradeBet bet = bet(109L);
        SettlementEvidence evidence = evidence(109L);
        ManualReview manualReview = new ManualReview(evidence, SettlementReason.MANUAL_REVIEW_AWAITING, List.of());

        when(policyCatalog.currentPolicy()).thenReturn(policy);
        when(builder.buildForBet(bet)).thenReturn(Optional.of(evidence));
        when(engine.decide(evidence, policy)).thenReturn(manualReview);

        assertEquals(1, service.recordAdvisoryDecisions(List.of(bet)));

        verify(engine).decide(evidence, policy);
        verify(auditService).recordAttempt(bet, evidence, manualReview);
    }

    @Test
    void pollPendingEvidenceReschedulesWhenEngineStillHoldsOpen() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScoreTruthAdvisoryService service = service("advisory", builder, engine, auditService, meterRegistry, betRepository);
        PaperTradeBet bet = pendingBet(106L);
        LocalDateTime originalTtl = bet.getPendingEvidenceUntil();
        SettlementEvidence evidence = evidence(106L);
        HoldOpen holdOpen = new HoldOpen(evidence, SettlementReason.MANUAL_REVIEW_AWAITING, "still waiting");

        when(betRepository.findByStatusAndPendingEvidenceNextPollAtLessThanEqualOrderByPendingEvidenceNextPollAtAsc(
                eq(PaperTradeBet.STATUS_PENDING_EVIDENCE),
                eq(LOCAL_NOW),
                any(Pageable.class)
        )).thenReturn(List.of(bet));
        when(builder.buildForBet(bet)).thenReturn(Optional.of(evidence));
        when(engine.decide(evidence, com.ttl.tabletennis.settlement.SettlementPolicy.defaults())).thenReturn(holdOpen);

        assertEquals(1, service.pollPendingEvidence());

        verify(auditService).recordAttempt(bet, evidence, holdOpen);
        verify(betRepository).save(bet);
        assertEquals(PaperTradeBet.STATUS_PENDING_EVIDENCE, bet.getStatus());
        assertEquals(originalTtl, bet.getPendingEvidenceUntil());
        assertEquals(LOCAL_NOW.plusMinutes(5), bet.getPendingEvidenceNextPollAt());
        assertEquals("still waiting", bet.getPendingEvidenceNote());
        assertEquals(1.0, meterRegistry.get("ttl.score_truth.advisory.decisions.persisted")
                .tag("decision", "POLL_HOLD_OPEN")
                .counter()
                .count());
    }

    @Test
    void pollPendingEvidenceStopsPollingWhenTtlExpires() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        ScoreTruthAdvisoryService service = service("advisory", builder, engine, auditService, new SimpleMeterRegistry(), betRepository);
        PaperTradeBet bet = pendingBet(107L);
        bet.setPendingEvidenceUntil(LOCAL_NOW.minusMinutes(1));

        when(betRepository.findByStatusAndPendingEvidenceNextPollAtLessThanEqualOrderByPendingEvidenceNextPollAtAsc(
                eq(PaperTradeBet.STATUS_PENDING_EVIDENCE),
                eq(LOCAL_NOW),
                any(Pageable.class)
        )).thenReturn(List.of(bet));

        assertEquals(1, service.pollPendingEvidence());

        verify(auditService).recordNoEvidenceAttempt(bet, ScoreTruthAdvisoryService.PENDING_EVIDENCE_TTL_EXPIRED);
        verify(builder, never()).buildForBet(any(PaperTradeBet.class));
        verifyNoInteractions(engine);
        verify(betRepository).save(bet);
        assertEquals(PaperTradeBet.STATUS_PENDING_EVIDENCE, bet.getStatus());
        assertEquals(ScoreTruthAdvisoryService.PENDING_EVIDENCE_TTL_EXPIRED, bet.getPendingEvidenceReason());
        assertNull(bet.getPendingEvidenceNextPollAt());
        assertNull(bet.getSettledAt());
    }

    @Test
    void pollPendingEvidenceMarksResolvedDecisionReadyForOperatorConfirmation() throws Exception {
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScoreTruthAdvisoryService service = service("advisory", builder, engine, auditService, meterRegistry, betRepository);
        PaperTradeBet bet = pendingBet(108L);
        SettlementEvidence evidence = evidence(108L);
        Settle settle = new Settle(evidence, 10L, SettlementReason.SCORE_BACKED_FINISHED, 0.91);

        when(betRepository.findByStatusAndPendingEvidenceNextPollAtLessThanEqualOrderByPendingEvidenceNextPollAtAsc(
                eq(PaperTradeBet.STATUS_PENDING_EVIDENCE),
                eq(LOCAL_NOW),
                any(Pageable.class)
        )).thenReturn(List.of(bet));
        when(builder.buildForBet(bet)).thenReturn(Optional.of(evidence));
        when(engine.decide(evidence, com.ttl.tabletennis.settlement.SettlementPolicy.defaults())).thenReturn(settle);

        assertEquals(1, service.pollPendingEvidence());

        verify(auditService, never()).recordAttempt(any(), any(), any());
        verify(betRepository).save(bet);
        assertEquals(PaperTradeBet.STATUS_PENDING_EVIDENCE, bet.getStatus());
        assertEquals(ScoreTruthAdvisoryService.PENDING_EVIDENCE_REVIEW_READY, bet.getPendingEvidenceReason());
        assertEquals("SETTLE requires operator confirmation", bet.getPendingEvidenceNote());
        assertNull(bet.getPendingEvidenceNextPollAt());
        assertNull(bet.getSettledAt());
        assertEquals(1.0, meterRegistry.get("ttl.score_truth.advisory.decisions.persisted")
                .tag("decision", "POLL_OBSERVED_SETTLE")
                .counter()
                .count());
    }

    private ScoreTruthAdvisoryService service(String state,
                                              SettlementEvidenceBuilder builder,
                                              SettlementEngine engine,
                                              SettlementShadowAuditService auditService,
                                              SimpleMeterRegistry meterRegistry) throws Exception {
        return service(state, builder, engine, auditService, meterRegistry, mock(PaperTradeBetRepository.class));
    }

    private ScoreTruthAdvisoryService service(String state,
                                              SettlementEvidenceBuilder builder,
                                              SettlementEngine engine,
                                              SettlementShadowAuditService auditService,
                                              SimpleMeterRegistry meterRegistry,
                                              PaperTradeBetRepository betRepository) throws Exception {
        return new ScoreTruthAdvisoryService(
                featureCatalogWithScoreTruth(state),
                builder,
                engine,
                auditService,
                meterRegistry,
                betRepository,
                CLOCK
        );
    }

    private FeatureFlagCatalog featureCatalogWithScoreTruth(String state) throws Exception {
        Path catalogPath = tempDir.resolve("features-" + state + ".yaml");
        Files.writeString(catalogPath, """
                schema_version: 1
                features:
                  "features.score-truth":
                    owner: "Alex"
                    expires_on: "2026-07-15"
                    state: "%s"
                    description: "Controls Score Truth advisory rollout."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "advisory"
                      - "primary"
                """.formatted(state));
        return new FeatureFlagCatalog(catalogPath.toString());
    }

    private PaperTradeBet bet(Long id) {
        PaperTradeBet bet = new PaperTradeBet();
        setId(bet, id);
        bet.setEventKey("event-" + id);
        bet.setDedupeKey("dedupe-" + id);
        bet.setPlacedAt(LocalDateTime.of(2026, 4, 19, 16, 0));
        bet.setPlayer1Id(10L);
        bet.setPlayer2Id(20L);
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        return bet;
    }

    private PaperTradeBet pendingBet(Long id) {
        PaperTradeBet bet = bet(id);
        bet.setStatus(PaperTradeBet.STATUS_PENDING_EVIDENCE);
        bet.setPendingEvidenceUntil(LOCAL_NOW.plusMinutes(30));
        bet.setPendingEvidenceNextPollAt(LOCAL_NOW.minusMinutes(1));
        bet.setPendingEvidenceReason(SettlementReason.MANUAL_REVIEW_AWAITING.name());
        bet.setPendingEvidenceNote("awaiting independent source");
        bet.setPendingEvidenceUpdatedAt(LOCAL_NOW.minusMinutes(5));
        return bet;
    }

    private SettlementEvidence evidence(long betId) {
        Instant bundleAsOf = NOW;
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
                List.of(),
                0.2,
                0.91,
                bundleAsOf
        );
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
