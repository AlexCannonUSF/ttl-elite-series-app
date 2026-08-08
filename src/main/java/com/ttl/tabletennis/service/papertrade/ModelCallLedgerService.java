package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.ModelCallResultDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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

    private final PaperTradeModelCallRepository callRepository;
    private final PaperTradeSessionRepository sessionRepository;
    private final MatchRepository matchRepository;
    private final TrackedMatchObservationRepository observationRepository;

    private volatile long watermarkCacheExpiresAtNanos;
    private volatile long cachedMatchIdHighWatermark;

    public ModelCallLedgerService(PaperTradeModelCallRepository callRepository,
                                  PaperTradeSessionRepository sessionRepository,
                                  MatchRepository matchRepository,
                                  TrackedMatchObservationRepository observationRepository) {
        this.callRepository = callRepository;
        this.sessionRepository = sessionRepository;
        this.matchRepository = matchRepository;
        this.observationRepository = observationRepository;
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
        List<ResolvedCall> resolved = calls.stream()
                .map(call -> new ResolvedCall(call, resolveOutcome(call).orElse(null)))
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
        List<ModelCallResultDto> results = new ArrayList<>();

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
                List.copyOf(results)
        );
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
        Optional<Match> archived = resolveCompletedMatch(call);
        if (archived.isPresent()) {
            Match match = archived.get();
            return Optional.of(new ResolvedOutcome(
                    match.getId(),
                    match.getWinnerPlayerId(),
                    winnerName(match),
                    scoreLabel(match),
                    match.getDate(),
                    match.getDate() == null ? null : match.getDate().atTime(23, 59)
            ));
        }

        Optional<TrackedMatchObservation> latest = observationRepository
                .findTopBySessionIdAndEventKeyOrderByObservedAtDesc(call.getSessionId(), call.getEventKey());
        if (latest.isEmpty() || !isTrustedTerminalObservation(latest.get())) {
            return Optional.empty();
        }
        TrackedMatchObservation observation = latest.get();
        Long winnerId = observation.getProvisionalWinnerPlayerId();
        String winner = winnerId != null && winnerId.equals(call.getPlayer1Id())
                ? call.getPlayer1Name()
                : winnerId != null && winnerId.equals(call.getPlayer2Id())
                ? call.getPlayer2Name()
                : "N/A";
        LocalDate eventDate = parseStartDateTime(call.getStartTimeIso())
                .map(LocalDateTime::toLocalDate)
                .orElseGet(() -> observation.getObservedAt() == null ? null : observation.getObservedAt().toLocalDate());
        return Optional.of(new ResolvedOutcome(
                null,
                winnerId,
                winner,
                safeText(observation.getLiveScore(), "N/A"),
                eventDate,
                observation.getObservedAt()
        ));
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
                0.0, null, List.of());
    }

    private record ResolvedCall(PaperTradeModelCall call, ResolvedOutcome outcome) { }

    private record ResolvedOutcome(Long matchId,
                                   Long winnerPlayerId,
                                   String winnerName,
                                   String score,
                                   LocalDate matchDate,
                                   LocalDateTime completedAt) { }
}
