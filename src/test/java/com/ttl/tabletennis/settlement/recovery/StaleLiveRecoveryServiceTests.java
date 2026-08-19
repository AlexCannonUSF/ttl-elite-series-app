package com.ttl.tabletennis.settlement.recovery;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.scrape.FeedClient;
import com.ttl.tabletennis.scrape.FeedHealth;
import com.ttl.tabletennis.scrape.IngestEvent;
import com.ttl.tabletennis.scrape.IngestionBus;
import com.ttl.tabletennis.scrape.MirrorObservationPayload;
import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.service.SettlementShadowAuditService;
import com.ttl.tabletennis.settlement.CoverageState;
import com.ttl.tabletennis.settlement.IdentityLock;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEngine;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementEvidenceBuilder;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.TrackedEventId;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StaleLiveRecoveryServiceTests {

    private static final Instant NOW = Instant.parse("2026-04-19T20:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    @TempDir
    Path tempDir;

    @Test
    void doesNothingWhenScoreTruthFlagIsOff() throws Exception {
        TrackedMatchObservationRepository trackedRepository = mock(TrackedMatchObservationRepository.class);
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        StaleLiveRecoveryService service = service(
                "off",
                trackedRepository,
                builder,
                engine,
                auditService,
                ingestionBus,
                new SimpleMeterRegistry(),
                List.of(new StaticMirrorFeedClient(List.of(mirrorEvent())))
        );

        StaleLiveRecoveryService.RecoveryBatch batch = service.recoverCandidates(List.of(staleOpenScoreBet(201L)));

        assertFalse(service.active());
        assertEquals(StaleLiveRecoveryService.RecoveryBatch.empty(), batch);
        verifyNoInteractions(trackedRepository, builder, engine, auditService, ingestionBus);
    }

    @Test
    void detectsStaleOpenScoreAndNormalizesFetchedMirrorPayloadBeforeDecision() throws Exception {
        TrackedMatchObservationRepository trackedRepository = mock(TrackedMatchObservationRepository.class);
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementEngine engine = mock(SettlementEngine.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        StaticMirrorFeedClient sofaScore = new StaticMirrorFeedClient(List.of(mirrorEvent()));
        StaleLiveRecoveryService service = service(
                "advisory",
                trackedRepository,
                builder,
                engine,
                auditService,
                ingestionBus,
                meterRegistry,
                List.of(sofaScore)
        );
        PaperTradeBet bet = staleOpenScoreBet(202L);
        SettlementEvidence evidence = evidence(202L);
        Settle settle = new Settle(evidence, 10L, SettlementReason.STALE_ESCALATION_RECOVERED, 0.93);

        when(trackedRepository.findTopByBetIdOrderByObservedAtDescIdDesc(202L)).thenReturn(Optional.empty());
        when(trackedRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(builder.buildForBet(bet)).thenReturn(Optional.of(evidence));
        when(engine.decide(evidence, com.ttl.tabletennis.settlement.SettlementPolicy.defaults())).thenReturn(settle);

        StaleLiveRecoveryService.RecoveryBatch batch = service.recoverCandidates(List.of(bet));

        assertEquals(1, batch.scanned());
        assertEquals(1, batch.detected());
        assertEquals(5, batch.fetchAttempts());
        assertEquals(1, batch.observationsRecorded());
        assertEquals(1, batch.decisionsRecorded());
        assertEquals(PaperTradeBet.STATUS_OPEN, bet.getStatus());
        assertEquals(1, sofaScore.pullCount());

        @SuppressWarnings("unchecked")
        var observationCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(trackedRepository).saveAll(observationCaptor.capture());
        @SuppressWarnings("unchecked")
        List<TrackedMatchObservation> saved = (List<TrackedMatchObservation>) observationCaptor.getValue();
        assertEquals(1, saved.size());
        TrackedMatchObservation observation = saved.get(0);
        assertEquals(202L, observation.getBetId());
        assertEquals(SourceId.SOFASCORE.id(), observation.getSource());
        assertEquals("STALE_LIVE_RECOVERY", observation.getSourceKind());
        assertEquals("3:1", observation.getLiveScore());
        assertEquals("11:8", observation.getScoreDetail());
        assertTrue(observation.isMatchCompleted());

        @SuppressWarnings("unchecked")
        var eventCaptor = org.mockito.ArgumentCaptor.forClass(IngestEvent.class);
        verify(ingestionBus).publish(eventCaptor.capture());
        IngestEvent<?> detected = eventCaptor.getValue();
        assertEquals(SourceId.INTERNAL_DB, detected.source());
        assertEquals(StaleLiveRecoveryService.TOPIC_STALE_LIVE_DETECTED, detected.topic());
        assertTrue(detected.payload() instanceof StaleLiveRecoveryService.StaleLiveDetectedPayload);

        verify(auditService).recordAttempt(bet, evidence, settle);
        assertEquals(1.0, meterRegistry.get("ttl.score_truth.stale_live.decisions")
                .tag("decision", "SETTLE")
                .counter()
                .count());
    }

    @Test
    void throttlesRepeatedSourceAttemptsForFiveMinutes() throws Exception {
        TrackedMatchObservationRepository trackedRepository = mock(TrackedMatchObservationRepository.class);
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        StaticMirrorFeedClient sofaScore = new StaticMirrorFeedClient(List.of());
        StaleLiveRecoveryService service = service(
                "advisory",
                trackedRepository,
                builder,
                mock(SettlementEngine.class),
                mock(SettlementShadowAuditService.class),
                mock(IngestionBus.class),
                new SimpleMeterRegistry(),
                List.of(sofaScore)
        );
        PaperTradeBet bet = staleOpenScoreBet(203L);

        when(trackedRepository.findTopByBetIdOrderByObservedAtDescIdDesc(203L)).thenReturn(Optional.empty());
        when(builder.buildForBet(bet)).thenReturn(Optional.empty());

        StaleLiveRecoveryService.RecoveryBatch first = service.recoverCandidates(List.of(bet));
        StaleLiveRecoveryService.RecoveryBatch second = service.recoverCandidates(List.of(bet));

        assertEquals(5, first.fetchAttempts());
        assertEquals(0, second.fetchAttempts());
        assertEquals(1, sofaScore.pullCount());
    }

    @Test
    void schedulesOfficialRecoveryWhenAllEscalationSourcesProduceNoEvidence() throws Exception {
        TrackedMatchObservationRepository trackedRepository = mock(TrackedMatchObservationRepository.class);
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        StaleLiveRecoveryService service = service(
                "advisory",
                trackedRepository,
                builder,
                mock(SettlementEngine.class),
                mock(SettlementShadowAuditService.class),
                mock(IngestionBus.class),
                new SimpleMeterRegistry(),
                List.of()
        );
        PaperTradeBet bet = staleOpenScoreBet(204L);

        when(trackedRepository.findTopByBetIdOrderByObservedAtDescIdDesc(204L)).thenReturn(Optional.empty());
        when(builder.buildForBet(bet)).thenReturn(Optional.empty());

        StaleLiveRecoveryService.RecoveryBatch batch = service.recoverCandidates(List.of(bet));

        assertEquals(5, batch.fetchAttempts());
        assertEquals(0, batch.observationsRecorded());
        assertEquals(1, batch.officialJobsScheduled());
    }

    @Test
    void escalatesToExplicitReviewWhenOfficialWindowExpiresWithoutEvidence() throws Exception {
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        TrackedMatchObservationRepository trackedRepository = mock(TrackedMatchObservationRepository.class);
        SettlementEvidenceBuilder builder = mock(SettlementEvidenceBuilder.class);
        SettlementShadowAuditService auditService = mock(SettlementShadowAuditService.class);
        StaleLiveRecoveryService service = service(
                "primary",
                betRepository,
                trackedRepository,
                builder,
                mock(SettlementEngine.class),
                auditService,
                mock(IngestionBus.class),
                new SimpleMeterRegistry(),
                List.of()
        );
        PaperTradeBet bet = staleOpenScoreBet(205L);
        bet.setPlacedAt(LocalDateTime.ofInstant(NOW.minus(Duration.ofMinutes(181)), ZoneId.systemDefault()));
        when(trackedRepository.findTopByBetIdOrderByObservedAtDescIdDesc(205L)).thenReturn(Optional.empty());
        when(builder.buildForBet(bet)).thenReturn(Optional.empty());

        StaleLiveRecoveryService.RecoveryBatch batch = service.recoverCandidates(List.of(bet));

        assertEquals(1, batch.decisionsRecorded());
        assertEquals(0, batch.officialJobsScheduled());
        assertEquals(StaleLiveRecoveryService.STALE_OPEN_REVIEW_REQUIRED, bet.getPendingEvidenceReason());
        assertTrue(bet.getPendingEvidenceNote().contains("Official recovery window expired"));
        verify(betRepository).save(bet);
        verify(auditService).recordNoEvidenceAttempt(
                bet,
                StaleLiveRecoveryService.STALE_OPEN_REVIEW_REQUIRED
        );
    }

    private StaleLiveRecoveryService service(String state,
                                             TrackedMatchObservationRepository trackedRepository,
                                             SettlementEvidenceBuilder builder,
                                             SettlementEngine engine,
                                             SettlementShadowAuditService auditService,
                                             IngestionBus ingestionBus,
                                             SimpleMeterRegistry meterRegistry,
                                             List<FeedClient<?>> feedClients) throws Exception {
        return service(
                state,
                mock(PaperTradeBetRepository.class),
                trackedRepository,
                builder,
                engine,
                auditService,
                ingestionBus,
                meterRegistry,
                feedClients
        );
    }

    private StaleLiveRecoveryService service(String state,
                                             PaperTradeBetRepository betRepository,
                                             TrackedMatchObservationRepository trackedRepository,
                                             SettlementEvidenceBuilder builder,
                                             SettlementEngine engine,
                                             SettlementShadowAuditService auditService,
                                             IngestionBus ingestionBus,
                                             SimpleMeterRegistry meterRegistry,
                                             List<FeedClient<?>> feedClients) throws Exception {
        return new StaleLiveRecoveryService(
                featureCatalogWithScoreTruth(state),
                betRepository,
                trackedRepository,
                builder,
                engine,
                auditService,
                ingestionBus,
                meterRegistry,
                feedClients,
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

    private PaperTradeBet staleOpenScoreBet(Long id) {
        PaperTradeBet bet = new PaperTradeBet();
        setId(bet, id);
        bet.setSessionId(88L);
        bet.setStatus(PaperTradeBet.STATUS_OPEN);
        bet.setEventKey("event-" + id);
        bet.setDedupeKey("dedupe-" + id);
        bet.setExternalEventId("sofa-123");
        bet.setPlayer1Id(10L);
        bet.setPlayer2Id(20L);
        bet.setPlayer1Name("Adam Staniczek");
        bet.setPlayer2Name("Dariusz Maszczynski");
        bet.setEventName("Adam Staniczek vs Dariusz Maszczynski");
        bet.setCompetitionName("TT Cup");
        bet.setLastObservedScore("2:1");
        bet.setLastObservedPhase("LIVE_LATE");
        bet.setLastObservedAt(LocalDateTime.ofInstant(NOW.minus(Duration.ofMinutes(15)), ZoneId.systemDefault()));
        bet.setPlacedAt(LocalDateTime.ofInstant(NOW.minus(Duration.ofMinutes(30)), ZoneId.systemDefault()));
        return bet;
    }

    private IngestEvent<MirrorObservationPayload> mirrorEvent() {
        MirrorObservationPayload payload = new MirrorObservationPayload(
                "event-202",
                "sofa-123",
                "Adam Staniczek",
                "Dariusz Maszczynski",
                "TT Cup",
                "FINISHED",
                3,
                1,
                11,
                8,
                "",
                true,
                "{\"event\":202}"
        );
        return new IngestEvent<>(
                SourceId.SOFASCORE,
                "score.observed",
                NOW,
                0.91,
                "corr-sofa",
                "",
                payload
        );
    }

    private SettlementEvidence evidence(long betId) {
        LiveObservation liveObservation = new LiveObservation(
                SourceId.HR_TGT,
                NOW,
                0.93,
                MatchPhase.FINISHED,
                new ScoreState(3, 1, 11, 8, ""),
                "raw-live",
                true,
                "sofa-123",
                "event-" + betId,
                true,
                true
        );
        return new SettlementEvidence(
                betId,
                new TrackedEventId("event-" + betId),
                new IdentityLock(10L, 20L, NOW.minus(Duration.ofMinutes(30)), Duration.ofMinutes(90), "sofa-123", "event-" + betId),
                List.of(liveObservation),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CoverageState.FULL,
                List.of(),
                0.1,
                0.93,
                NOW
        );
    }

    private void setId(PaperTradeBet bet, Long id) {
        try {
            Field field = PaperTradeBet.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(bet, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class StaticMirrorFeedClient implements FeedClient<MirrorObservationPayload> {
        private final List<IngestEvent<MirrorObservationPayload>> events;
        private final List<PullContext> contexts = new ArrayList<>();

        private StaticMirrorFeedClient(List<IngestEvent<MirrorObservationPayload>> events) {
            this.events = events;
        }

        @Override
        public SourceId source() {
            return SourceId.SOFASCORE;
        }

        @Override
        public List<IngestEvent<MirrorObservationPayload>> pullOnce(PullContext ctx) {
            contexts.add(ctx);
            return events;
        }

        @Override
        public FeedHealth currentHealth() {
            return FeedHealth.idle(SourceId.SOFASCORE);
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.SCORES, Capability.RESULTS);
        }

        private int pullCount() {
            return contexts.size();
        }
    }
}
