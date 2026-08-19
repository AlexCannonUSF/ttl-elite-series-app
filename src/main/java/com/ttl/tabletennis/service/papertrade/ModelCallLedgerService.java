package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.ModelCallViewerReview;
import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.LiveRunAnalyticsDto;
import com.ttl.tabletennis.dto.ModelCallApprovalRequest;
import com.ttl.tabletennis.dto.ModelCallMonitorDto;
import com.ttl.tabletennis.dto.ModelCallResultDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.dto.ModelCallTrackingDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.ModelCallViewerReviewRepository;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.service.ModelArtifactIdentityService;
import com.ttl.tabletennis.service.ResearchOpportunityLedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.clamp;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.isFinishedPhase;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.parseStartDateTime;
import static com.ttl.tabletennis.service.papertrade.PaperTradingHelpers.safeText;

/**
 * Captures and grades the model's outright winner call independently of the
 * value/staking decision. A suggested bet can be an underdog while the model's
 * most likely winner is the favourite, so reusing the bet side here would
 * produce a misleading accuracy number.
 */
@Service
public class ModelCallLedgerService {

    private static final Logger log = LoggerFactory.getLogger(ModelCallLedgerService.class);

    private static final double PROBABILITY_TIE_EPSILON = 0.000_001;
    private static final int MAX_RESULTS = 200;
    private static final Duration LIVE_OBSERVATION_STALE_AFTER = Duration.ofMinutes(3);

    private final PaperTradeModelCallRepository callRepository;
    private final PaperTradeSessionRepository sessionRepository;
    private final MatchRepository matchRepository;
    private final TrackedMatchObservationRepository observationRepository;
    private final ModelCallViewerReviewRepository reviewRepository;
    private final PaperTradeDecisionSampleRepository decisionSampleRepository;
    private ModelArtifactIdentityService modelArtifactIdentityService;
    private ResearchOpportunityLedgerService researchOpportunityLedgerService;

    @Value("${ttl.paper.modelIdentity.strictPinning:true}")
    private boolean strictModelPinning;

    private volatile long watermarkCacheExpiresAtNanos;
    private volatile long cachedMatchIdHighWatermark;
    private final Map<Long, CachedOutcome> outcomeCache = new ConcurrentHashMap<>();

    public ModelCallLedgerService(PaperTradeModelCallRepository callRepository,
                                  PaperTradeSessionRepository sessionRepository,
                                  MatchRepository matchRepository,
                                  TrackedMatchObservationRepository observationRepository,
                                  ModelCallViewerReviewRepository reviewRepository,
                                  PaperTradeDecisionSampleRepository decisionSampleRepository) {
        this.callRepository = callRepository;
        this.sessionRepository = sessionRepository;
        this.matchRepository = matchRepository;
        this.observationRepository = observationRepository;
        this.reviewRepository = reviewRepository;
        this.decisionSampleRepository = decisionSampleRepository;
    }

    @Autowired(required = false)
    void setModelArtifactIdentityService(ModelArtifactIdentityService modelArtifactIdentityService) {
        this.modelArtifactIdentityService = modelArtifactIdentityService;
    }

    @Autowired(required = false)
    void setResearchOpportunityLedgerService(ResearchOpportunityLedgerService researchOpportunityLedgerService) {
        this.researchOpportunityLedgerService = researchOpportunityLedgerService;
    }

    /**
     * Upsert one canonical call for the event. Prematch predictor snapshots may
     * refresh until play begins; live snapshots never overwrite a prematch call,
     * and a live-only event freezes the first predictor snapshot observed. The
     * operational paper-trade decision is intentionally allowed to advance while
     * the event is live so the ledger reflects the gate currently being evaluated.
     */
    @Transactional
    public void recordCall(Long sessionId,
                           String strategy,
                           String modelVersion,
                           LiveOddsRecommendationDto row,
                           String eventKey,
                           String decisionStatus,
                           String decisionReason) {
        recordCall(sessionId, strategy, modelVersion, row, eventKey, decisionStatus, decisionReason, null);
    }

    @Transactional
    public void recordCall(Long sessionId,
                           String strategy,
                           String modelVersion,
                           LiveOddsRecommendationDto row,
                           String eventKey,
                           String decisionStatus,
                           String decisionReason,
                           PaperTradeDecisionSample decisionSample) {
        if (sessionId == null || row == null || !StringUtils.hasText(eventKey)) {
            return;
        }

        String normalizedEventKey = eventKey.trim();
        Optional<PaperTradeSession> owningSession = sessionRepository.findById(sessionId);
        if (owningSession
                .map(PaperTradeSession::getStatus)
                .filter(PaperTradeSession.STATUS_CLOSED::equalsIgnoreCase)
                .isPresent()) {
            log.error("[model-call] rejected write to closed run session={} event={}",
                    sessionId, normalizedEventKey);
            return;
        }
        Optional<PaperTradeModelCall> existing = callRepository.findBySessionIdAndEventKey(sessionId, normalizedEventKey);
        boolean alreadyFinished = row.matchCompleted() || row.resulted() || isFinishedPhase(row.matchPhase());
        if (existing.isEmpty() && alreadyFinished) {
            return;
        }
        PaperTradeModelCall call = existing.orElseGet(PaperTradeModelCall::new);
        boolean isNew = existing.isEmpty();
        boolean incomingPrematch = !row.live();
        boolean storedPrematch = PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE.equals(call.getCaptureType());
        boolean refreshSnapshot = !alreadyFinished && (isNew || incomingPrematch);

        String normalizedModelVersion = safeText(modelVersion, "").trim();
        ModelArtifactIdentityService.ModelArtifactIdentity artifactIdentity = modelArtifactIdentityService == null
                ? null
                : modelArtifactIdentityService.resolve(normalizedModelVersion);
        if (strictModelPinning) {
            if (artifactIdentity == null || !artifactIdentity.complete()) {
                log.warn("[model-call] rejected unresolvable/generic artifact session={} event={} model={}",
                        sessionId, normalizedEventKey, normalizedModelVersion);
                return;
            }
            if (owningSession.isEmpty()
                    || !StringUtils.hasText(owningSession.get().getPolicyVersion())
                    || !StringUtils.hasText(owningSession.get().getCodeRevision())) {
                log.warn("[model-call] rejected incomplete session identity session={} event={}",
                        sessionId, normalizedEventKey);
                return;
            }
            if (decisionSample == null
                    || finite(decisionSample.getSelectionScore()) == null
                    || finite(decisionSample.getSignalQuality()) == null) {
                log.warn("[model-call] rejected incomplete required telemetry session={} event={}",
                        sessionId, normalizedEventKey);
                return;
            }
            String pinned = owningSession.map(PaperTradeSession::getEffectiveModelVersion).orElse(null);
            if (StringUtils.hasText(pinned)
                    && !ModelArtifactIdentityService.isGenericSelector(pinned)
                    && !pinned.equals(normalizedModelVersion)) {
                log.warn("[model-call] rejected artifact drift session={} event={} pinned={} incoming={}",
                        sessionId, normalizedEventKey, pinned, normalizedModelVersion);
                return;
            }
        }

        if (isNew) {
            call.setSessionId(sessionId);
            call.setEventKey(normalizedEventKey);
            call.setMatchIdHighWatermark(currentMatchIdHighWatermark());
        }

        if (refreshSnapshot) {
            call.setEventName(safeText(row.eventName(), playerPairLabel(row)));
            call.setCompetitionName(safeText(row.competitionName(), "Table Tennis"));
            call.setSource(safeText(row.source(), "UNKNOWN"));
            call.setStrategy(safeText(strategy, "CONSERVATIVE"));
            call.setModelVersion(safeText(normalizedModelVersion, "ENSEMBLE"));
            applyArtifactIdentity(call, artifactIdentity, owningSession.orElse(null));
            updateSessionModelIdentity(sessionId, call.getModelVersion(), artifactIdentity);
            call.setCaptureType(incomingPrematch
                    ? PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE
                    : PaperTradeModelCall.CAPTURE_LIVE_FIRST);
            call.setCapturedAt(LocalDateTime.now());
            call.setStartTimeIso(row.startTimeIso());
            call.setExternalEventId(firstText(
                    row.externalEventId(),
                    MatchKeyBuilder.extractExternalEventId(row.source())));
            call.setSourceFeedEventId(row.sourceFeedEventId());
            call.setPlayer1Id(row.player1Id());
            call.setPlayer1Name(safeText(row.player1Name(), "Player 1"));
            call.setPlayer2Id(row.player2Id());
            call.setPlayer2Name(safeText(row.player2Name(), "Player 2"));
            applyWinnerCall(call, row);
            call.setRecommendedAtCapture(row.recommended());
            call.setDecisionStatus(safeText(decisionStatus, "SKIPPED"));
            call.setDecisionReason(safeText(decisionReason, "UNKNOWN"));
            applyPredictorSnapshot(call, row, decisionSample);
        } else if (!storedPrematch && row.live()) {
            // First-live predictor reads are deliberately frozen. Operational
            // decision metadata is refreshed below without changing the call.
        }

        boolean placedNow = "PLACED".equalsIgnoreCase(decisionStatus);
        if (row.live() && !alreadyFinished && (!call.isHasPaperPick() || placedNow)) {
            // A frozen pregame call may initially say EVENT_NOT_UPCOMING. Once
            // the event is positively live, keep its original prediction for
            // unbiased grading but expose the real live gate (or placement) to
            // users and operators. Never downgrade an already placed call.
            call.setDecisionStatus(safeText(decisionStatus, "SKIPPED"));
            call.setDecisionReason(safeText(decisionReason, "UNKNOWN"));
        }
        if (placedNow) {
            call.setHasPaperPick(true);
        }
        if (call.getTopTrigger() == null && decisionSample != null) {
            applyPredictorSnapshot(call, row, decisionSample);
        }
        call = callRepository.save(call);
        if (researchOpportunityLedgerService != null && owningSession.isPresent()) {
            researchOpportunityLedgerService.capture(owningSession.get(), call, decisionSample);
        }
    }

