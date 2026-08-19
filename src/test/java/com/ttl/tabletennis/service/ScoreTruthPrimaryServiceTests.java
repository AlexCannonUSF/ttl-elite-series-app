package com.ttl.tabletennis.service;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.prediction.staking.ClosingLineLookupService;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.BetSettlementPolicyCatalog;
import com.ttl.tabletennis.settlement.Contradiction;
import com.ttl.tabletennis.settlement.ContradictionKind;
import com.ttl.tabletennis.settlement.CoverageState;
import com.ttl.tabletennis.settlement.Decision;
import com.ttl.tabletennis.settlement.Escalate;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.IdentityLock;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEngine;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementEvidenceBuilder;
import com.ttl.tabletennis.settlement.SettlementPolicy;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.TrackedEventId;
import com.ttl.tabletennis.settlement.VoidDecision;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import com.ttl.tabletennis.settlement.observation.StreamObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreTruthPrimaryServiceTests {

    private static final Instant BUNDLE_AS_OF = Instant.parse("2026-05-18T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(BUNDLE_AS_OF, ZoneId.of("UTC"));

    @TempDir
    Path tempDir;

    @Test
    void closesBetAsWinWhenSettleNamesOurSide() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(1L, /* sidePlayerId */ 10L, 100.0, 1.8);
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(evidence(bet.getId())));
        when(setup.engine.decide(any(), any())).thenReturn(new Settle(evidence(bet.getId()), 10L, SettlementReason.SCORE_BACKED_FINISHED, 0.95));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(1, stats.settled());
        assertEquals(0, stats.voided());
        assertEquals(PaperTradeBet.STATUS_WON, bet.getStatus());
        assertEquals(10L, bet.getWinnerPlayerId());
        assertEquals(100.0 * (1.8 - 1.0), bet.getProfitLoss(), 1e-9);
        assertNotNull(bet.getSettledAt());
        assertEquals(0.95, bet.getSettlementConfidence(), 1e-9);
        assertNotNull(bet.getSettlementEvidenceFingerprint());
        assertEquals(1, bet.getSettlementEvidenceSourceCount());
        assertEquals(CoverageState.FULL.name(), bet.getSettlementCoverageState());
        assertEquals(0.1, bet.getSettlementAmbiguityScore(), 1e-9);
        assertEquals(LocalDateTime.ofInstant(BUNDLE_AS_OF, ZoneId.systemDefault()), bet.getSettlementObservedAt());
        verify(setup.betRepository).save(bet);
    }

    @Test
    void capturesEvidenceAndClosingPriceProvenanceBeforePersistingOutcome() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(11L, 10L, 40.0, 2.1);
        SettlementEvidence evidence = evidence(bet.getId());
        Settle settle = new Settle(evidence, 10L, SettlementReason.SCORE_BACKED_FINISHED, 0.94);
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(evidence));
        when(setup.engine.decide(any(), any())).thenReturn(settle);
        when(setup.auditService.recordAttempt(bet, evidence, settle)).thenReturn(
                new SettlementShadowAuditService.AuditWriteResult(
                        true,
                        701L,
                        501L,
                        "evidence-fingerprint",
                        "decision-fingerprint"
                )
        );
        when(setup.closingLineLookupService.findFor(bet)).thenReturn(Optional.of(
                new ClosingLineLookupService.ClosingLine(
                        1.92,
                        LocalDateTime.of(2026, 5, 18, 19, 59),
                        "CLOSED",
                        "HR_MKT"
                )
        ));

        setup.service.closeOpenBets(List.of(bet));

        assertEquals(501L, bet.getSettlementEvidenceId());
        assertEquals("evidence-fingerprint", bet.getSettlementEvidenceFingerprint());
        assertEquals(1.92, bet.getClosingDecimalOdds(), 1e-9);
        assertEquals(LocalDateTime.of(2026, 5, 18, 19, 59), bet.getClosingObservedAt());
        assertEquals("HR_MKT", bet.getClosingSource());
        assertEquals("CLOSED", bet.getClosingMarketState());
        verify(setup.auditService).recordLearningEligibility(501L, bet);
        assertEquals(1.0, setup.meterRegistry.counter(
                ScoreTruthPrimaryService.LEARNING_SETTLEMENT_METRIC,
                "eligibility", "excluded",
                "reason", "LOW_CONFIDENCE_SETTLEMENT").count(), 1e-9);
    }

    @Test
    void closesBetAsLossWhenSettleNamesOpponent() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(2L, 10L, 50.0, 2.5);
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(evidence(bet.getId())));
        when(setup.engine.decide(any(), any())).thenReturn(new Settle(evidence(bet.getId()), 20L, SettlementReason.SCORE_BACKED_FINISHED, 0.93));

        setup.service.closeOpenBets(List.of(bet));

        assertEquals(PaperTradeBet.STATUS_LOST, bet.getStatus());
        assertEquals(20L, bet.getWinnerPlayerId());
        assertEquals(-50.0, bet.getProfitLoss(), 1e-9);
        verify(setup.betRepository).save(bet);
    }

    @Test
    void voidDecisionIsAppliedByPrimaryWithoutLegacyFallthrough() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(3L, 10L, 25.0, 2.0);
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(evidence(bet.getId())));
        when(setup.engine.decide(any(), any())).thenReturn(new VoidDecision(evidence(bet.getId()), SettlementReason.MANUAL_REVIEW_AWAITING));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(1, stats.voided());
        assertEquals(0, stats.held());
        assertEquals(PaperTradeBet.STATUS_VOIDED, bet.getStatus());
        assertEquals(0.0, bet.getProfitLoss(), 1e-9);
        assertEquals("V3_PRIMARY_VOID", bet.getSettlementSource());
        verify(setup.betRepository).save(bet);
    }

    @Test
    void holdOpenDecisionLeavesStatusOpenAndIncrementsHeld() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(4L, 10L, 30.0, 1.7);
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(evidence(bet.getId())));
        when(setup.engine.decide(any(), any())).thenReturn(new HoldOpen(evidence(bet.getId()), SettlementReason.MANUAL_REVIEW_AWAITING, "still live"));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(0, stats.settled());
        assertEquals(1, stats.held());
        assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
        assertEquals("DECISION_GRADE", bet.getScoreEvidenceQuality());
        verify(setup.betRepository).save(bet);
    }

    @Test
    void scoreBackedPrimaryCloseRequiresStreamCvWhenTrackedAfterClose() throws Exception {
        Setup setup = setup("primary", "on");
        PaperTradeBet bet = openBet(41L, 10L, 30.0, 1.7);
        bet.setTrackedAfterClose(true);
        SettlementEvidence settlementEvidence = evidence(bet.getId());
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(settlementEvidence));
        when(setup.engine.decide(any(), any())).thenReturn(
                new Settle(settlementEvidence, 10L, SettlementReason.SCORE_BACKED_FINISHED, 0.95));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(0, stats.settled());
        assertEquals(1, stats.held());
        assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
        verify(setup.betRepository).save(bet);

        ArgumentCaptor<Decision> decisionCaptor = ArgumentCaptor.forClass(Decision.class);
        verify(setup.auditService).recordAttempt(Mockito.eq(bet), Mockito.eq(settlementEvidence), decisionCaptor.capture());
        assertTrue(decisionCaptor.getValue() instanceof HoldOpen);
        assertEquals(SettlementReason.SCORE_BACKED_ONLY, decisionCaptor.getValue().reason());
        assertEquals(1.0, setup.meterRegistry.counter(
                "ttl.score_truth.primary.closures", "outcome", "SCORE_BACKED_ONLY").count(), 1e-9);
    }

    @Test
    void streamCvOffDoesNotBlockTrustedTargetedCompletion() throws Exception {
        Setup setup = setup("primary", "off");
        PaperTradeBet bet = openBet(44L, 10L, 30.0, 1.7);
        bet.setTrackedAfterClose(true);
        SettlementEvidence settlementEvidence = evidence(bet.getId());
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(settlementEvidence));
        when(setup.engine.decide(any(), any())).thenReturn(
                new Settle(settlementEvidence, 10L, SettlementReason.TARGETED_COMPLETION_SIGNAL, 0.98));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(1, stats.settled());
        assertEquals(0, stats.held());
        assertEquals(PaperTradeBet.STATUS_WON, bet.getStatus());
    }

    @Test
    void streamCvEvidenceAllowsTrackedAfterCloseScoreBackedClosure() throws Exception {
        Setup setup = setup("primary", "on");
        PaperTradeBet bet = openBet(42L, 10L, 30.0, 1.7);
        bet.setTrackedAfterClose(true);
        SettlementEvidence settlementEvidence = evidenceWithStream(bet.getId());
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(settlementEvidence));
        when(setup.engine.decide(any(), any())).thenReturn(
                new Settle(settlementEvidence, 10L, SettlementReason.SCORE_BACKED_FINISHED, 0.95));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(1, stats.settled());
        assertEquals(0, stats.held());
        assertEquals(PaperTradeBet.STATUS_WON, bet.getStatus());
        verify(setup.betRepository).save(bet);
        assertEquals(1.0, setup.meterRegistry.counter(
                ScoreTruthPrimaryService.LEARNING_SETTLEMENT_METRIC,
                "eligibility", "trusted",
                "reason", "ELIGIBLE").count(), 1e-9);
    }

    @Test
    void officialResultCanCloseTrackedAfterCloseWithoutStreamCv() throws Exception {
        Setup setup = setup("primary", "on");
        PaperTradeBet bet = openBet(43L, 10L, 30.0, 1.7);
        bet.setTrackedAfterClose(true);
        SettlementEvidence settlementEvidence = evidence(bet.getId());
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(settlementEvidence));
        when(setup.engine.decide(any(), any())).thenReturn(
                new Settle(settlementEvidence, 10L, SettlementReason.OFFICIAL_RESULT_CONFIRMED, 0.95));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(1, stats.settled());
        assertEquals(0, stats.held());
        assertEquals(PaperTradeBet.STATUS_WON, bet.getStatus());
        verify(setup.betRepository).save(bet);
    }

    @Test
    void escalateAndManualReviewLeaveStatusOpenAndIncrementReviewed() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(5L, 10L, 40.0, 2.1);
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.of(evidence(bet.getId())));
        when(setup.engine.decide(any(), any())).thenReturn(new Escalate(evidence(bet.getId()), SettlementReason.MANUAL_REVIEW_AWAITING, List.of(SourceId.TTS_POST)));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(0, stats.settled());
        assertEquals(1, stats.reviewed());
        assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
    }

    @Test
    void noEvidenceCountsAsSkipped() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(6L, 10L, 20.0, 1.9);
        when(setup.evidenceBuilder.buildForBet(bet)).thenReturn(Optional.empty());

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(0, stats.settled());
        assertEquals(1, stats.skipped());
        verify(setup.engine, never()).decide(any(), any());
        assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
    }

    @Test
    void inactiveModeShortCircuitsAndDoesNotMutate() throws Exception {
        for (String state : new String[]{"off", "shadow", "advisory"}) {
            Setup setup = setup(state);
            PaperTradeBet bet = openBet(7L, 10L, 100.0, 1.8);
            assertFalse(setup.service.active(), "expected inactive for state " + state);

            ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

            assertEquals(0, stats.total(), "expected no closures in state " + state);
            verify(setup.engine, never()).decide(any(), any());
            assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
        }
    }

    @Test
    void skipsBetsThatAreNoLongerOpen() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(8L, 10L, 100.0, 1.8);
        bet.setStatus(PaperTradeBet.STATUS_WON);

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(0, stats.total());
        verify(setup.engine, never()).decide(any(), any());
        verify(setup.evidenceBuilder, never()).buildForBet(any());
    }

    @Test
    void exceptionInsideClosureLogsAndCountsAsSkipped() throws Exception {
        Setup setup = setup("primary");
        PaperTradeBet bet = openBet(9L, 10L, 100.0, 1.8);
        when(setup.evidenceBuilder.buildForBet(bet)).thenThrow(new RuntimeException("boom"));

        ScoreTruthPrimaryService.ClosureStats stats = setup.service.closeOpenBets(List.of(bet));

        assertEquals(1, stats.skipped());
        assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
    }

    // ---- helpers --------------------------------------------------------

    private static PaperTradeBet openBet(long id, long sidePlayerId, double stake, double odds) {
        PaperTradeBet bet = new PaperTradeBet();
        try {
            java.lang.reflect.Field field = PaperTradeBet.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(bet, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        bet.setPlayer1Id(10L);
        bet.setPlayer2Id(20L);
        bet.setSidePlayerId(sidePlayerId);
        bet.setStake(stake);
        bet.setDecimalOdds(odds);
        return bet;
    }

    private static SettlementEvidence evidence(long betId) {
        return evidence(betId, List.of());
    }

    private static SettlementEvidence evidenceWithStream(long betId) {
        StreamObservation stream = new StreamObservation(
                SourceId.STREAM_CV,
                BUNDLE_AS_OF.minusSeconds(30),
                0.94,
                MatchPhase.FINISHED,
                new ScoreState(3, 1, 11, 7, ""),
                "cv-frame-bundle",
                true,
                "route-1",
                "template-1",
                4
        );
        return evidence(betId, List.of(stream));
    }

    private static SettlementEvidence evidence(long betId, List<StreamObservation> streamObservations) {
        LiveObservation live = new LiveObservation(
                SourceId.HR_TGT,
                BUNDLE_AS_OF.minusSeconds(60),
                0.95,
                MatchPhase.LIVE_LATE,
                new ScoreState(3, 1, 11, 7, ""),
                "raw",
                true,
                "booker-1",
                "market-1",
                false,
                true
        );
        Contradiction contradiction = new Contradiction(live, live, ContradictionKind.WINNER_DISAGREE, 0.10);
        return new SettlementEvidence(
                betId,
                new TrackedEventId("evt-" + betId),
                new IdentityLock(10L, 20L, BUNDLE_AS_OF.minus(Duration.ofMinutes(30)), Duration.ofMinutes(90), "booker-1", "market-1"),
                List.of(live),
                List.of(),
                streamObservations,
                List.of(),
                List.of(),
                CoverageState.FULL,
                List.of(contradiction),
                0.1,
                0.95,
                BUNDLE_AS_OF
        );
    }

    private FeatureFlagCatalog catalogFor(String state) throws Exception {
        return catalogFor(state, "off");
    }

    private FeatureFlagCatalog catalogFor(String state, String streamCvState) throws Exception {
        Path catalogPath = tempDir.resolve("features-" + state + ".yaml");
        Files.writeString(catalogPath, """
                schema_version: 1
                features:
                  "features.score-truth":
                    owner: "Alex"
                    expires_on: "2026-12-31"
                    state: "%s"
                    description: "Controls Score Truth rollout."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "advisory"
                      - "primary"
                  "features.stream-cv":
                    owner: "Alex"
                    expires_on: "2026-12-31"
                    state: "%s"
                    description: "Controls Stream-CV enforcement."
                    allowed_states:
                      - "off"
                      - "shadow"
                      - "on"
                """.formatted(state, streamCvState));
        return new FeatureFlagCatalog(catalogPath.toString());
    }

    private Setup setup(String state) throws Exception {
        return setup(state, "off");
    }

    private Setup setup(String state, String streamCvState) throws Exception {
        FeatureFlagCatalog catalog = catalogFor(state, streamCvState);
        SettlementEvidenceBuilder evidenceBuilder = Mockito.mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = Mockito.mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = Mockito.mock(SettlementShadowAuditService.class);
        BetSettlementPolicyCatalog policyCatalog = Mockito.mock(BetSettlementPolicyCatalog.class);
        when(policyCatalog.currentPolicy()).thenReturn(SettlementPolicy.defaults());
        PaperTradeBetRepository betRepository = Mockito.mock(PaperTradeBetRepository.class);
        ClosingLineLookupService closingLineLookupService = Mockito.mock(ClosingLineLookupService.class);
        when(betRepository.save(any(PaperTradeBet.class))).thenAnswer(inv -> inv.getArgument(0));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        ScoreTruthPrimaryService service = new ScoreTruthPrimaryService(
                catalog, evidenceBuilder, engine, auditService, policyCatalog, betRepository,
                closingLineLookupService, meterRegistry, CLOCK);
        return new Setup(service, evidenceBuilder, engine, auditService, policyCatalog, betRepository,
                closingLineLookupService, meterRegistry);
    }

    private record Setup(ScoreTruthPrimaryService service,
                          SettlementEvidenceBuilder evidenceBuilder,
                          SettlementEngine engine,
                          SettlementShadowAuditService auditService,
                          BetSettlementPolicyCatalog policyCatalog,
                          PaperTradeBetRepository betRepository,
                          ClosingLineLookupService closingLineLookupService,
                          SimpleMeterRegistry meterRegistry) { }
}
