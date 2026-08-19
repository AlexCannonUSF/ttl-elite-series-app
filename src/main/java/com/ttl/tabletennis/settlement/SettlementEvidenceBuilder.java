package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.scrape.TrustTier;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.MirrorObservation;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import com.ttl.tabletennis.settlement.observation.StreamObservation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SettlementEvidenceBuilder {

    private static final Duration DEFAULT_AMBIGUITY_WINDOW = Duration.ofMinutes(90);
    private static final Pattern SCORE_PAIR_PATTERN = Pattern.compile("(\\d+)\\s*[:/-]\\s*(\\d+)");

    private final TrackedMatchObservationRepository trackedMatchObservationRepository;
    private final AmbiguityScorer ambiguityScorer;

    public SettlementEvidenceBuilder(TrackedMatchObservationRepository trackedMatchObservationRepository,
                                     AmbiguityScorer ambiguityScorer) {
        this.trackedMatchObservationRepository = trackedMatchObservationRepository;
        this.ambiguityScorer = ambiguityScorer;
    }

    public Optional<SettlementEvidence> buildForBet(PaperTradeBet bet) {
        if (bet == null || bet.getId() == null || bet.getPlayer1Id() == null || bet.getPlayer2Id() == null) {
            return Optional.empty();
        }
        if (bet.getPlayer1Id() <= 0L || bet.getPlayer2Id() <= 0L || bet.getPlayer1Id().equals(bet.getPlayer2Id())) {
            return Optional.empty();
        }

        List<TrackedMatchObservation> trackedObservations = trackedMatchObservationRepository.findByBetIdOrderByObservedAtAsc(bet.getId());
        List<LiveObservation> liveObservations = new ArrayList<>();
        List<MirrorObservation> mirrorObservations = new ArrayList<>();
        List<StreamObservation> streamObservations = new ArrayList<>();
        boolean trackedAfterClose = false;

        for (TrackedMatchObservation trackedObservation : trackedObservations) {
            SourceId sourceId = resolveSourceId(trackedObservation.getSource(), trackedObservation.getSourceKind());
            MatchPhase phase = toMatchPhase(trackedObservation.getMatchPhase(),
                    trackedObservation.isLive(),
                    trackedObservation.isMatchCompleted() || trackedObservation.isResulted(),
                    trackedObservation.getLiveScore());
            ScoreState scoreState = parseScoreState(trackedObservation.getLiveScore(), trackedObservation.getScoreDetail());
            Instant observedAt = toInstant(trackedObservation.getObservedAt(), bet.getPlacedAt());
            boolean completionSignal = trackedObservation.isMatchCompleted()
                    || trackedObservation.isResulted()
                    || phase.isFinished();
            double confidence = effectiveObservationConfidence(
                    bet,
                    trackedObservation,
                    sourceId,
                    phase,
                    scoreState,
                    completionSignal
            );
            trackedAfterClose = trackedAfterClose || trackedObservation.isTrackedAfterClose();

            if (sourceId.tier() == TrustTier.T2_MIRROR) {
                mirrorObservations.add(new MirrorObservation(
                        sourceId,
                        observedAt,
                        confidence,
                        phase,
                        scoreState,
                        safeText(trackedObservation.getCorrelationId()),
                        completionSignal,
                        firstText(
                                trackedObservation.getExternalEventId(),
                                trackedObservation.getSourceFeedEventId(),
                                bet.getExternalEventId(),
                                bet.getEventKey()
                        )
                ));
                continue;
            }
            if (sourceId.tier() == TrustTier.T3_STREAM_CV) {
                streamObservations.add(new StreamObservation(
                        sourceId,
                        observedAt,
                        confidence,
                        phase,
                        scoreState,
                        safeText(trackedObservation.getCorrelationId()),
                        completionSignal,
                        safeText(trackedObservation.getSource()),
                        safeText(trackedObservation.getSourceFeedCode()),
                        0
                ));
                continue;
            }
            liveObservations.add(new LiveObservation(
                    sourceId,
                    observedAt,
                    confidence,
                    phase,
                    scoreState,
                    safeText(trackedObservation.getCorrelationId()),
                    completionSignal,
                    firstText(
                            trackedObservation.getSourceFeedEventId(),
                            trackedObservation.getExternalEventId(),
                            bet.getLockedExternalEventId(),
                            bet.getExternalEventId(),
                            bet.getEventKey()
                    ),
                    firstText(
                            bet.getLockedSourceFeedEventId(),
                            trackedObservation.getSourceFeedEventId(),
                            trackedObservation.getDedupeKey(),
                            bet.getDedupeKey(),
                            bet.getEventKey()
                    ),
                    trackedObservation.isDisplayed(),
                    trackedObservation.isResulted()
            ));
        }

        if (liveObservations.isEmpty() && mirrorObservations.isEmpty() && streamObservations.isEmpty()) {
            maybeAddFallbackLiveObservation(bet, liveObservations);
            trackedAfterClose = trackedAfterClose || bet.isTrackedAfterClose();
        }

        List<DatabaseCandidate> databaseCandidates = buildDatabaseCandidates(bet);
        CoverageState coverageState = resolveCoverageState(liveObservations, mirrorObservations, streamObservations, trackedAfterClose);
        Instant bundleAsOf = resolveBundleAsOf(bet, trackedObservations);
        IdentityLock identityLock = new IdentityLock(
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                toInstant(bet.getPlacedAt(), null),
                DEFAULT_AMBIGUITY_WINDOW,
                firstText(bet.getLockedExternalEventId(), bet.getExternalEventId(), bet.getEventKey()),
                firstText(bet.getLockedSourceFeedEventId(), bet.getLastSourceFeedEventId(), bet.getDedupeKey(), bet.getEventKey())
        );

        SettlementEvidence seed = new SettlementEvidence(
                bet.getId(),
                new TrackedEventId(firstText(bet.getEventKey(), bet.getDedupeKey(), "bet-" + bet.getId())),
                identityLock,
                liveObservations,
                mirrorObservations,
                streamObservations,
                List.of(),
                databaseCandidates,
                coverageState,
                List.of(),
                0.0,
                aggregateConfidence(liveObservations, mirrorObservations, streamObservations, bet.getLastScoreConfidence()),
                bundleAsOf
        );

        double ambiguityScore = ambiguityScorer.score(seed);
        return Optional.of(new SettlementEvidence(
                seed.betId(),
                seed.trackedEventId(),
                seed.identityLock(),
                seed.liveObservations(),
                seed.mirrorObservations(),
                seed.streamObservations(),
                seed.officialCandidates(),
                seed.databaseCandidates(),
                seed.coverageState(),
                List.of(),
                ambiguityScore,
                seed.confidence(),
                seed.bundleAsOf()
        ));
    }

    private List<DatabaseCandidate> buildDatabaseCandidates(PaperTradeBet bet) {
        if (!isResolvedDecision(bet)) {
            return List.of();
        }
        Instant observedAt = toInstant(firstNonNull(bet.getSettledAt(), bet.getLastObservedAt(), bet.getPlacedAt()), null);
        if (observedAt == null) {
            observedAt = Instant.now();
        }
        // Until the internal Match-row lookup is wired in Phase 02, shadow mode uses the resolved
        // legacy bet outcome as a provisional internal confirmation claim for contradiction checks.
        return List.of(new DatabaseCandidate(
                bet.getResultMatchId() != null ? bet.getResultMatchId() : bet.getId(),
                observedAt,
                observedAt.atZone(ZoneId.systemDefault()).toLocalDate(),
                bet.getPlayer1Id(),
                bet.getPlayer2Id(),
                bet.getWinnerPlayerId(),
                firstText(bet.getLockedExternalEventId(), bet.getExternalEventId(), bet.getEventKey()),
                clamp(defaultConfidenceForResolvedBet(bet), 0.0, 1.0),
                true,
                safeText(bet.getSettlementReason())
        ));
    }

    private void maybeAddFallbackLiveObservation(PaperTradeBet bet, List<LiveObservation> liveObservations) {
        if (!StringUtils.hasText(bet.getLastObservedScore()) && !StringUtils.hasText(bet.getLastObservedPhase())) {
            return;
        }
        MatchPhase phase = toMatchPhase(
                bet.getLastObservedPhase(),
                bet.isLiveAtPlacement() || StringUtils.hasText(bet.getLastObservedScore()),
                bet.isLastMatchCompleted() || bet.isLastObservationResulted(),
                bet.getLastObservedScore()
        );
        liveObservations.add(new LiveObservation(
                resolveSourceId(bet.getSource(), bet.getLastScoreSource()),
                toInstant(firstNonNull(bet.getLastObservedAt(), bet.getPlacedAt()), null),
                clamp(bet.getLastScoreConfidence() == null ? 0.65 : bet.getLastScoreConfidence(), 0.0, 1.0),
                phase,
                parseScoreState(bet.getLastObservedScore(), bet.getLastScoreDetail()),
                safeText(bet.getSettlementReason()),
                bet.isLastMatchCompleted() || bet.isLastObservationResulted() || phase.isFinished(),
                firstText(bet.getLockedExternalEventId(), bet.getExternalEventId(), bet.getEventKey()),
                firstText(bet.getLockedSourceFeedEventId(), bet.getLastSourceFeedEventId(), bet.getDedupeKey(), bet.getEventKey()),
                bet.isLastObservationDisplayed(),
                bet.isLastObservationResulted()
        ));
    }

    private CoverageState resolveCoverageState(List<LiveObservation> liveObservations,
                                              List<MirrorObservation> mirrorObservations,
                                              List<StreamObservation> streamObservations,
                                              boolean trackedAfterClose) {
        boolean hasObservation = !liveObservations.isEmpty() || !mirrorObservations.isEmpty() || !streamObservations.isEmpty();
        if (!hasObservation) {
            return CoverageState.DARK;
        }
        boolean completionSeen = liveObservations.stream().anyMatch(LiveObservation::completionSignal)
                || mirrorObservations.stream().anyMatch(MirrorObservation::completionSignal)
                || streamObservations.stream().anyMatch(StreamObservation::completionSignal);
        if (trackedAfterClose || completionSeen || (hasObservation && !liveObservations.isEmpty() && (!mirrorObservations.isEmpty() || !streamObservations.isEmpty()))) {
            return CoverageState.FULL;
        }
        return CoverageState.PARTIAL;
    }

    private Instant resolveBundleAsOf(PaperTradeBet bet, List<TrackedMatchObservation> trackedObservations) {
        if (!trackedObservations.isEmpty()) {
            TrackedMatchObservation latest = trackedObservations.get(trackedObservations.size() - 1);
            return toInstant(latest.getObservedAt(), bet.getLastObservedAt());
        }
        return toInstant(firstNonNull(bet.getSettledAt(), bet.getLastObservedAt(), bet.getPlacedAt()), null);
    }

    private double aggregateConfidence(List<LiveObservation> liveObservations,
                                       List<MirrorObservation> mirrorObservations,
                                       List<StreamObservation> streamObservations,
                                       Double fallbackConfidence) {
        List<Double> values = new ArrayList<>();
        liveObservations.forEach(observation -> values.add(observation.confidence()));
        mirrorObservations.forEach(observation -> values.add(observation.confidence()));
        streamObservations.forEach(observation -> values.add(observation.confidence()));
        if (values.isEmpty()) {
            return clamp(fallbackConfidence == null ? 0.0 : fallbackConfidence, 0.0, 1.0);
        }
        double total = values.stream().mapToDouble(Double::doubleValue).sum();
        return clamp(total / values.size(), 0.0, 1.0);
    }

    private SourceId resolveSourceId(String source, String sourceKind) {
        Optional<SourceId> direct = SourceId.fromValue(source);
        if (direct.isPresent()) {
            return direct.get();
        }
        String normalizedKind = safeText(sourceKind).toUpperCase(Locale.ROOT);
        String normalizedSource = safeText(source).toUpperCase(Locale.ROOT);
        String combined = normalizedKind + " " + normalizedSource;

        // HARD_ROCK_SCORE_STREAM is Hard Rock's structured Betradar score
        // endpoint, not the video/OCR Stream-CV pipeline. Resolve the
        // explicit source kind before the generic "STREAM" name check so a
        // terminal resulted row receives the single-source HR_TGT settlement
        // policy instead of waiting forever for Stream-CV consensus frames.
        if (normalizedKind.contains("SCORE_FEED")
                || normalizedSource.contains("HARD_ROCK_SCORE_STREAM")
                || combined.contains("BETRADAR_UF")
                || combined.contains("TARGET")) {
            return SourceId.HR_TGT;
        }
        if (combined.contains("SOFA")) {
            return SourceId.SOFASCORE;
        }
        if (combined.contains("AISCORE")) {
            return SourceId.AISCORE;
        }
        if (combined.contains("BETSAPI")) {
            return SourceId.BETSAPI;
        }
        if (combined.contains("STREAM") || combined.contains("OCR") || combined.contains("CV")) {
            return SourceId.STREAM_CV;
        }
        if (combined.contains("SCORE")) {
            return SourceId.HR_TGT;
        }
        return SourceId.HR_MKT;
    }

    /**
     * A targeted Hard Rock terminal row is stronger than its generic polling
     * confidence. The uplift is allowed only after the row has survived the
     * locked event/player identity checks used when observations are recorded.
     * This lets the explicit completion endpoint settle immediately while a
     * merely commanding in-play score remains below the automatic threshold.
     */
    private double effectiveObservationConfidence(PaperTradeBet bet,
                                                  TrackedMatchObservation observation,
                                                  SourceId sourceId,
                                                  MatchPhase phase,
                                                  ScoreState score,
                                                  boolean completionSignal) {
        double base = clamp(observation.getSourceConfidence(), 0.0, 1.0);
        if (!completionSignal || !identityAligned(bet, observation) || !hasWinnerShape(score)) {
            return base;
        }
        if (sourceId == SourceId.HR_TGT
                && (observation.isResulted() || observation.isMatchCompleted())
                && phase.isFinished()) {
            return Math.max(base, 0.98);
        }
        if (sourceId == SourceId.HR_TGT) {
            return Math.max(base, 0.96);
        }
        if (sourceId.tier() == TrustTier.T2_MIRROR || sourceId.tier() == TrustTier.T3_STREAM_CV) {
            return Math.max(base, 0.94);
        }
        return Math.max(base, 0.92);
    }

    private boolean identityAligned(PaperTradeBet bet, TrackedMatchObservation observation) {
        if (bet == null || observation == null) {
            return false;
        }
        boolean playersMatch = java.util.Objects.equals(bet.getPlayer1Id(), observation.getPlayer1Id())
                && java.util.Objects.equals(bet.getPlayer2Id(), observation.getPlayer2Id());
        String lockedEventId = firstText(
                bet.getLockedExternalEventId(),
                bet.getExternalEventId(),
                bet.getLockedSourceFeedEventId()
        );
        String observedEventId = firstText(
                observation.getExternalEventId(),
                observation.getSourceFeedEventId()
        );
        boolean eventMatch = StringUtils.hasText(lockedEventId)
                && StringUtils.hasText(observedEventId)
                && lockedEventId.equalsIgnoreCase(observedEventId);
        return playersMatch && (eventMatch || StringUtils.hasText(bet.getEventKey()));
    }

    private boolean hasWinnerShape(ScoreState score) {
        if (score == null) {
            return false;
        }
        if (score.gamesP1() != null && score.gamesP2() != null
                && !score.gamesP1().equals(score.gamesP2())) {
            return true;
        }
        return score.pointsP1() != null && score.pointsP2() != null
                && !score.pointsP1().equals(score.pointsP2());
    }

    private MatchPhase toMatchPhase(String rawPhase, boolean live, boolean completionSignal, String liveScore) {
        if (completionSignal || isFinishedPhase(rawPhase)) {
            return MatchPhase.FINISHED;
        }
        String phase = safeText(rawPhase).toUpperCase(Locale.ROOT);
        if (phase.contains("PRE")) {
            return MatchPhase.PREMATCH;
        }
        if (phase.contains("EARLY")) {
            return MatchPhase.LIVE_EARLY;
        }
        if (phase.contains("MID")) {
            return MatchPhase.LIVE_MID;
        }
        if (phase.contains("LATE")) {
            return MatchPhase.LIVE_LATE;
        }
        Integer setsP1 = null;
        Integer setsP2 = null;
        List<int[]> pairs = extractPairs(liveScore);
        if (!pairs.isEmpty() && isLikelySetScore(pairs.get(0)[0], pairs.get(0)[1])) {
            setsP1 = pairs.get(0)[0];
            setsP2 = pairs.get(0)[1];
        }
        int totalSets = (setsP1 == null || setsP2 == null) ? 0 : (setsP1 + setsP2);
        if (totalSets >= 4) {
            return MatchPhase.LIVE_LATE;
        }
        if (totalSets >= 2) {
            return MatchPhase.LIVE_MID;
        }
        if (live || StringUtils.hasText(liveScore)) {
            return MatchPhase.LIVE_EARLY;
        }
        return MatchPhase.UNKNOWN;
    }

    private boolean isFinishedPhase(String rawPhase) {
        String phase = safeText(rawPhase).toUpperCase(Locale.ROOT);
        return phase.contains("FINISH")
                || phase.contains("FINAL")
                || phase.contains("ENDED")
                || phase.contains("CLOSED")
                || phase.contains("SETTLED")
                || phase.contains("RESULT")
                || phase.contains("COMPLETE");
    }

    private ScoreState parseScoreState(String liveScore, String scoreDetail) {
        Integer setsP1 = null;
        Integer setsP2 = null;
        Integer pointsP1 = null;
        Integer pointsP2 = null;

        List<int[]> livePairs = extractPairs(liveScore);
        for (int[] pair : livePairs) {
            if (setsP1 == null && isLikelySetScore(pair[0], pair[1])) {
                setsP1 = pair[0];
                setsP2 = pair[1];
                continue;
            }
            if (pointsP1 == null && isLikelyPointScore(pair[0], pair[1])) {
                pointsP1 = pair[0];
                pointsP2 = pair[1];
            }
        }

        if (pointsP1 == null) {
            List<int[]> detailPairs = extractPairs(scoreDetail);
            if (!detailPairs.isEmpty()) {
                int[] last = detailPairs.get(detailPairs.size() - 1);
                if (isLikelyPointScore(last[0], last[1])) {
                    pointsP1 = last[0];
                    pointsP2 = last[1];
                }
            }
        }

        return new ScoreState(setsP1, setsP2, pointsP1, pointsP2, "");
    }

    private List<int[]> extractPairs(String raw) {
        List<int[]> pairs = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return pairs;
        }
        Matcher matcher = SCORE_PAIR_PATTERN.matcher(raw);
        while (matcher.find()) {
            pairs.add(new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
            });
        }
        return pairs;
    }

    private boolean isLikelySetScore(int left, int right) {
        return Math.max(left, right) <= 7;
    }

    private boolean isLikelyPointScore(int left, int right) {
        return Math.max(left, right) >= 8;
    }

    private boolean isResolvedDecision(PaperTradeBet bet) {
        return bet != null
                && (PaperTradeBet.STATUS_WON.equalsIgnoreCase(bet.getStatus())
                || PaperTradeBet.STATUS_LOST.equalsIgnoreCase(bet.getStatus())
                || PaperTradeBet.STATUS_PUSHED.equalsIgnoreCase(bet.getStatus())
                || PaperTradeBet.STATUS_VOIDED.equalsIgnoreCase(bet.getStatus())
                || bet.getWinnerPlayerId() != null
                || StringUtils.hasText(bet.getSettlementReason())
                || bet.getSettledAt() != null);
    }

    private double defaultConfidenceForResolvedBet(PaperTradeBet bet) {
        if (StringUtils.hasText(bet.getSettlementSource())) {
            String source = bet.getSettlementSource().trim().toUpperCase(Locale.ROOT);
            if (source.contains("OFFICIAL")) {
                return 0.92;
            }
            if (source.contains("DATABASE")) {
                return 0.88;
            }
            if (source.contains("SCORE")) {
                return 0.80;
            }
        }
        return 0.72;
    }

    private Instant toInstant(LocalDateTime value, LocalDateTime fallback) {
        LocalDateTime resolved = firstNonNull(value, fallback);
        if (resolved == null) {
            return Instant.now();
        }
        return resolved.atZone(ZoneId.systemDefault()).toInstant();
    }

    private <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