    private void updateSessionModelIdentity(
            Long sessionId,
            String effectiveVersion,
            ModelArtifactIdentityService.ModelArtifactIdentity identity) {
        if (sessionId == null || !StringUtils.hasText(effectiveVersion)) {
            return;
        }
        sessionRepository.findById(sessionId).ifPresent(session -> {
            String normalized = effectiveVersion.trim();
            if (!normalized.equals(session.getEffectiveModelVersion())) {
                session.setEffectiveModelVersion(normalized);
                session.setEffectiveModelFamily(inferModelFamily(normalized));
            }
            if (identity != null && identity.complete()) {
                session.setEffectiveArtifactChecksum(identity.artifactChecksum());
                session.setFeatureSchemaChecksum(identity.featureSchemaChecksum());
                session.setCalibrationId(identity.calibrationId());
            }
            sessionRepository.save(session);
        });
    }

    private static void applyArtifactIdentity(
            PaperTradeModelCall call,
            ModelArtifactIdentityService.ModelArtifactIdentity identity,
            PaperTradeSession session) {
        if (identity != null && identity.complete()) {
            call.setArtifactChecksum(identity.artifactChecksum());
            call.setFeatureSchemaChecksum(identity.featureSchemaChecksum());
            call.setCalibrationId(identity.calibrationId());
        }
        if (session != null) {
            call.setPolicyId(session.getPolicyVersion());
            call.setCodeRevision(session.getCodeRevision());
        }
    }

