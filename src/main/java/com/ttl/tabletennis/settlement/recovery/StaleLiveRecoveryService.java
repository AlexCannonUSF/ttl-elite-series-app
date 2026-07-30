package com.ttl.tabletennis.settlement.recovery;

import com.ttl.tabletennis.config.FeatureFlagCatalog;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.scrape.FeedClient;
import com.ttl.tabletennis.scrape.IngestEvent;
import com.ttl.tabletennis.scrape.IngestionBus;
import com.ttl.tabletennis.scrape.MirrorObservationPayload;
import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TtSeriesScraper;
import com.ttl.tabletennis.service.SettlementShadowAuditService;
import com.ttl.tabletennis.settlement.BetSettlementPolicyCatalog;
import com.ttl.tabletennis.settlement.Decision;
import com.ttl.tabletennis.settlement.Escalate;
import com.ttl.tabletennis.settlement.HoldOpen;
import com.ttl.tabletennis.settlement.ManualReview;
import com.ttl.tabletennis.settlement.Settle;
import com.ttl.tabletennis.settlement.SettlementEngine;
import com.ttl.tabletennis.settlement.SettlementEvidence;
import com.ttl.tabletennis.settlement.SettlementEvidenceBuilder;
import com.ttl.tabletennis.settlement.SettlementPolicy;
import com.ttl.tabletennis.settlement.SettlementReason;
import com.ttl.tabletennis.settlement.VoidDecision;
import com.ttl.tabletennis.util.CorrelationContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StaleLiveRecoveryService {

    public static final String TOPIC_STALE_LIVE_DETECTED = "stale.live.detected";
    public static final String TRACKING_MARKET_CLOSED_SCORE_TRACKED = "MARKET_CLOSED_SCORE_TRACKED";
    public static final String TRACKING_OPEN_SCORE_VISIBLE = "OPEN_SCORE_VISIBLE";
    public static final String STALE_OPEN_REVIEW_REQUIRED = "STALE_OPEN_REVIEW_REQUIRED";

    private static final Logger log = LoggerFactory.getLogger(StaleLiveRecoveryService.class);
    private static final Duration RETRY_COOLDOWN = Duration.ofMinutes(5);
    private static final Duration OFFICIAL_RECOVERY_DELAY = Duration.ofMinutes(15);
    private static final int DEFAULT_MAX_BETS_PER_TICK = 50;

    private final FeatureFlagCatalog featureFlagCatalog;
    private final PaperTradeBetRepository betRepository;
    private final TrackedMatchObservationRepository trackedMatchObservationRepository;
    private final SettlementEvidenceBuilder settlementEvidenceBuilder;
    private final SettlementEngine settlementEngine;
    private final SettlementShadowAuditService settlementShadowAuditService;
    private final IngestionBus ingestionBus;
    private final MeterRegistry meterRegistry;
    private final Map<SourceId, FeedClient<?>> feedClients;
    private final BetSettlementPolicyCatalog betSettlementPolicyCatalog;
    private final Clock clock;
    private final Map<AttemptKey, Instant> lastAttemptByBetAndSource = new ConcurrentHashMap<>();
    private final Map<Long, Instant> officialRecoveryDueByBet = new ConcurrentHashMap<>();

    @Value("${ttl.score-truth.stale-live-recovery.maxBetsPerTick:50}")
    private int maxBetsPerTick = DEFAULT_MAX_BETS_PER_TICK;

    @Autowired
    public StaleLiveRecoveryService(FeatureFlagCatalog featureFlagCatalog,
                                    PaperTradeBetRepository betRepository,
                                    TrackedMatchObservationRepository trackedMatchObservationRepository,
                                    SettlementEvidenceBuilder settlementEvidenceBuilder,
                                    SettlementEngine settlementEngine,
                                    SettlementShadowAuditService settlementShadowAuditService,
                                    IngestionBus ingestionBus,
                                    MeterRegistry meterRegistry,
                                    List<FeedClient<?>> feedClients,
                                    BetSettlementPolicyCatalog betSettlementPolicyCatalog) {
        this(
                featureFlagCatalog,
                betRepository,
                trackedMatchObservationRepository,
                settlementEvidenceBuilder,
                settlementEngine,
                settlementShadowAuditService,
                ingestionBus,
                meterRegistry,
                feedClients,
                betSettlementPolicyCatalog,
                Clock.systemDefaultZone()
        );
    }

    StaleLiveRecoveryService(FeatureFlagCatalog featureFlagCatalog,
                             PaperTradeBetRepository betRepository,
                             TrackedMatchObservationRepository trackedMatchObservationRepository,
                             SettlementEvidenceBuilder settlementEvidenceBuilder,
                             SettlementEngine settlementEngine,
                             SettlementShadowAuditService settlementShadowAuditService,
                             IngestionBus ingestionBus,
                             MeterRegistry meterRegistry,
                             List<FeedClient<?>> feedClients,
                             BetSettlementPolicyCatalog betSettlementPolicyCatalog,
                             Clock clock) {
        this.featureFlagCatalog = featureFlagCatalog;
        this.betRepository = betRepository;
        this.trackedMatchObservationRepository = trackedMatchObservationRepository;
        this.settlementEvidenceBuilder = settlementEvidenceBuilder;
        this.settlementEngine = settlementEngine;
        this.settlementShadowAuditService = settlementShadowAuditService;
        this.ingestionBus = ingestionBus;
        this.meterRegistry = meterRegistry;
        this.feedClients = indexFeedClients(feedClients);
        this.betSettlementPolicyCatalog = betSettlementPolicyCatalog;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    StaleLiveRecoveryService(FeatureFlagCatalog featureFlagCatalog,
                             PaperTradeBetRepository betRepository,
                             TrackedMatchObservationRepository trackedMatchObservationRepository,
                             SettlementEvidenceBuilder settlementEvidenceBuilder,
                             SettlementEngine settlementEngine,
                             SettlementShadowAuditService settlementShadowAuditService,
                             IngestionBus ingestionBus,
                             MeterRegistry meterRegistry,
                             List<FeedClient<?>> feedClients,
                             Clock clock) {
        this(
                featureFlagCatalog,
                betRepository,
                trackedMatchObservationRepository,
                settlementEvidenceBuilder,
                settlementEngine,
                settlementShadowAuditService,
                ingestionBus,
                meterRegistry,
                feedClients,
                null,
                clock
        );
    }

    public boolean active() {
        String state = featureFlagCatalog == null
                ? "off"
                : featureFlagCatalog.stateOf(FeatureFlagCatalog.SCORE_TRUTH_FLAG);
        return "advisory".equals(state) || "primary".equals(state);
    }

    @Scheduled(fixedDelayString = "${ttl.score-truth.stale-live-recovery.fixedDelayMs:30000}")
    @Transactional
    public void recoverStaleLiveBets() {
        if (!active() || betRepository == null) {
            return;
        }
        int limit = Math.max(1, maxBetsPerTick <= 0 ? DEFAULT_MAX_BETS_PER_TICK : maxBetsPerTick);
        List<PaperTradeBet> openBets = betRepository.findByStatusOrderByPlacedAtAsc(
                PaperTradeBet.STATUS_OPEN,
                PageRequest.of(0, limit)
        );
        recoverCandidates(openBets);
    }

    public RecoveryBatch recoverCandidates(List<PaperTradeBet> bets) {
        if (!active() || bets == null || bets.isEmpty()) {
            return RecoveryBatch.empty();
        }

        int scanned = 0;
        int detected = 0;
        int fetchAttempts = 0;
        int observationsRecorded = 0;
        int decisionsRecorded = 0;
        int officialJobsScheduled = 0;

        for (PaperTradeBet bet : bets) {
            scanned++;
            try {
                Optional<RecoveryCandidate> candidate = recoveryCandidate(bet);
                if (candidate.isEmpty()) {
                    continue;
                }
                detected++;
                RecoveryResult result = recoverCandidate(candidate.get());
                fetchAttempts += result.fetchAttempts();
                observationsRecorded += result.observationsRecorded();
                if (result.decisionRecorded()) {
                    decisionsRecorded++;
                }
                if (result.officialJobScheduled()) {
                    officialJobsScheduled++;
                }
            } catch (RuntimeException ex) {
                log.warn("[score-truth-recovery] unable to recover stale live bet {}", bet == null ? null : bet.getId(), ex);
                increment("ttl.score_truth.stale_live.errors", "stage", "candidate");
            }
        }

        return new RecoveryBatch(scanned, detected, fetchAttempts, observationsRecorded, decisionsRecorded, officialJobsScheduled);
    }

    private RecoveryResult recoverCandidate(RecoveryCandidate candidate) {
        PaperTradeBet bet = candidate.bet();
        Instant now = clock.instant();
        String correlationId = "stale-live-" + bet.getId() + "-" + now.toEpochMilli();
        int fetchAttempts = 0;
        int observationsRecorded = 0;
        boolean decisionRecorded = false;
        boolean officialJobScheduled = false;

        try (CorrelationContext.Scope ignored = CorrelationContext.openIfAbsent(correlationId)) {
            publishDetected(candidate, correlationId, now);
            increment("ttl.score_truth.stale_live.detected", "state", candidate.trackingState());

            SettlementPolicy policy = currentPolicy();
            for (SourceId sourceId : policy.staleLiveRecovery().escalationOrder()) {
                FetchResult fetchResult = fetchSourceIfDue(bet, sourceId, correlationId, now);
                fetchAttempts += fetchResult.attempted() ? 1 : 0;
                observationsRecorded += fetchResult.observationsRecorded();
            }

            if (officialRecoveryDue(bet.getId(), now)) {
                for (FeedClient<?> feedClient : resultCapableFeedClients()) {
                    FetchResult fetchResult = fetchSourceIfDue(bet, feedClient.source(), correlationId, now);
                    fetchAttempts += fetchResult.attempted() ? 1 : 0;
                    observationsRecorded += fetchResult.observationsRecorded();
                }
                officialRecoveryDueByBet.remove(bet.getId());
            }

            Optional<SettlementEvidence> evidence = settlementEvidenceBuilder == null
                    ? Optional.empty()
                    : settlementEvidenceBuilder.buildForBet(bet);
            if (evidence.isPresent() && settlementEngine != null) {
                Decision decision = settlementEngine.decide(evidence.get(), policy);
                if (officialWindowExpired(evidence.get(), policy)
                        && (decision instanceof HoldOpen || decision instanceof Escalate)) {
                    decision = new ManualReview(
                            evidence.get(),
                            SettlementReason.MANUAL_REVIEW_AWAITING,
                            evidence.get().contradictions()
                    );
                }
                if (recordRecoverableDecision(bet, evidence.get(), decision, policy)) {
                    decisionRecorded = true;
                }
            } else if (officialWindowExpired(bet, now, policy)) {
                decisionRecorded = escalateNoEvidence(bet, now);
            } else if (allEscalationSourcesCoolingDown(bet.getId(), policy.staleLiveRecovery().escalationOrder(), now)) {
                officialJobScheduled = scheduleOfficialRecoveryIfAbsent(bet.getId(), now);
            }
        }

        return new RecoveryResult(fetchAttempts, observationsRecorded, decisionRecorded, officialJobScheduled);
    }

    private Optional<RecoveryCandidate> recoveryCandidate(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null || !PaperTradeBet.STATUS_OPEN.equalsIgnoreCase(safeText(bet.getStatus()))) {
            return Optional.empty();
        }

        Optional<TrackedMatchObservation> latestTracked = latestTrackedObservation(bet);
        String trackingState = trackingState(bet, latestTracked);
        if (!TRACKING_MARKET_CLOSED_SCORE_TRACKED.equals(trackingState)
                && !TRACKING_OPEN_SCORE_VISIBLE.equals(trackingState)) {
            return Optional.empty();
        }

        Optional<Instant> lastObservedAt = latestObservedAt(bet, latestTracked);
        if (lastObservedAt.isEmpty()) {
            return Optional.empty();
        }

        int thresholdMinutes = Math.max(0, currentPolicy().staleLiveRecovery().enterAfterMinutesDark());
        long darkMinutes = Duration.between(lastObservedAt.get(), clock.instant()).toMinutes();
        if (darkMinutes < thresholdMinutes) {
            return Optional.empty();
        }

        return Optional.of(new RecoveryCandidate(bet, trackingState, lastObservedAt.get(), (int) Math.min(Integer.MAX_VALUE, darkMinutes)));
    }

    private Optional<TrackedMatchObservation> latestTrackedObservation(PaperTradeBet bet) {
        if (trackedMatchObservationRepository == null || bet == null || bet.getId() == null) {
            return Optional.empty();
        }
        return trackedMatchObservationRepository.findTopByBetIdOrderByObservedAtDesc(bet.getId());
    }

    private Optional<Instant> latestObservedAt(PaperTradeBet bet, Optional<TrackedMatchObservation> latestTracked) {
        Optional<Instant> trackedAt = latestTracked
                .map(TrackedMatchObservation::getObservedAt)
                .map(this::toInstant);
        Optional<Instant> betAt = Optional.ofNullable(bet.getLastObservedAt()).map(this::toInstant);
        return List.of(trackedAt, betAt).stream()
                .flatMap(Optional::stream)
                .max(Comparator.naturalOrder());
    }

    private String trackingState(PaperTradeBet bet, Optional<TrackedMatchObservation> latestTracked) {
        if (bet.isTrackedAfterClose()) {
            boolean trackedHasScoreContext = latestTracked
                    .filter(observation -> hasText(observation.getLiveScore()) || hasMeaningfulPhase(observation.getMatchPhase()))
                    .isPresent();
            if (trackedHasScoreContext || hasText(bet.getLastObservedScore()) || hasMeaningfulPhase(bet.getLastObservedPhase())) {
                return TRACKING_MARKET_CLOSED_SCORE_TRACKED;
            }
        }
        if (hasText(bet.getLastObservedScore()) || hasMeaningfulPhase(bet.getLastObservedPhase())) {
            return TRACKING_OPEN_SCORE_VISIBLE;
        }
        return "OPEN_PENDING_SCORE";
    }

    private void publishDetected(RecoveryCandidate candidate, String correlationId, Instant now) {
        if (ingestionBus == null) {
            return;
        }
        SettlementPolicy policy = currentPolicy();
        ingestionBus.publish(new IngestEvent<>(
                SourceId.INTERNAL_DB,
                TOPIC_STALE_LIVE_DETECTED,
                now,
                1.0,
                correlationId,
                "",
                new StaleLiveDetectedPayload(
                        candidate.bet().getId(),
                        safeText(candidate.bet().getEventKey()),
                        candidate.trackingState(),
                        candidate.lastObservedAt(),
                        candidate.darkMinutes(),
                        policy.staleLiveRecovery().escalationOrder()
                )
        ));
    }

    private FetchResult fetchSourceIfDue(PaperTradeBet bet, SourceId sourceId, String correlationId, Instant now) {
        if (sourceId == null || bet == null || bet.getId() == null) {
            return FetchResult.skipped();
        }
        AttemptKey key = new AttemptKey(bet.getId(), sourceId);
        Instant previousAttempt = lastAttemptByBetAndSource.get(key);
        if (previousAttempt != null && Duration.between(previousAttempt, now).compareTo(RETRY_COOLDOWN) < 0) {
            return FetchResult.skipped();
        }
        lastAttemptByBetAndSource.put(key, now);

        FeedClient<?> feedClient = feedClientFor(sourceId).orElse(null);
        if (feedClient == null) {
            increment("ttl.score_truth.stale_live.fetch.attempts", "source", sourceId.id(), "result", "missing_client");
            return FetchResult.attempted(0);
        }

        try {
            List<? extends IngestEvent<?>> events = pull(feedClient, bet, sourceId, correlationId, now);
            int recorded = recordTrackedObservations(bet, sourceId, events);
            incrementBy("ttl.score_truth.stale_live.fetch.events", recorded, "source", sourceId.id());
            increment("ttl.score_truth.stale_live.fetch.attempts", "source", sourceId.id(), "result", "success");
            return FetchResult.attempted(recorded);
        } catch (RuntimeException ex) {
            log.warn("[score-truth-recovery] targeted fetch failed for bet {} source {}", bet.getId(), sourceId, ex);
            increment("ttl.score_truth.stale_live.fetch.attempts", "source", sourceId.id(), "result", "error");
            return FetchResult.attempted(0);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<? extends IngestEvent<?>> pull(FeedClient<?> feedClient,
                                                PaperTradeBet bet,
                                                SourceId requestedSource,
                                                String correlationId,
                                                Instant now) {
        FeedClient rawClient = feedClient;
        return rawClient.pullOnce(new FeedClient.PullContext(now, correlationId, attributesFor(bet, requestedSource)));
    }

    private Map<String, String> attributesFor(PaperTradeBet bet, SourceId requestedSource) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "betId", bet.getId() == null ? "" : String.valueOf(bet.getId()));
        put(attributes, "sessionId", bet.getSessionId() == null ? "" : String.valueOf(bet.getSessionId()));
        put(attributes, "trackedEventId", firstText(bet.getEventKey(), bet.getDedupeKey(), "bet-" + bet.getId()));
        put(attributes, "eventKey", bet.getEventKey());
        put(attributes, "dedupeKey", bet.getDedupeKey());
        put(attributes, "externalEventId", firstText(bet.getLockedExternalEventId(), bet.getExternalEventId()));
        put(attributes, "bookerEventId", firstText(bet.getLockedExternalEventId(), bet.getExternalEventId()));
        put(attributes, "sourceFeedEventId", firstText(bet.getLockedSourceFeedEventId(), bet.getLastSourceFeedEventId()));
        put(attributes, "fixtureId", fixtureIdFor(bet, requestedSource));
        put(attributes, "player1Name", bet.getPlayer1Name());
        put(attributes, "player2Name", bet.getPlayer2Name());
        put(attributes, "competitionName", bet.getCompetitionName());
        put(attributes, "startTimeIso", bet.getStartTimeIso());
        put(attributes, "limit", "10");
        return Map.copyOf(attributes);
    }

    private String fixtureIdFor(PaperTradeBet bet, SourceId requestedSource) {
        if (requestedSource == SourceId.SOFASCORE || requestedSource == SourceId.AISCORE || requestedSource == SourceId.BETSAPI) {
            return firstText(bet.getLockedExternalEventId(), bet.getExternalEventId(), bet.getLockedSourceFeedEventId(), bet.getLastSourceFeedEventId());
        }
        return "";
    }

    private int recordTrackedObservations(PaperTradeBet bet,
                                          SourceId requestedSource,
                                          List<? extends IngestEvent<?>> events) {
        if (events == null || events.isEmpty() || trackedMatchObservationRepository == null) {
            return 0;
        }
        List<TrackedMatchObservation> observations = new ArrayList<>();
        for (IngestEvent<?> event : events) {
            toTrackedObservation(bet, requestedSource, event).ifPresent(observations::add);
        }
        if (observations.isEmpty()) {
            return 0;
        }
        trackedMatchObservationRepository.saveAll(observations);
        return observations.size();
    }

    private Optional<TrackedMatchObservation> toTrackedObservation(PaperTradeBet bet,
                                                                   SourceId requestedSource,
                                                                   IngestEvent<?> event) {
        if (event == null || event.payload() == null) {
            return Optional.empty();
        }
        Object payload = event.payload();
        SourceId observationSource = sourceForObservation(requestedSource, event.source());
        if (payload instanceof MirrorObservationPayload mirrorPayload) {
            return Optional.of(mirrorPayloadObservation(bet, observationSource, event, mirrorPayload));
        }
        if (payload instanceof MatchOdds matchOdds && matchesBet(bet, matchOdds)) {
            return Optional.of(matchOddsObservation(bet, observationSource, event, matchOdds));
        }
        if (payload instanceof TtSeriesScraper.OfficialLedgerMatch ledgerMatch && matchesBet(bet, ledgerMatch)) {
            return Optional.of(officialLedgerObservation(bet, observationSource, event, ledgerMatch));
        }
        return Optional.empty();
    }

    private TrackedMatchObservation mirrorPayloadObservation(PaperTradeBet bet,
                                                            SourceId source,
                                                            IngestEvent<?> event,
                                                            MirrorObservationPayload payload) {
        TrackedMatchObservation observation = baseObservation(bet, source, event);
        observation.setExternalEventId(firstText(payload.mirrorEventId(), bet.getExternalEventId()));
        observation.setSourceFeedEventId(payload.mirrorEventId());
        observation.setLive(!payload.completionSignal());
        observation.setResulted(payload.completionSignal());
        observation.setMatchCompleted(payload.completionSignal());
        observation.setEventName(bet.getEventName());
        observation.setCompetitionName(firstText(payload.competitionName(), bet.getCompetitionName()));
        observation.setPlayer1Name(firstText(payload.player1Name(), bet.getPlayer1Name()));
        observation.setPlayer2Name(firstText(payload.player2Name(), bet.getPlayer2Name()));
        observation.setLiveScore(scorePair(payload.gamesP1(), payload.gamesP2()));
        observation.setScoreDetail(scorePair(payload.pointsP1(), payload.pointsP2()));
        observation.setMatchPhase(firstText(payload.phase(), payload.completionSignal() ? "FINISHED" : ""));
        return observation;
    }

    private TrackedMatchObservation matchOddsObservation(PaperTradeBet bet,
                                                        SourceId source,
                                                        IngestEvent<?> event,
                                                        MatchOdds matchOdds) {
        TrackedMatchObservation observation = baseObservation(bet, source, event);
        observation.setExternalEventId(firstText(matchOdds.getExternalEventId(), bet.getExternalEventId()));
        observation.setSourceFeedCode(matchOdds.getSourceFeedCode());
        observation.setSourceFeedEventId(matchOdds.getSourceFeedEventId());
        observation.setLive(matchOdds.isLive());
        observation.setDisplayed(matchOdds.isDisplayed());
        observation.setResulted(matchOdds.isResulted());
        observation.setMatchCompleted(matchOdds.isMatchCompleted());
        observation.setEventName(firstText(matchOdds.getEventName(), bet.getEventName()));
        observation.setCompetitionName(firstText(matchOdds.getCompetitionName(), bet.getCompetitionName()));
        observation.setStartTimeIso(firstText(matchOdds.getStartTimeIso(), bet.getStartTimeIso()));
        observation.setPlayer1Name(firstText(matchOdds.getPlayerA(), bet.getPlayer1Name()));
        observation.setPlayer2Name(firstText(matchOdds.getPlayerB(), bet.getPlayer2Name()));
        observation.setLiveScore(matchOdds.getLiveScore());
        observation.setScoreDetail(matchOdds.getScoreDetail());
        observation.setMatchPhase(matchOdds.getMatchPhase());
        return observation;
    }

    private TrackedMatchObservation officialLedgerObservation(PaperTradeBet bet,
                                                             SourceId source,
                                                             IngestEvent<?> event,
                                                             TtSeriesScraper.OfficialLedgerMatch ledgerMatch) {
        TrackedMatchObservation observation = baseObservation(bet, source, event);
        observation.setExternalEventId(firstText(bet.getExternalEventId(), bet.getEventKey()));
        observation.setSourceFeedEventId(truncate(ledgerMatch.sourceUrl(), 128));
        observation.setLive(false);
        observation.setDisplayed(true);
        observation.setResulted(true);
        observation.setMatchCompleted(true);
        observation.setEventName(bet.getEventName());
        observation.setCompetitionName(bet.getCompetitionName());
        observation.setPlayer1Name(firstText(ledgerMatch.player1Raw(), bet.getPlayer1Name()));
        observation.setPlayer2Name(firstText(ledgerMatch.player2Raw(), bet.getPlayer2Name()));
        observation.setLiveScore(ledgerMatch.result());
        observation.setScoreDetail(ledgerMatch.result());
        observation.setMatchPhase("FINISHED");
        return observation;
    }

    private TrackedMatchObservation baseObservation(PaperTradeBet bet,
                                                   SourceId source,
                                                   IngestEvent<?> event) {
        TrackedMatchObservation observation = new TrackedMatchObservation();
        observation.setSessionId(bet.getSessionId());
        observation.setBetId(bet.getId());
        observation.setEventKey(firstText(bet.getEventKey(), bet.getDedupeKey(), "bet-" + bet.getId()));
        observation.setDedupeKey(bet.getDedupeKey());
        observation.setSource(source.id());
        observation.setSourceKind("STALE_LIVE_RECOVERY");
        observation.setSourceConfidence(event.confidence());
        observation.setTrackedAfterClose(bet.isTrackedAfterClose());
        observation.setPlayer1Id(bet.getPlayer1Id());
        observation.setPlayer2Id(bet.getPlayer2Id());
        observation.setObservedAt(LocalDateTime.ofInstant(event.observedAt(), ZoneId.systemDefault()));
        observation.setCorrelationId(event.correlationId());
        return observation;
    }

    private boolean recordRecoverableDecision(PaperTradeBet bet, SettlementEvidence evidence, Decision decision, SettlementPolicy policy) {
        if (decision == null || settlementShadowAuditService == null) {
            return false;
        }
        if (decision instanceof VoidDecision && !officialWindowExpired(evidence, policy)) {
            return false;
        }
        if (decision instanceof ManualReview) {
            markReviewRequired(bet, "Trusted settlement evidence remains ambiguous", clock.instant());
        }
        SettlementShadowAuditService.AuditWriteResult write =
                settlementShadowAuditService.recordAttempt(bet, evidence, decision);
        increment("ttl.score_truth.stale_live.decisions", "decision", decisionType(decision));
        return write == null || write.recorded();
    }

    private boolean officialWindowExpired(SettlementEvidence evidence, SettlementPolicy policy) {
        long minutesSincePlacement = Duration.between(evidence.identityLock().placementTime(), evidence.bundleAsOf()).toMinutes();
        return minutesSincePlacement >= policy.staleLiveRecovery().officialWindowMinutes();
    }

    private boolean officialWindowExpired(PaperTradeBet bet, Instant now, SettlementPolicy policy) {
        if (bet == null || bet.getPlacedAt() == null || now == null) {
            return false;
        }
        long minutesSincePlacement = Duration.between(toInstant(bet.getPlacedAt()), now).toMinutes();
        return minutesSincePlacement >= policy.staleLiveRecovery().officialWindowMinutes();
    }

    private boolean escalateNoEvidence(PaperTradeBet bet, Instant now) {
        if (settlementShadowAuditService == null) {
            return false;
        }
        markReviewRequired(bet, "Official recovery window expired without settlement evidence", now);
        SettlementShadowAuditService.AuditWriteResult write =
                settlementShadowAuditService.recordNoEvidenceAttempt(bet, STALE_OPEN_REVIEW_REQUIRED);
        increment("ttl.score_truth.stale_live.decisions", "decision", "MANUAL_REVIEW");
        return write == null || write.recorded();
    }

    private void markReviewRequired(PaperTradeBet bet, String note, Instant now) {
        if (bet == null) {
            return;
        }
        bet.setPendingEvidenceReason(STALE_OPEN_REVIEW_REQUIRED);
        bet.setPendingEvidenceNote(note);
        bet.setPendingEvidenceNextPollAt(null);
        bet.setPendingEvidenceUpdatedAt(LocalDateTime.ofInstant(now, ZoneId.systemDefault()));
        if (betRepository != null) {
            betRepository.save(bet);
        }
    }

    private SettlementPolicy currentPolicy() {
        return betSettlementPolicyCatalog == null ? SettlementPolicy.defaults() : betSettlementPolicyCatalog.currentPolicy();
    }

    private boolean scheduleOfficialRecoveryIfAbsent(Long betId, Instant now) {
        if (betId == null || officialRecoveryDueByBet.containsKey(betId)) {
            return false;
        }
        officialRecoveryDueByBet.put(betId, now.plus(OFFICIAL_RECOVERY_DELAY));
        increment("ttl.score_truth.stale_live.official_jobs.scheduled");
        return true;
    }

    private boolean officialRecoveryDue(Long betId, Instant now) {
        Instant dueAt = officialRecoveryDueByBet.get(betId);
        return dueAt != null && !now.isBefore(dueAt);
    }

    private boolean allEscalationSourcesCoolingDown(Long betId, List<SourceId> sources, Instant now) {
        if (betId == null || sources == null || sources.isEmpty()) {
            return false;
        }
        return sources.stream().allMatch(sourceId -> {
            Instant attemptedAt = lastAttemptByBetAndSource.get(new AttemptKey(betId, sourceId));
            return attemptedAt != null && Duration.between(attemptedAt, now).compareTo(RETRY_COOLDOWN) < 0;
        });
    }

    private Optional<FeedClient<?>> feedClientFor(SourceId sourceId) {
        FeedClient<?> direct = feedClients.get(sourceId);
        if (direct != null) {
            return Optional.of(direct);
        }
        if (sourceId == SourceId.HR_TGT) {
            return Optional.ofNullable(feedClients.get(SourceId.HR_MKT));
        }
        return Optional.empty();
    }

    private List<FeedClient<?>> resultCapableFeedClients() {
        return feedClients.values().stream()
                .filter(Objects::nonNull)
                .filter(client -> client.capabilities().contains(FeedClient.Capability.RESULTS))
                .toList();
    }

    private boolean matchesBet(PaperTradeBet bet, MatchOdds row) {
        if (row == null) {
            return false;
        }
        if (textEqualsAny(row.getExternalEventId(), bet.getExternalEventId(), bet.getLockedExternalEventId())) {
            return true;
        }
        if (textEqualsAny(row.getSourceFeedEventId(), bet.getLastSourceFeedEventId(), bet.getLockedSourceFeedEventId())) {
            return true;
        }
        return samePlayerSet(bet.getPlayer1Name(), bet.getPlayer2Name(), row.getPlayerA(), row.getPlayerB());
    }

    private boolean matchesBet(PaperTradeBet bet, TtSeriesScraper.OfficialLedgerMatch ledgerMatch) {
        if (ledgerMatch == null) {
            return false;
        }
        return samePlayerSet(bet.getPlayer1Name(), bet.getPlayer2Name(), ledgerMatch.player1Raw(), ledgerMatch.player2Raw());
    }

    private boolean samePlayerSet(String leftA, String leftB, String rightA, String rightB) {
        String a = normalize(leftA);
        String b = normalize(leftB);
        String c = normalize(rightA);
        String d = normalize(rightB);
        if (!hasText(a) || !hasText(b) || !hasText(c) || !hasText(d)) {
            return false;
        }
        return (a.equals(c) && b.equals(d)) || (a.equals(d) && b.equals(c));
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private boolean textEqualsAny(String value, String... candidates) {
        if (!StringUtils.hasText(value) || candidates == null) {
            return false;
        }
        String normalized = value.trim();
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && normalized.equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private SourceId sourceForObservation(SourceId requestedSource, SourceId eventSource) {
        if (requestedSource == SourceId.HR_TGT && eventSource == SourceId.HR_MKT) {
            return SourceId.HR_TGT;
        }
        return eventSource == null ? requestedSource : eventSource;
    }

    private Map<SourceId, FeedClient<?>> indexFeedClients(List<FeedClient<?>> feedClients) {
        if (feedClients == null || feedClients.isEmpty()) {
            return Map.of();
        }
        Map<SourceId, FeedClient<?>> bySource = new EnumMap<>(SourceId.class);
        for (FeedClient<?> feedClient : feedClients) {
            if (feedClient != null && feedClient.source() != null) {
                bySource.putIfAbsent(feedClient.source(), feedClient);
            }
        }
        return Map.copyOf(bySource);
    }

    private Instant toInstant(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private boolean hasMeaningfulPhase(String phase) {
        if (!StringUtils.hasText(phase)) {
            return false;
        }
        String normalized = phase.trim().toUpperCase(Locale.ROOT);
        return !normalized.isBlank() && !"UPCOMING".equals(normalized);
    }

    private String scorePair(Integer left, Integer right) {
        if (left == null || right == null) {
            return "";
        }
        return left + ":" + right;
    }

    private String decisionType(Decision decision) {
        if (decision instanceof Settle) {
            return "SETTLE";
        }
        if (decision instanceof VoidDecision) {
            return "VOID";
        }
        return decision == null ? "UNKNOWN" : decision.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private void increment(String metric, String... tags) {
        incrementBy(metric, 1.0, tags);
    }

    private void incrementBy(String metric, double amount, String... tags) {
        if (meterRegistry == null || amount <= 0.0) {
            return;
        }
        meterRegistry.counter(metric, tags).increment(amount);
    }

    private void put(Map<String, String> attributes, String key, String value) {
        if (StringUtils.hasText(value)) {
            attributes.put(key, value.trim());
        }
    }

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    public record StaleLiveDetectedPayload(long betId,
                                           String eventKey,
                                           String trackingState,
                                           Instant lastObservedAt,
                                           int darkMinutes,
                                           List<SourceId> escalationOrder) {
    }

    public record RecoveryBatch(int scanned,
                                int detected,
                                int fetchAttempts,
                                int observationsRecorded,
                                int decisionsRecorded,
                                int officialJobsScheduled) {

        public static RecoveryBatch empty() {
            return new RecoveryBatch(0, 0, 0, 0, 0, 0);
        }
    }

    private record RecoveryCandidate(PaperTradeBet bet,
                                     String trackingState,
                                     Instant lastObservedAt,
                                     int darkMinutes) {
    }

    private record RecoveryResult(int fetchAttempts,
                                  int observationsRecorded,
                                  boolean decisionRecorded,
                                  boolean officialJobScheduled) {
    }

    private record FetchResult(boolean attempted,
                               int observationsRecorded) {

        static FetchResult skipped() {
            return new FetchResult(false, 0);
        }

        static FetchResult attempted(int observationsRecorded) {
            return new FetchResult(true, observationsRecorded);
        }
    }

    private record AttemptKey(Long betId, SourceId sourceId) {
    }
}
