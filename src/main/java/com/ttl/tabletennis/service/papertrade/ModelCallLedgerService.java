package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.ModelCallViewerReview;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.ModelCallApprovalRequest;
import com.ttl.tabletennis.dto.ModelCallMonitorDto;
import com.ttl.tabletennis.dto.ModelCallResultDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.dto.ModelCallTrackingDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.ModelCallViewerReviewRepository;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
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

    private static final double PROBABILITY_TIE_EPSILON = 0.000_001;
    private static final int MAX_RESULTS = 200;
    private static final Duration LIVE_OBSERVATION_STALE_AFTER = Duration.ofMinutes(3);

    private final PaperTradeModelCallRepository callRepository;
    private final PaperTradeSessionRepository sessionRepository;
    private final MatchRepository matchRepository;
    private final TrackedMatchObservationRepository observationRepository;
    private final ModelCallViewerReviewRepository reviewRepository;

    private volatile long watermarkCacheExpiresAtNanos;
    private volatile long cachedMatchIdHighWatermark;
    private final Map<Long, CachedOutcome> outcomeCache = new ConcurrentHashMap<>();

    public ModelCallLedgerService(PaperTradeModelCallRepository callRepository,
                                  PaperTradeSessionRepository sessionRepository,
                                  MatchRepository matchRepository,
                                  TrackedMatchObservationRepository observationRepository,
                                  ModelCallViewerReviewRepository reviewRepository) {
        this.callRepository = callRepository;
        this.sessionRepository = sessionRepository;
        this.matchRepository = matchRepository;
        this.observationRepository = observationRepository;
        this.reviewRepository = reviewRepository;
    }

    /**
     * Upsert one canonical call for the event. Prematch snapshots may refresh
     * until play begins; live snapshots never overwrite a prematch call, and a
     * live-only event freezes the first snapshot observed.
     */
    @Transactional
    public void recordCall(Long sessionId,
                           String strategy,
                           String modelVersion,
                           LiveOddsRecommendationDto row,
                           String eventKey,
                           String decisionStatus,
                           String decisionReason) {
        if (sessionId == null || row == null || !StringUtils.hasText(eventKey)) {
            return;
        }

        String normalizedEventKey = eventKey.trim();
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
            call.setModelVersion(safeText(modelVersion, "ENSEMBLE"));
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
        } else if (!storedPrematch && row.live()) {
            // First-live reads are deliberately frozen; only bet-placement
            // metadata below may change after the initial observation.
        }

        if ("PLACED".equalsIgnoreCase(decisionStatus)) {
            call.setHasPaperPick(true);
        }
        callRepository.save(call);
    }

    @Transactional(readOnly = true)
    public ModelCallScorecardDto scorecard(int limit) {
        int take = clamp(limit, 5, MAX_RESULTS);
        Optional<PaperTradeSession> activeSession =
                sessionRepository.findFirstByStatusOrderByIdDesc(PaperTradeSession.STATUS_ACTIVE);
        if (activeSession.isEmpty()) {
            return emptyScorecard(take);
        }

        PaperTradeSession session = activeSession.get();
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
            }
            if (results.size() < take) {
                results.add(toResult(call, outcome, hasLean, isCorrect));
            }
        }

        int graded = correct + incorrect;
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

        PaperTradeSession session = activeSession.get();
        Map<Long, ModelCallViewerReview> reviews = latestReviews(session.getId());
        Map<String, TrackedMatchObservation> observations = latestObservations(session.getId());
        List<PaperTradeModelCall> allCalls = callRepository.findBySessionIdOrderByCapturedAtDesc(session.getId());
        ArchiveIndex archiveIndex = archiveIndex(allCalls);
        List<ModelCallTrackingDto> trackedCalls = allCalls
                .stream()
                .map(call -> tracking(call, reviews.get(call.getId()), observations.get(call.getEventKey()), archiveIndex))
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

    @Transactional(readOnly = true)
    public ModelCallTrackingDto tracking(long callId) {
        PaperTradeModelCall call = callRepository.findById(callId)
                .orElseThrow(() -> new ResourceNotFoundException("Model call " + callId + " was not found"));
        ModelCallViewerReview review = reviewRepository.findByCallIdOrderByCreatedAtDesc(callId)
                .stream().findFirst().orElse(null);
        return tracking(call, review, latestObservation(call).orElse(null));
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
        return tracking(call, review, latest);
    }

    private ModelCallTrackingDto tracking(PaperTradeModelCall call,
                                          ModelCallViewerReview review,
                                          TrackedMatchObservation latest) {
        return tracking(call, review, latest, null);
    }

    private ModelCallTrackingDto tracking(PaperTradeModelCall call,
                                          ModelCallViewerReview review,
                                          TrackedMatchObservation latest,
                                          ArchiveIndex archiveIndex) {
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
            call.setModelFairAmericanOdds(null);
            call.setHardRockAmericanOdds(null);
            call.setOpponentHardRockAmericanOdds(null);
            call.setHardRockNoVigProbability(null);
            return;
        }

        boolean player1 = p1 > p2;
        call.setPredictedWinnerPlayerId(player1 ? row.player1Id() : row.player2Id());
        call.setPredictedWinnerName(player1
                ? safeText(row.player1Name(), "Player 1")
                : safeText(row.player2Name(), "Player 2"));
        call.setModelProbability(player1 ? p1 : p2);
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

    private static ModelCallScorecardDto emptyScorecard(int ignoredLimit) {
        return new ModelCallScorecardDto(
                null, "No active simulation", LocalDateTime.now().toString(),
                0, 0, 0, 0, 0, 0, 0.0,
                0, 0, 0.0, 0, 0, 0.0,
                0.0, null,
                0, 0, 0, 0.0, 0, 0,
                List.of());
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