    private static String inferModelFamily(String version) {
        String normalized = version == null ? "" : version.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("ENSEMBLE")) return "ENSEMBLE";
        if (normalized.contains("LOGISTIC")) return "LOGISTIC";
        if (normalized.contains("GBT")) return "GBT_LIKE";
        if (normalized.contains("RF")) return "RF_LIKE";
        if (normalized.contains("BASELINE")) return "BASELINE";
        return "UNKNOWN";
    }

    @Transactional(readOnly = true)
    public ModelCallScorecardDto scorecard(int limit) {
        int take = clamp(limit, 5, MAX_RESULTS);
        Optional<PaperTradeSession> activeSession =
                sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
        if (activeSession.isEmpty()) {
            return emptyScorecard(take);
        }

        return scorecard(activeSession.get(), take);
    }

    /**
     * Historical equivalent of {@link #scorecard(int)}. The run id is
     * explicit so research pages never silently fall back to the current
     * active session when an operator is inspecting an older run.
     */
    @Transactional(readOnly = true)
    public ModelCallScorecardDto scorecard(long sessionId, int limit) {
        PaperTradeSession session = requireSession(sessionId);
        return scorecard(session, clamp(limit, 5, MAX_RESULTS));
    }

    private ModelCallScorecardDto scorecard(PaperTradeSession session, int take) {
        List<PaperTradeModelCall> calls = callRepository.findBySessionIdOrderByCapturedAtDesc(session.getId());
        Map<Long, ModelCallViewerReview> latestReviews = latestReviews(session.getId());
        Map<String, TrackedMatchObservation> latestObservations = latestObservations(session.getId());
        ArchiveIndex archiveIndex = archiveIndex(calls);
        List<ResolvedCall> resolved = calls.stream()
                .map(call -> new ResolvedCall(call,
                        resolveOutcome(call, latestObservations.get(call.getEventKey()), archiveIndex).orElse(null)))
                .toList();

        // Event keys can change when a feed promotes prematch into live. Never
        // count one archived result twice: prefer a prematch snapshot, then the
        // earliest capture, for any duplicate resolved match id.
        Map<String, ResolvedCall> byOutcome = new LinkedHashMap<>();
        for (ResolvedCall item : resolved) {
            if (item.outcome() == null) {
                continue;
            }
            String outcomeKey = item.outcome().matchId() == null
                    ? "event:" + item.call().getEventKey()
                    : "match:" + item.outcome().matchId();
            byOutcome.merge(outcomeKey, item, ModelCallLedgerService::preferredCall);
        }

        List<ResolvedCall> settled = new ArrayList<>(byOutcome.values());
        settled.sort(Comparator
                .comparing((ResolvedCall item) -> item.outcome().completedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.outcome().matchId(), Comparator.nullsLast(Comparator.reverseOrder())));

        int awaiting = (int) resolved.stream().filter(item -> item.outcome() == null).count();
        int correct = 0;
        int incorrect = 0;
        int noLean = 0;
        int pregameSettled = 0;
        int pregameCorrect = 0;
        int liveSettled = 0;
        int liveCorrect = 0;
        double confidenceSum = 0.0;
        int confidenceCount = 0;
        double brierSum = 0.0;
        int brierCount = 0;
        int flatStakeSettled = 0;
        int flatStakeWins = 0;
        int flatStakeLosses = 0;
        double flatStakeReturned = 0.0;
        int viewerGraded = 0;
        int viewerCorrect = 0;
        int viewerIncorrect = 0;
        int viewerApprovedPending = 0;
        int viewerConflicts = 0;
        List<ModelCallResultDto> results = new ArrayList<>();

        for (ResolvedCall item : resolved) {
            PaperTradeModelCall call = item.call();
            ModelCallViewerReview review = latestReviews.get(call.getId());
            ResolvedOutcome systemOutcome = item.outcome();
            if (review != null && systemOutcome == null) viewerApprovedPending++;
            if (review != null && systemOutcome != null
                    && !review.getWinnerPlayerId().equals(systemOutcome.winnerPlayerId())) viewerConflicts++;
            if (review != null && call.getPredictedWinnerPlayerId() != null) {
                viewerGraded++;
                if (call.getPredictedWinnerPlayerId().equals(review.getWinnerPlayerId())) viewerCorrect++;
                else viewerIncorrect++;
            }
        }

        for (ResolvedCall item : settled) {
            PaperTradeModelCall call = item.call();
            ResolvedOutcome outcome = item.outcome();
            boolean hasLean = call.getPredictedWinnerPlayerId() != null && call.getModelProbability() != null;
            boolean isCorrect = hasLean && call.getPredictedWinnerPlayerId().equals(outcome.winnerPlayerId());
            if (!hasLean) {
                noLean++;
            } else if (isCorrect) {
                correct++;
            } else {
                incorrect++;
            }

            boolean pregame = PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE.equals(call.getCaptureType());
            if (hasLean && pregame) {
                pregameSettled++;
                if (isCorrect) pregameCorrect++;
            } else if (hasLean) {
                liveSettled++;
                if (isCorrect) liveCorrect++;
            }
            if (hasLean) {
                double probability = clamp(call.getModelProbability(), 0.0, 1.0);
                confidenceSum += probability;
                confidenceCount++;
                double target = isCorrect ? 1.0 : 0.0;
                brierSum += Math.pow(probability - target, 2);
                brierCount++;
                Double decimalPrice = americanDecimal(call.getHardRockAmericanOdds());
                if (decimalPrice != null) {
                    flatStakeSettled++;
                    if (isCorrect) {
                        flatStakeWins++;
                        flatStakeReturned += decimalPrice;
                    } else {
                        flatStakeLosses++;
                    }
                }
            }
            if (results.size() < take) {
                results.add(toResult(call, outcome, hasLean, isCorrect));
            }
        }

        int graded = correct + incorrect;
        double flatStakeWagered = flatStakeSettled;
        double flatStakeNetProfit = flatStakeReturned - flatStakeWagered;
        return new ModelCallScorecardDto(
                session.getId(),
                session.getLabel(),
                LocalDateTime.now().toString(),
                calls.size(),
                awaiting,
                settled.size(),
                correct,
                incorrect,
                noLean,
                percentage(correct, graded),
                pregameSettled,
                pregameCorrect,
                percentage(pregameCorrect, pregameSettled),
                liveSettled,
                liveCorrect,
                percentage(liveCorrect, liveSettled),
                percentage(confidenceSum, confidenceCount),
                brierCount == 0 ? null : round4(brierSum / brierCount),
                flatStakeSettled,
                flatStakeWins,
                flatStakeLosses,
                round2(flatStakeWagered),
                round2(flatStakeReturned),
                round2(flatStakeNetProfit),
                percentage(flatStakeNetProfit, flatStakeSettled),
                viewerGraded,
                viewerCorrect,
                viewerIncorrect,
                percentage(viewerCorrect, viewerGraded),
                viewerApprovedPending,
                viewerConflicts,
                List.copyOf(results)
        );
    }

    /**
     * Session-scoped evidence over every frozen winner call. Unlike the
     * paper-bet learning audit, this intentionally includes resolved passes so
     * an operator can evaluate the model and each gate before enough official
     * picks exist for adaptive learning.
     */
    @Transactional(readOnly = true)
    public LiveRunAnalyticsDto analytics(int limit) {
        int take = clamp(limit, 20, 500);
        Optional<PaperTradeSession> activeSession =
                sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
        if (activeSession.isEmpty()) return emptyAnalytics();

        return analytics(activeSession.get(), take);
    }

    /** Complete all-call analytics for one immutable historical run. */
    @Transactional(readOnly = true)
    public LiveRunAnalyticsDto analytics(long sessionId, int limit) {
        return analytics(requireSession(sessionId), clamp(limit, 20, 500));
    }

    private LiveRunAnalyticsDto analytics(PaperTradeSession session, int take) {
        List<PaperTradeModelCall> calls = callRepository.findBySessionIdOrderByCapturedAtDesc(session.getId());
        Map<String, TrackedMatchObservation> observations = latestObservations(session.getId());
        Map<String, PaperTradeDecisionSample> decisions = latestDecisionSamples(session.getId());
        ArchiveIndex archiveIndex = archiveIndex(calls);
        List<ResolvedCall> resolved = calls.stream()
                .map(call -> new ResolvedCall(call,
                        resolveOutcome(call, observations.get(call.getEventKey()), archiveIndex).orElse(null)))
                .toList();

        Map<String, ResolvedCall> byOutcome = new LinkedHashMap<>();
        for (ResolvedCall item : resolved) {
            if (item.outcome() == null) continue;
            String key = item.outcome().matchId() == null
                    ? "event:" + item.call().getEventKey()
                    : "match:" + item.outcome().matchId();
            byOutcome.merge(key, item, ModelCallLedgerService::preferredCall);
        }

        List<ResolvedCall> settled = byOutcome.values().stream()
                .filter(item -> item.call().getPredictedWinnerPlayerId() != null)
                .filter(item -> item.call().getModelProbability() != null)
                .sorted(Comparator
                        .comparing((ResolvedCall item) -> item.outcome().completedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.call().getCapturedAt(), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        int correct = 0;
        int paperPicks = 0;
        double probabilitySum = 0.0;
        double brier = 0.0;
        double returned = 0.0;
        double cumulativeProfit = 0.0;
        int pricedWins = 0;
        int pricedLosses = 0;
        List<Double> profits = new ArrayList<>();
        List<LiveRunAnalyticsDto.TrendPointDto> trend = new ArrayList<>();
        Map<String, SegmentAccumulator> triggerSegments = new LinkedHashMap<>();
        Map<String, SegmentAccumulator> decisionSegments = new LinkedHashMap<>();
        Map<String, FactorAccumulator> factorSegments = new LinkedHashMap<>();

        for (ResolvedCall item : settled) {
            PaperTradeModelCall call = item.call();
            boolean won = call.getPredictedWinnerPlayerId().equals(item.outcome().winnerPlayerId());
            if (won) correct++;
            if (call.isHasPaperPick()) paperPicks++;
            double probability = clamp(call.getModelProbability(), 0.0, 1.0);
            probabilitySum += probability;
            brier += Math.pow(probability - (won ? 1.0 : 0.0), 2);

            Double decimal = americanDecimal(call.getHardRockAmericanOdds());
            double profit = decimal == null ? 0.0 : (won ? decimal - 1.0 : -1.0);
            if (decimal != null) {
                profits.add(profit);
                if (won) {
                    pricedWins++;
                    returned += decimal;
                } else {
                    pricedLosses++;
                }
                cumulativeProfit += profit;
            }

            int sample = trend.size() + 1;
            trend.add(new LiveRunAnalyticsDto.TrendPointDto(
                    sample,
                    iso(item.outcome().completedAt()),
                    call.getId() == null ? -1L : call.getId(),
                    safeText(call.getEventName(), playerPairLabel(call)),
                    won,
                    percentage(correct, sample),
                    round2(cumulativeProfit),
                    percentage(cumulativeProfit, profits.size())
            ));

            PredictorSnapshot predictor = predictorSnapshot(call, decisions.get(call.getEventKey()));
            String trigger = safeText(predictor.topTrigger(), "UNKNOWN");
            String decision = safeText(call.getDecisionReason(), "UNKNOWN");
            triggerSegments.computeIfAbsent(trigger, ignored -> new SegmentAccumulator())
                    .add(won, probability, profit, decimal != null, predictor.triggerReliability());
            decisionSegments.computeIfAbsent(decision, ignored -> new SegmentAccumulator())
                    .add(won, probability, profit, decimal != null, predictor.overallReliability());

            boolean flip = call.getPredictedWinnerPlayerId().equals(call.getPlayer2Id());
            for (FactorValue factor : parseFactors(predictor.featureContributions())) {
                double aligned = flip ? -factor.value() : factor.value();
                factorSegments.computeIfAbsent(factor.name(), ignored -> new FactorAccumulator())
                        .add(aligned, won);
            }
        }

        int n = settled.size();
        int losses = Math.max(0, n - correct);
        ConfidenceInterval accuracyInterval = wilson(correct, n);
        ConfidenceInterval roiInterval = meanInterval(profits);
        int readinessTarget = 100;
        double net = returned - profits.size();
        List<LiveRunAnalyticsDto.SegmentPerformanceDto> triggers = triggerSegments.entrySet().stream()
                .map(entry -> entry.getValue().toDto(entry.getKey()))
                .sorted(Comparator.comparingInt(LiveRunAnalyticsDto.SegmentPerformanceDto::sampleSize).reversed()
                        .thenComparing(LiveRunAnalyticsDto.SegmentPerformanceDto::segment))
                .toList();
        List<LiveRunAnalyticsDto.SegmentPerformanceDto> reasons = decisionSegments.entrySet().stream()
                .map(entry -> entry.getValue().toDto(entry.getKey()))
                .sorted(Comparator.comparingInt(LiveRunAnalyticsDto.SegmentPerformanceDto::sampleSize).reversed()
                        .thenComparing(LiveRunAnalyticsDto.SegmentPerformanceDto::segment))
                .toList();
        List<LiveRunAnalyticsDto.FactorPerformanceDto> factors = factorSegments.entrySet().stream()
                .map(entry -> entry.getValue().toDto(entry.getKey()))
                .sorted(Comparator.comparingInt(LiveRunAnalyticsDto.FactorPerformanceDto::sampleSize).reversed()
                        .thenComparing(LiveRunAnalyticsDto.FactorPerformanceDto::meanAbsoluteContribution,
                                Comparator.reverseOrder()))
                .toList();

        return new LiveRunAnalyticsDto(
                session.getId(),
                session.getLabel(),
                LocalDateTime.now().toString(),
                evidenceLabel(n),
                readinessTarget,
                round2(Math.min(100.0, n * 100.0 / readinessTarget)),
                calls.size(),
                n,
                (int) resolved.stream().filter(item -> item.outcome() == null).count(),
                correct,
                losses,
                percentage(correct, n),
                accuracyInterval.low(),
                accuracyInterval.high(),
                percentage(probabilitySum, n),
                n == 0 ? null : round4(brier / n),
                profits.size(),
                pricedWins,
                pricedLosses,
                round2(profits.size()),
                round2(returned),
                round2(net),
                percentage(net, profits.size()),
                roiInterval.low(),
                roiInterval.high(),
                positiveMeanConfidence(profits),
                paperPicks,
                Math.max(0, n - paperPicks),
                List.copyOf(trend.stream().skip(Math.max(0, trend.size() - take)).toList()),
                List.copyOf(triggers),
                List.copyOf(reasons),
                List.copyOf(factors)
        );
    }

    /**
     * Returns every model call in the active session, including unresolved
     * matches, with a human-readable explanation of its current pipeline stage.
     */
    @Transactional(readOnly = true)
    public ModelCallMonitorDto monitor(int limit) {
        int take = clamp(limit, 5, MAX_RESULTS);
        Optional<PaperTradeSession> activeSession =
                sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
        if (activeSession.isEmpty()) {
            return new ModelCallMonitorDto(null, "No active simulation", LocalDateTime.now().toString(),
                    0, 0, 0, 0, 0, 0, 0, List.of());
        }

        return monitor(activeSession.get(), take);
    }

    /** Pipeline state and calls for one explicit run, active or closed. */
    @Transactional(readOnly = true)
    public ModelCallMonitorDto monitor(long sessionId, int limit) {
        return monitor(requireSession(sessionId), clamp(limit, 5, MAX_RESULTS));
    }

    /**
     * Complete immutable ledger for offline research jobs. This intentionally
     * bypasses the interactive response cap; it must not be exposed as an
     * unpaged browser endpoint.
     */
    @Transactional(readOnly = true)
    public ModelCallMonitorDto monitorAllForResearch(long sessionId) {
        return monitor(requireSession(sessionId), Integer.MAX_VALUE);
    }

    private ModelCallMonitorDto monitor(PaperTradeSession session, int take) {
        Map<Long, ModelCallViewerReview> reviews = latestReviews(session.getId());
        Map<String, TrackedMatchObservation> observations = latestObservations(session.getId());
        Map<String, PaperTradeDecisionSample> decisions = latestDecisionSamples(session.getId());
        List<PaperTradeModelCall> allCalls = callRepository.findBySessionIdOrderByCapturedAtDesc(session.getId());
        ArchiveIndex archiveIndex = archiveIndex(allCalls);
        List<ModelCallTrackingDto> trackedCalls = allCalls
                .stream()
                .map(call -> tracking(call, reviews.get(call.getId()), observations.get(call.getEventKey()),
                        archiveIndex, decisions.get(call.getEventKey())))
                .toList();
        List<ModelCallTrackingDto> calls = trackedCalls.stream().limit(take).toList();
        return new ModelCallMonitorDto(
                session.getId(),
                session.getLabel(),
                LocalDateTime.now().toString(),
                allCalls.size(),
                countStage(trackedCalls, "SCHEDULED") + countStage(trackedCalls, "WAITING_FOR_FEED"),
                countStage(trackedCalls, "LIVE_MONITORING"),
                countStage(trackedCalls, "SETTLEMENT_REVIEW"),
                countStage(trackedCalls, "VIEWER_APPROVED"),
                countStage(trackedCalls, "SYSTEM_CONFIRMED"),
                countStage(trackedCalls, "RESULT_CONFLICT"),
                List.copyOf(calls));
    }

    private PaperTradeSession requireSession(long sessionId) {
        if (sessionId <= 0) {
            throw new IllegalArgumentException("Run id must be positive");
        }
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Run " + sessionId + " was not found"));
    }

    @Transactional(readOnly = true)
    public ModelCallTrackingDto tracking(long callId) {
        PaperTradeModelCall call = callRepository.findById(callId)
                .orElseThrow(() -> new ResourceNotFoundException("Model call " + callId + " was not found"));
        ModelCallViewerReview review = reviewRepository.findByCallIdOrderByCreatedAtDesc(callId)
                .stream().findFirst().orElse(null);
        PaperTradeDecisionSample decision = decisionSampleRepository
                .findTopBySessionIdAndEventKeyOrderByCreatedAtDescIdDesc(call.getSessionId(), call.getEventKey())
                .orElse(null);
        return tracking(call, review, latestObservation(call).orElse(null), null, decision);
    }

    /**
     * Adds an append-only viewer grade. It never settles a paper bet, mutates a
     * match result, or becomes a model-training label.
     */
    @Transactional
    public ModelCallTrackingDto approve(long callId, ModelCallApprovalRequest request) {
        if (request == null || request.winnerPlayerId() == null) {
            throw new IllegalArgumentException("Choose the winner you observed");
        }
        PaperTradeModelCall call = callRepository.findById(callId)
                .orElseThrow(() -> new ResourceNotFoundException("Model call " + callId + " was not found"));
        TrackedMatchObservation latest = latestObservation(call).orElse(null);
        if (resolveOutcome(call, latest).isPresent()) {
            throw new IllegalStateException("This match already has a trusted system result and no longer needs viewer approval");
        }
        boolean player1 = request.winnerPlayerId().equals(call.getPlayer1Id());
        boolean player2 = request.winnerPlayerId().equals(call.getPlayer2Id());
        if (!player1 && !player2) {
            throw new IllegalArgumentException("The selected winner is not a player in this match");
        }
        ModelCallViewerReview review = new ModelCallViewerReview();
        review.setCallId(call.getId());
        review.setSessionId(call.getSessionId());
        review.setEventKey(call.getEventKey());
        review.setWinnerPlayerId(request.winnerPlayerId());
        review.setWinnerName(player1 ? call.getPlayer1Name() : call.getPlayer2Name());
        review.setScore(limitText(firstText(request.score(), latest == null ? null : latest.getLiveScore()), 80));
        review.setReviewer(limitText(safeText(request.reviewer(), "USER"), 80));
        review.setNote(limitText(request.note(), 400));
        review.setCreatedAt(LocalDateTime.now());
        reviewRepository.save(review);
        PaperTradeDecisionSample decision = decisionSampleRepository
                .findTopBySessionIdAndEventKeyOrderByCreatedAtDescIdDesc(call.getSessionId(), call.getEventKey())
                .orElse(null);
        return tracking(call, review, latest, null, decision);
    }

    private ModelCallTrackingDto tracking(PaperTradeModelCall call,
                                          ModelCallViewerReview review,
                                          TrackedMatchObservation latest) {
        return tracking(call, review, latest, null, null);
    }

    private ModelCallTrackingDto tracking(PaperTradeModelCall call,
                                          ModelCallViewerReview review,
                                          TrackedMatchObservation latest,
                                          ArchiveIndex archiveIndex,
                                          PaperTradeDecisionSample decisionSample) {
        ResolvedOutcome system = resolveOutcome(call, latest, archiveIndex).orElse(null);
        boolean completionSignal = latest != null && (latest.isMatchCompleted()
                || latest.isResulted()
                || isFinishedPhase(latest.getMatchPhase()));
        PipelineState state = pipelineState(call, latest, system, review, completionSignal);
        boolean conflict = system != null && review != null
                && !review.getWinnerPlayerId().equals(system.winnerPlayerId());
        Long effectiveWinner = system != null
                ? system.winnerPlayerId()
                : review == null ? null : review.getWinnerPlayerId();
        String effectiveOutcome = effectiveWinner == null
                ? "AWAITING"
                : call.getPredictedWinnerPlayerId() == null
                ? "NO_LEAN"
                : call.getPredictedWinnerPlayerId().equals(effectiveWinner) ? "CORRECT" : "INCORRECT";
        PredictorSnapshot predictor = predictorSnapshot(call, decisionSample);

        return new ModelCallTrackingDto(
                call.getId(),
                call.getSessionId(),
                call.getEventKey(),
                safeText(call.getEventName(), playerPairLabel(call)),
                call.getCompetitionName(),
                call.getSource(),
                firstText(call.getExternalEventId(), MatchKeyBuilder.extractExternalEventId(call.getSource())),
                call.getStrategy(),
                call.getModelVersion(),
                call.getCaptureType(),
                iso(call.getCapturedAt()),
                call.getStartTimeIso(),
                call.getPlayer1Id(),
                call.getPlayer1Name(),
                call.getPlayer2Id(),
                call.getPlayer2Name(),
                call.getPredictedWinnerPlayerId(),
                call.getPredictedWinnerName(),
                call.getModelProbability(),
                call.getModelFairAmericanOdds(),
                call.getHardRockAmericanOdds(),
                call.getOpponentHardRockAmericanOdds(),
                call.getHardRockNoVigProbability(),
                hardRockMarginPct(call),
                call.isRecommendedAtCapture(),
                call.isHasPaperPick(),
                call.getDecisionStatus(),
                call.getDecisionReason(),
                predictor.topTrigger(),
                predictor.featureContributions(),
                predictor.overallReliability(),
                predictor.ratingAgreement(),
                predictor.triggerReliability(),
                predictor.baselineStability(),
                predictor.suggestedEdge(),
                predictor.selectionScore(),
                predictor.signalQuality(),
                predictor.confidenceWidth(),
                latest == null ? null : latest.getLiveScore(),
                latest == null ? null : latest.getMatchPhase(),
                latest == null ? null : latest.getSource(),
                latest == null ? null : iso(latest.getObservedAt()),
                latest != null && latest.isLive(),
                completionSignal,
                latest == null ? null : latest.getProvisionalOutcomeMethod(),
                latest == null ? null : latest.getProvisionalOutcomeConfidence(),
                state.stage(),
                state.label(),
                state.detail(),
                system == null ? null : system.winnerPlayerId(),
                system == null ? null : system.winnerName(),
                system == null ? null : system.score(),
                system == null ? null : system.source(),
                system == null ? null : iso(system.completedAt()),
                review == null ? null : review.getWinnerPlayerId(),
                review == null ? null : review.getWinnerName(),
                review == null ? null : review.getScore(),
                review == null ? null : review.getNote(),
                review == null ? null : iso(review.getCreatedAt()),
                effectiveOutcome,
                system != null ? "SYSTEM" : review != null ? "VIEWER" : null,
                system == null && call.getPlayer1Id() != null && call.getPlayer2Id() != null && !conflict);
    }

    private PipelineState pipelineState(PaperTradeModelCall call,
                                        TrackedMatchObservation latest,
                                        ResolvedOutcome system,
                                        ModelCallViewerReview review,
                                        boolean completionSignal) {
        if (system != null && review != null && !review.getWinnerPlayerId().equals(system.winnerPlayerId())) {
            return new PipelineState("RESULT_CONFLICT", "Result conflict",
                    "Your provisional winner disagrees with the trusted system result. Both are preserved for review.");
        }
        if (system != null) {
            String evidence = "MATCH_ARCHIVE".equals(system.source()) ? "the completed-match archive" : "a trusted terminal score";
            return new PipelineState("SYSTEM_CONFIRMED", "System-confirmed",
                    "Winner accepted from " + evidence + "; the model call is now officially graded.");
        }
        if (review != null) {
            return new PipelineState("VIEWER_APPROVED", "Viewer-approved",
                    "Your provisional grade is visible now. Official settlement and model training still wait for trusted evidence.");
        }
        if (completionSignal) {
            return new PipelineState("SETTLEMENT_REVIEW", "Checking terminal evidence",
                    "A finish signal was seen, but the winner evidence is not yet trusted enough for automatic settlement.");
        }
        if (latest != null && latest.isLive()) {
            if (isStaleLiveObservation(latest)) {
                return new PipelineState("SETTLEMENT_REVIEW", "Score feed stale · verify result",
                        "The last live score was " + safeText(latest.getLiveScore(), "recorded")
                                + " and stopped updating " + observationAge(latest.getObservedAt())
                                + " ago. Trusted completion is still missing; viewer approval is available as a provisional grade.");
            }
            return new PipelineState("LIVE_MONITORING", "Live · tracking score",
                    "The match is in progress. Score observations are being collected until a terminal winner can be verified.");
        }
        Optional<LocalDateTime> start = parseStartDateTime(call.getStartTimeIso());
        if (start.isPresent() && start.get().isAfter(LocalDateTime.now())) {
            return new PipelineState("SCHEDULED", "Scheduled",
                    "The model call is frozen and waiting for the match to start and the live score feed to appear.");
        }
        if (latest == null) {
            return new PipelineState("WAITING_FOR_FEED", "Waiting for score feed",
                    "The model decision is recorded, but no score observation has been linked to this event yet.");
        }
        return new PipelineState("WAITING_FOR_FEED", "Match started · no live score",
                "The event still has only a pregame observation even though its scheduled start has passed. The live score feed has not linked a score yet.");
    }

    private static boolean isStaleLiveObservation(TrackedMatchObservation latest) {
        return latest.getObservedAt() != null
                && latest.getObservedAt().isBefore(LocalDateTime.now().minus(LIVE_OBSERVATION_STALE_AFTER));
    }

    private static String observationAge(LocalDateTime observedAt) {
        if (observedAt == null) return "an unknown amount of time";
        long minutes = Math.max(1, Duration.between(observedAt, LocalDateTime.now()).toMinutes());
        if (minutes < 60) return minutes + (minutes == 1 ? " minute" : " minutes");
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return hours + (hours == 1 ? " hour" : " hours")
                + (remainder == 0 ? "" : " " + remainder + " min");
    }

    private Map<Long, ModelCallViewerReview> latestReviews(Long sessionId) {
        Map<Long, ModelCallViewerReview> latest = new LinkedHashMap<>();
        for (ModelCallViewerReview review : reviewRepository.findBySessionIdOrderByCreatedAtDesc(sessionId)) {
            latest.putIfAbsent(review.getCallId(), review);
        }
        return latest;
    }

    private Map<String, TrackedMatchObservation> latestObservations(Long sessionId) {
        Map<String, TrackedMatchObservation> latest = new LinkedHashMap<>();
        for (TrackedMatchObservation observation : observationRepository.findLatestForEachEventBySessionId(sessionId)) {
            latest.merge(observation.getEventKey(), observation, (left, right) ->
                    left.getObservedAt() == null || (right.getObservedAt() != null && right.getObservedAt().isAfter(left.getObservedAt()))
                            ? right : left);
        }
        return latest;
    }

    private Map<String, PaperTradeDecisionSample> latestDecisionSamples(Long sessionId) {
        Map<String, PaperTradeDecisionSample> latest = new LinkedHashMap<>();
        for (PaperTradeDecisionSample sample : decisionSampleRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            if (sample != null && StringUtils.hasText(sample.getEventKey())) {
                latest.put(sample.getEventKey(), sample);
            }
        }
        return latest;
    }

    private Optional<TrackedMatchObservation> latestObservation(PaperTradeModelCall call) {
        return observationRepository.findTopBySessionIdAndEventKeyOrderByObservedAtDescIdDesc(
                call.getSessionId(), call.getEventKey());
    }

    private static int countStage(List<ModelCallTrackingDto> calls, String stage) {
        return (int) calls.stream().filter(call -> stage.equals(call.pipelineStage())).count();
    }

    private static Double hardRockMarginPct(PaperTradeModelCall call) {
        Double chosen = americanImplied(call.getHardRockAmericanOdds());
        Double opponent = americanImplied(call.getOpponentHardRockAmericanOdds());
        if (chosen == null || opponent == null) return null;
        return round2(Math.max(0.0, chosen + opponent - 1.0) * 100.0);
    }

    private static Double americanImplied(Integer odds) {
        if (odds == null || odds == 0) return null;
        return odds > 0 ? 100.0 / (odds + 100.0) : (-odds) / ((-odds) + 100.0);
    }

    private static Double americanDecimal(Integer odds) {
        if (odds == null || odds == 0) return null;
        return odds > 0 ? 1.0 + (odds / 100.0) : 1.0 + (100.0 / -odds);
    }

    private static String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private static String limitText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private void applyWinnerCall(PaperTradeModelCall call, LiveOddsRecommendationDto row) {
        Double p1 = finiteProbability(row.modelProbabilityPlayer1());
        Double p2 = finiteProbability(row.modelProbabilityPlayer2());
        if (p1 == null || p2 == null || Math.abs(p1 - p2) <= PROBABILITY_TIE_EPSILON) {
            call.setPredictedWinnerPlayerId(null);
            call.setPredictedWinnerName(null);
            call.setModelProbability(p1 == null || p2 == null ? null : Math.max(p1, p2));
            call.setRawModelProbability(null);
            call.setModelFairAmericanOdds(null);
            call.setHardRockAmericanOdds(null);
            call.setOpponentHardRockAmericanOdds(null);
            call.setHardRockNoVigProbability(null);
            call.setModelMarketNoVigGap(null);
            call.setConfidenceLow(null);
            call.setConfidenceHigh(null);
            return;
        }

        boolean player1 = p1 > p2;
        call.setPredictedWinnerPlayerId(player1 ? row.player1Id() : row.player2Id());
        call.setPredictedWinnerName(player1
                ? safeText(row.player1Name(), "Player 1")
                : safeText(row.player2Name(), "Player 2"));
        call.setModelProbability(player1 ? p1 : p2);
        call.setRawModelProbability(finiteProbability(player1
                ? row.rawModelProbabilityPlayer1()
                : row.rawModelProbabilityPlayer2()));
        call.setModelFairAmericanOdds(player1
                ? row.modelFairAmericanOddsPlayer1()
                : row.modelFairAmericanOddsPlayer2());
        call.setHardRockAmericanOdds(player1 ? row.americanOddsPlayer1() : row.americanOddsPlayer2());
        call.setOpponentHardRockAmericanOdds(player1 ? row.americanOddsPlayer2() : row.americanOddsPlayer1());
        double chosenImplied = player1 ? row.impliedProbabilityPlayer1() : row.impliedProbabilityPlayer2();
        double totalImplied = row.impliedProbabilityPlayer1() + row.impliedProbabilityPlayer2();
        call.setHardRockNoVigProbability(totalImplied > 0.0
                ? clamp(chosenImplied / totalImplied, 0.0, 1.0)
                : null);
        if (call.getHardRockNoVigProbability() != null) {
            call.setModelMarketNoVigGap(call.getModelProbability() - call.getHardRockNoVigProbability());
        } else {
            call.setModelMarketNoVigGap(null);
        }
        Double p1Low = finiteProbability(row.modelConfidenceLowPlayer1());
        Double p1High = finiteProbability(row.modelConfidenceHighPlayer1());
        if (p1Low != null && p1High != null && p1Low <= p1High) {
            call.setConfidenceLow(player1 ? p1Low : 1.0 - p1High);
            call.setConfidenceHigh(player1 ? p1High : 1.0 - p1Low);
        } else {
            call.setConfidenceLow(null);
            call.setConfidenceHigh(null);
        }
    }

    private static void applyPredictorSnapshot(PaperTradeModelCall call,
                                               LiveOddsRecommendationDto row,
                                               PaperTradeDecisionSample sample) {
        call.setTopTrigger(firstText(sample == null ? null : sample.getTopTrigger(), row.topTrigger()));
        call.setFeatureContributions(sample == null ? null : sample.getFeatureContributions());
        call.setOverallReliability(firstFinite(
                sample == null ? null : sample.getOverallReliability(), row.overallReliability()));
        call.setRatingAgreement(firstFinite(
                sample == null ? null : sample.getRatingAgreement(), row.ratingAgreement()));
        call.setTriggerReliability(firstFinite(
                sample == null ? null : sample.getTriggerReliability(), row.topTriggerReliability()));
        call.setBaselineStability(firstFinite(
                sample == null ? null : sample.getBaselineStability(), row.suggestedSideBaselineStability()));
        call.setSuggestedEdge(firstFinite(
                sample == null ? null : sample.getSuggestedEdge(), row.suggestedEdge()));
        call.setSelectionScore(sample == null ? null : finite(sample.getSelectionScore()));
        call.setSignalQuality(sample == null ? null : finite(sample.getSignalQuality()));
        call.setConfidenceWidth(sample == null ? null : finite(sample.getConfidenceWidth()));
        call.setGateResults(sample == null ? null : sample.getGateResults());
    }

    private static PredictorSnapshot predictorSnapshot(PaperTradeModelCall call,
                                                        PaperTradeDecisionSample fallback) {
        return new PredictorSnapshot(
                firstText(call.getTopTrigger(), fallback == null ? null : fallback.getTopTrigger()),
                firstText(call.getFeatureContributions(), fallback == null ? null : fallback.getFeatureContributions()),
                firstFinite(call.getOverallReliability(), fallback == null ? null : fallback.getOverallReliability()),
                firstFinite(call.getRatingAgreement(), fallback == null ? null : fallback.getRatingAgreement()),
                firstFinite(call.getTriggerReliability(), fallback == null ? null : fallback.getTriggerReliability()),
                firstFinite(call.getBaselineStability(), fallback == null ? null : fallback.getBaselineStability()),
                firstFinite(call.getSuggestedEdge(), fallback == null ? null : fallback.getSuggestedEdge()),
                firstFinite(call.getSelectionScore(), fallback == null ? null : fallback.getSelectionScore()),
                firstFinite(call.getSignalQuality(), fallback == null ? null : fallback.getSignalQuality()),
                firstFinite(call.getConfidenceWidth(), fallback == null ? null : fallback.getConfidenceWidth())
        );
    }

    private static Double firstFinite(Double... values) {
        for (Double value : values) {
            Double finite = finite(value);
            if (finite != null) return finite;
        }
        return null;
    }

    private static Double finite(Double value) {
        return value == null || !Double.isFinite(value) ? null : value;
    }

    private Optional<Match> resolveCompletedMatch(PaperTradeModelCall call) {
        Set<Long> seen = new LinkedHashSet<>();
        List<Match> exact = new ArrayList<>();
        for (String identity : List.of(
                safeText(call.getSourceFeedEventId(), ""),
                safeText(call.getExternalEventId(), ""))) {
            if (!StringUtils.hasText(identity)) continue;
            for (Match match : matchRepository.findMatchesByFeedEventIdentity(identity, PageRequest.of(0, 8))) {
                if (match.isComplete() && samePlayers(call, match) && seen.add(match.getId())) {
                    exact.add(match);
                }
            }
        }
        if (exact.size() == 1) {
            return Optional.of(exact.get(0));
        }
        if (exact.size() > 1) {
            return Optional.empty();
        }

        if (call.getPlayer1Id() == null || call.getPlayer2Id() == null) {
            return Optional.empty();
        }
        LocalDate eventDate = parseStartDateTime(call.getStartTimeIso())
                .map(LocalDateTime::toLocalDate)
                .orElseGet(() -> call.getCapturedAt() == null ? null : call.getCapturedAt().toLocalDate());
        if (eventDate == null) {
            return Optional.empty();
        }
        long watermark = call.getMatchIdHighWatermark() == null ? 0L : call.getMatchIdHighWatermark();
        List<Match> candidates = matchRepository.findCompletedMatchesByPlayersOnDate(
                        call.getPlayer1Id(), call.getPlayer2Id(), eventDate)
                .stream()
                .filter(match -> match.getId() != null && match.getId() > watermark)
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private Optional<ResolvedOutcome> resolveOutcome(PaperTradeModelCall call) {
        return resolveOutcome(call, latestObservation(call).orElse(null));
    }

    private Optional<ResolvedOutcome> resolveOutcome(PaperTradeModelCall call,
                                                     TrackedMatchObservation observation) {
        return resolveOutcome(call, observation, null);
    }

    private Optional<ResolvedOutcome> resolveOutcome(PaperTradeModelCall call,
                                                     TrackedMatchObservation observation,
                                                     ArchiveIndex archiveIndex) {
        if (isTrustedTerminalObservation(observation)) {
            return Optional.of(terminalOutcome(call, observation));
        }
        if (!shouldCheckArchive(call, observation)) {
            return Optional.empty();
        }
        if (archiveIndex != null) {
            return resolveArchivedOutcome(call, archiveIndex);
        }
        return resolveArchivedOutcomeCached(call);
    }

    private ResolvedOutcome terminalOutcome(PaperTradeModelCall call,
                                            TrackedMatchObservation observation) {
        Long winnerId = observation.getProvisionalWinnerPlayerId();
        String winner = winnerId != null && winnerId.equals(call.getPlayer1Id())
                ? call.getPlayer1Name()
                : winnerId != null && winnerId.equals(call.getPlayer2Id())
                ? call.getPlayer2Name()
                : "N/A";
        LocalDate eventDate = parseStartDateTime(call.getStartTimeIso())
                .map(LocalDateTime::toLocalDate)
                .orElseGet(() -> observation.getObservedAt() == null ? null : observation.getObservedAt().toLocalDate());
        return new ResolvedOutcome(
                null,
                winnerId,
                winner,
                safeText(observation.getLiveScore(), "N/A"),
                eventDate,
                observation.getObservedAt(),
                "TRUSTED_TERMINAL_SCORE"
        );
    }

    private Optional<ResolvedOutcome> resolveArchivedOutcomeCached(PaperTradeModelCall call) {
        if (call.getId() == null) return resolveArchivedOutcome(call);
        long now = System.nanoTime();
        CachedOutcome cached = outcomeCache.get(call.getId());
        if (cached != null && cached.expiresAtNanos() > now) return Optional.ofNullable(cached.outcome());
        CachedOutcome refreshed = outcomeCache.compute(call.getId(), (ignored, current) -> {
            long computeNow = System.nanoTime();
            if (current != null && current.expiresAtNanos() > computeNow) return current;
            ResolvedOutcome outcome = resolveArchivedOutcome(call).orElse(null);
            long ttl = outcome == null ? 30_000_000_000L : 21_600_000_000_000L;
            return new CachedOutcome(outcome, computeNow + ttl);
        });
        return Optional.ofNullable(refreshed.outcome());
    }

    private Optional<ResolvedOutcome> resolveArchivedOutcome(PaperTradeModelCall call) {
        return resolveCompletedMatch(call).map(match -> new ResolvedOutcome(
                match.getId(),
                match.getWinnerPlayerId(),
                winnerName(match),
                scoreLabel(match),
                match.getDate(),
                match.getDate() == null ? null : match.getDate().atTime(23, 59),
                "MATCH_ARCHIVE"));
    }

    private ArchiveIndex archiveIndex(List<PaperTradeModelCall> calls) {
        List<LocalDate> dates = calls.stream()
                .map(ModelCallLedgerService::eventDate)
                .flatMap(Optional::stream)
                .toList();
        if (dates.isEmpty()) return null;
        LocalDate from = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate to = dates.stream().max(LocalDate::compareTo).orElseThrow();
        List<Match> completed = matchRepository.findCompletedMatchesBetween(from, to);
        if (completed == null || completed.isEmpty()) return ArchiveIndex.empty();

        Map<String, List<Match>> byIdentity = new LinkedHashMap<>();
        Map<PlayerDateKey, List<Match>> byPlayersAndDate = new LinkedHashMap<>();
        for (Match match : completed) {
            if (match == null || !match.isComplete()) continue;
            addIdentity(byIdentity, match.getSourceFeedEventId(), match);
            addIdentity(byIdentity, match.getExternalId(), match);
            playerDateKey(match).ifPresent(key -> byPlayersAndDate
                    .computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(match));
        }
        return new ArchiveIndex(byIdentity, byPlayersAndDate);
    }

    private Optional<ResolvedOutcome> resolveArchivedOutcome(PaperTradeModelCall call,
                                                              ArchiveIndex index) {
        Set<Long> seen = new LinkedHashSet<>();
        List<Match> exact = new ArrayList<>();
        for (String identity : List.of(
                safeText(call.getSourceFeedEventId(), ""),
                safeText(call.getExternalEventId(), ""))) {
            if (!StringUtils.hasText(identity)) continue;
            for (Match match : index.byIdentity().getOrDefault(identity, List.of())) {
                if (samePlayers(call, match) && match.getId() != null && seen.add(match.getId())) exact.add(match);
            }
        }
        if (exact.size() == 1) return Optional.of(archiveOutcome(exact.get(0)));
        if (exact.size() > 1) return Optional.empty();

        Optional<PlayerDateKey> key = playerDateKey(call);
        if (key.isEmpty()) return Optional.empty();
        long watermark = call.getMatchIdHighWatermark() == null ? 0L : call.getMatchIdHighWatermark();
        List<Match> candidates = index.byPlayersAndDate().getOrDefault(key.get(), List.of()).stream()
                .filter(match -> match.getId() != null && match.getId() > watermark)
                .toList();
        return candidates.size() == 1 ? Optional.of(archiveOutcome(candidates.get(0))) : Optional.empty();
    }

    private static ResolvedOutcome archiveOutcome(Match match) {
        return new ResolvedOutcome(
                match.getId(),
                match.getWinnerPlayerId(),
                winnerName(match),
                scoreLabel(match),
                match.getDate(),
                match.getDate() == null ? null : match.getDate().atTime(23, 59),
                "MATCH_ARCHIVE");
    }

    private static void addIdentity(Map<String, List<Match>> index, String identity, Match match) {
        if (StringUtils.hasText(identity)) {
            index.computeIfAbsent(identity.trim(), ignored -> new ArrayList<>()).add(match);
        }
    }

    private static Optional<PlayerDateKey> playerDateKey(PaperTradeModelCall call) {
        if (call.getPlayer1Id() == null || call.getPlayer2Id() == null) return Optional.empty();
        return eventDate(call).map(date -> PlayerDateKey.of(call.getPlayer1Id(), call.getPlayer2Id(), date));
    }

    private static Optional<PlayerDateKey> playerDateKey(Match match) {
        if (match.getPlayer1() == null || match.getPlayer2() == null
                || match.getPlayer1().getId() == null || match.getPlayer2().getId() == null
                || match.getDate() == null) return Optional.empty();
        return Optional.of(PlayerDateKey.of(match.getPlayer1().getId(), match.getPlayer2().getId(), match.getDate()));
    }

    private static Optional<LocalDate> eventDate(PaperTradeModelCall call) {
        return parseStartDateTime(call.getStartTimeIso())
                .map(LocalDateTime::toLocalDate)
                .or(() -> Optional.ofNullable(call.getCapturedAt()).map(LocalDateTime::toLocalDate));
    }

    private static boolean shouldCheckArchive(PaperTradeModelCall call,
                                              TrackedMatchObservation observation) {
        if (observation != null) {
            boolean completion = observation.isMatchCompleted()
                    || observation.isResulted()
                    || isFinishedPhase(observation.getMatchPhase());
            if (completion) return true;
            if (observation.isLive()) return isStaleLiveObservation(observation);
        }
        Optional<LocalDateTime> start = parseStartDateTime(call.getStartTimeIso());
        return start.isPresent() && start.get().isBefore(LocalDateTime.now().minusMinutes(12));
    }

    private static boolean isTrustedTerminalObservation(TrackedMatchObservation observation) {
        if (observation == null || observation.getProvisionalWinnerPlayerId() == null) return false;
        boolean completionSignal = observation.isMatchCompleted()
                || observation.isResulted()
                || isFinishedPhase(observation.getMatchPhase());
        return completionSignal
                && ProvisionalScoreOutcomeTracker.TERMINAL_SCORE.equals(observation.getProvisionalOutcomeMethod());
    }

    private static ResolvedCall preferredCall(ResolvedCall left, ResolvedCall right) {
        boolean leftPregame = PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE.equals(left.call().getCaptureType());
        boolean rightPregame = PaperTradeModelCall.CAPTURE_PREMATCH_CLOSE.equals(right.call().getCaptureType());
        if (leftPregame != rightPregame) {
            return leftPregame ? left : right;
        }
        LocalDateTime leftAt = left.call().getCapturedAt();
        LocalDateTime rightAt = right.call().getCapturedAt();
        if (leftAt == null) return right;
        if (rightAt == null) return left;
        return leftAt.isBefore(rightAt) ? left : right;
    }

    private static ModelCallResultDto toResult(PaperTradeModelCall call,
                                                ResolvedOutcome resolved,
                                                boolean hasLean,
                                                boolean correct) {
        return new ModelCallResultDto(
                call.getId(),
                resolved.matchId(),
                call.getEventKey(),
                safeText(call.getEventName(), playerPairLabel(call)),
                call.getCompetitionName(),
                call.getCaptureType(),
                call.getCapturedAt() == null ? null : call.getCapturedAt().toString(),
                resolved.matchDate() == null ? null : resolved.matchDate().toString(),
                call.getStartTimeIso(),
                call.getPlayer1Name(),
                call.getPlayer2Name(),
                call.getPredictedWinnerPlayerId(),
                call.getPredictedWinnerName(),
                call.getModelProbability(),
                call.getModelFairAmericanOdds(),
                call.getHardRockAmericanOdds(),
                call.getOpponentHardRockAmericanOdds(),
                call.getHardRockNoVigProbability(),
                resolved.winnerPlayerId(),
                resolved.winnerName(),
                resolved.score(),
                !hasLean ? "NO_LEAN" : (correct ? "CORRECT" : "INCORRECT"),
                call.isHasPaperPick(),
                call.isRecommendedAtCapture()
        );
    }

    private synchronized long currentMatchIdHighWatermark() {
        long now = System.nanoTime();
        if (now >= watermarkCacheExpiresAtNanos) {
            Long value = matchRepository.findMaxMatchId();
            cachedMatchIdHighWatermark = value == null ? 0L : Math.max(0L, value);
            watermarkCacheExpiresAtNanos = now + 2_000_000_000L;
        }
        return cachedMatchIdHighWatermark;
    }

    private static boolean samePlayers(PaperTradeModelCall call, Match match) {
        if (call.getPlayer1Id() == null || call.getPlayer2Id() == null
                || match.getPlayer1() == null || match.getPlayer2() == null
                || match.getPlayer1().getId() == null || match.getPlayer2().getId() == null) {
            return false;
        }
        long a = call.getPlayer1Id();
        long b = call.getPlayer2Id();
        long x = match.getPlayer1().getId();
        long y = match.getPlayer2().getId();
        return (a == x && b == y) || (a == y && b == x);
    }

    private static String winnerName(Match match) {
        if (match.getWinnerPlayerId() == null) return "N/A";
        if (match.getPlayer1() != null && match.getWinnerPlayerId().equals(match.getPlayer1().getId())) {
            return match.getPlayer1().getName();
        }
        if (match.getPlayer2() != null && match.getWinnerPlayerId().equals(match.getPlayer2().getId())) {
            return match.getPlayer2().getName();
        }
        return "N/A";
    }

    private static String scoreLabel(Match match) {
        if (StringUtils.hasText(match.getResult())) return match.getResult().trim();
        if (match.getPlayer1SetsWon() != null && match.getPlayer2SetsWon() != null) {
            return match.getPlayer1SetsWon() + ":" + match.getPlayer2SetsWon();
        }
        return "N/A";
    }

    private static String playerPairLabel(LiveOddsRecommendationDto row) {
        return safeText(row.player1Name(), "Player 1") + " vs " + safeText(row.player2Name(), "Player 2");
    }

    private static String playerPairLabel(PaperTradeModelCall call) {
        return safeText(call.getPlayer1Name(), "Player 1") + " vs " + safeText(call.getPlayer2Name(), "Player 2");
    }

    private static Double finiteProbability(Double value) {
        if (value == null || !Double.isFinite(value)) return null;
        return clamp(value, 0.0, 1.0);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private static double percentage(double numerator, int denominator) {
        return denominator <= 0 ? 0.0 : round2((numerator / denominator) * 100.0);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static String evidenceLabel(int sampleSize) {
        if (sampleSize >= 100) return "DECISION_GRADE";
        if (sampleSize >= 50) return "DIRECTIONAL";
        if (sampleSize >= 20) return "EARLY_SIGNAL";
        return "COLLECTING";
    }

    private static ConfidenceInterval wilson(int successes, int samples) {
        if (samples <= 0) return ConfidenceInterval.empty();
        double z = 1.959963984540054;
        double p = successes / (double) samples;
        double z2 = z * z;
        double denominator = 1.0 + z2 / samples;
        double center = (p + z2 / (2.0 * samples)) / denominator;
        double radius = z * Math.sqrt((p * (1.0 - p) + z2 / (4.0 * samples)) / samples) / denominator;
        return new ConfidenceInterval(
                round2(Math.max(0.0, center - radius) * 100.0),
                round2(Math.min(1.0, center + radius) * 100.0));
    }

    private static ConfidenceInterval meanInterval(List<Double> samples) {
        if (samples == null || samples.size() < 2) return ConfidenceInterval.empty();
        double mean = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = samples.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .sum() / (samples.size() - 1.0);
        double radius = 1.959963984540054 * Math.sqrt(variance / samples.size());
        return new ConfidenceInterval(round2((mean - radius) * 100.0), round2((mean + radius) * 100.0));
    }

    private static Double positiveMeanConfidence(List<Double> samples) {
        if (samples == null || samples.size() < 5) return null;
        double mean = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = samples.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .sum() / (samples.size() - 1.0);
        double standardError = Math.sqrt(variance / samples.size());
        if (standardError <= 1.0e-12) return mean > 0.0 ? 100.0 : 0.0;
        return round2(normalCdf(mean / standardError) * 100.0);
    }

    /** Abramowitz-Stegun approximation, sufficient for operator confidence telemetry. */
    private static double normalCdf(double z) {
        double sign = z < 0.0 ? -1.0 : 1.0;
        double x = Math.abs(z) / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double polynomial = (((((1.061405429 * t - 1.453152027) * t)
                + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t;
        double erf = sign * (1.0 - polynomial * Math.exp(-x * x));
        return clamp(0.5 * (1.0 + erf), 0.0, 1.0);
    }

    private static List<FactorValue> parseFactors(String encoded) {
        if (!StringUtils.hasText(encoded)) return List.of();
        List<FactorValue> factors = new ArrayList<>();
        for (String token : encoded.split("\\|")) {
            int split = token.lastIndexOf('=');
            if (split <= 0 || split >= token.length() - 1) continue;
            try {
                double value = Double.parseDouble(token.substring(split + 1));
                if (Double.isFinite(value)) factors.add(new FactorValue(token.substring(0, split), value));
            } catch (NumberFormatException ignored) {
                // Predictor telemetry is best effort; malformed factors remain absent, never fabricated.
            }
        }
        return factors;
    }

    private static ModelCallScorecardDto emptyScorecard(int ignoredLimit) {
        return new ModelCallScorecardDto(
                null, "No active simulation", LocalDateTime.now().toString(),
                0, 0, 0, 0, 0, 0, 0.0,
                0, 0, 0.0, 0, 0, 0.0,
                0.0, null,
                0, 0, 0, 0.0, 0.0, 0.0, 0.0,
                0, 0, 0, 0.0, 0, 0,
                List.of());
    }

    private static LiveRunAnalyticsDto emptyAnalytics() {
        return new LiveRunAnalyticsDto(
                null, "No active simulation", LocalDateTime.now().toString(), "COLLECTING",
                100, 0.0, 0, 0, 0, 0, 0, 0.0, null, null, 0.0, null,
                0, 0, 0, 0.0, 0.0, 0.0, 0.0, null, null, null,
                0, 0, List.of(), List.of(), List.of(), List.of());
    }

    private record ResolvedCall(PaperTradeModelCall call, ResolvedOutcome outcome) { }

    private record ResolvedOutcome(Long matchId,
                                   Long winnerPlayerId,
                                   String winnerName,
                                   String score,
                                   LocalDate matchDate,
                                   LocalDateTime completedAt,
                                   String source) { }

    private record PipelineState(String stage, String label, String detail) { }

    private record PredictorSnapshot(String topTrigger,
                                     String featureContributions,
                                     Double overallReliability,
                                     Double ratingAgreement,
                                     Double triggerReliability,
                                     Double baselineStability,
                                     Double suggestedEdge,
                                     Double selectionScore,
                                     Double signalQuality,
                                     Double confidenceWidth) { }

    private record ConfidenceInterval(Double low, Double high) {
        private static ConfidenceInterval empty() { return new ConfidenceInterval(null, null); }
    }

    private record FactorValue(String name, double value) { }

    private static final class SegmentAccumulator {
        private static final int READINESS_TARGET = 30;
        private int samples;
        private int wins;
        private int pricedSamples;
        private double probability;
        private double profit;
        private double reliability;
        private int reliabilitySamples;

        void add(boolean won, double modelProbability, double perDollarProfit,
                 boolean priced, Double reliabilityValue) {
            samples++;
            if (won) wins++;
            probability += modelProbability;
            if (priced) {
                pricedSamples++;
                profit += perDollarProfit;
            }
            if (reliabilityValue != null && Double.isFinite(reliabilityValue)) {
                reliability += reliabilityValue;
                reliabilitySamples++;
            }
        }

        LiveRunAnalyticsDto.SegmentPerformanceDto toDto(String segment) {
            int losses = Math.max(0, samples - wins);
            double observed = samples == 0 ? 0.0 : wins / (double) samples;
            double predicted = samples == 0 ? 0.0 : probability / samples;
            ConfidenceInterval interval = wilson(wins, samples);
            return new LiveRunAnalyticsDto.SegmentPerformanceDto(
                    segment, samples, wins, losses, percentage(wins, samples),
                    interval.low(), interval.high(), round2(predicted * 100.0),
                    round2((predicted - observed) * 100.0), round2(profit),
                    percentage(profit, pricedSamples),
                    reliabilitySamples == 0 ? 0.0 : round2(reliability * 100.0 / reliabilitySamples),
                    READINESS_TARGET, round2(Math.min(100.0, samples * 100.0 / READINESS_TARGET)));
        }
    }

    private static final class FactorAccumulator {
        private static final int READINESS_TARGET = 50;
        private int samples;
        private int wins;
        private int losses;
        private int directionCorrect;
        private double absolute;
        private double aligned;
        private double winContribution;
        private double lossContribution;

        void add(double contribution, boolean won) {
            samples++;
            absolute += Math.abs(contribution);
            aligned += contribution;
            if ((won && contribution >= 0.0) || (!won && contribution < 0.0)) directionCorrect++;
            if (won) {
                wins++;
                winContribution += contribution;
            } else {
                losses++;
                lossContribution += contribution;
            }
        }

        LiveRunAnalyticsDto.FactorPerformanceDto toDto(String factor) {
            return new LiveRunAnalyticsDto.FactorPerformanceDto(
                    factor, samples,
                    samples == 0 ? 0.0 : round4(absolute / samples),
                    samples == 0 ? 0.0 : round4(aligned / samples),
                    percentage(directionCorrect, samples),
                    wins == 0 ? 0.0 : round4(winContribution / wins),
                    losses == 0 ? 0.0 : round4(lossContribution / losses),
                    READINESS_TARGET,
                    round2(Math.min(100.0, samples * 100.0 / READINESS_TARGET)));
        }
    }

    private record CachedOutcome(ResolvedOutcome outcome, long expiresAtNanos) { }

    private record ArchiveIndex(Map<String, List<Match>> byIdentity,
                                Map<PlayerDateKey, List<Match>> byPlayersAndDate) {
        private static ArchiveIndex empty() {
            return new ArchiveIndex(Map.of(), Map.of());
        }
    }

    private record PlayerDateKey(long playerLow, long playerHigh, LocalDate date) {
        private static PlayerDateKey of(long player1, long player2, LocalDate date) {
            return new PlayerDateKey(Math.min(player1, player2), Math.max(player1, player2), date);
        }
    }
}
