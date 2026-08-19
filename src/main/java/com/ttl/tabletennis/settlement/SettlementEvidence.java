package com.ttl.tabletennis.settlement;

import com.ttl.tabletennis.scrape.SourceId;
import com.ttl.tabletennis.settlement.observation.DatabaseObservation;
import com.ttl.tabletennis.settlement.observation.LiveObservation;
import com.ttl.tabletennis.settlement.observation.MirrorObservation;
import com.ttl.tabletennis.settlement.observation.Observation;
import com.ttl.tabletennis.settlement.observation.OfficialObservation;
import com.ttl.tabletennis.settlement.observation.MatchPhase;
import com.ttl.tabletennis.settlement.observation.ScoreState;
import com.ttl.tabletennis.settlement.observation.StreamObservation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SettlementEvidence(long betId,
                                 TrackedEventId trackedEventId,
                                 IdentityLock identityLock,
                                 List<LiveObservation> liveObservations,
                                 List<MirrorObservation> mirrorObservations,
                                 List<StreamObservation> streamObservations,
                                 List<OfficialCandidate> officialCandidates,
                                 List<DatabaseCandidate> databaseCandidates,
                                 CoverageState coverageState,
                                 List<Contradiction> contradictions,
                                 double ambiguityScore,
                                 double confidence,
                                 Instant bundleAsOf) {

    private static final Comparator<Observation> OBSERVED_AT_COMPARATOR =
            Comparator.comparing(Observation::observedAt);

    private static final Comparator<OfficialCandidate> OFFICIAL_CANDIDATE_COMPARATOR =
            Comparator.comparing(OfficialCandidate::observedAt);

    private static final Comparator<DatabaseCandidate> DATABASE_CANDIDATE_COMPARATOR =
            Comparator.comparing(DatabaseCandidate::observedAt);

    public SettlementEvidence {
        if (betId <= 0L) {
            throw new IllegalArgumentException("betId must be positive");
        }
        trackedEventId = Objects.requireNonNull(trackedEventId, "trackedEventId must not be null");
        identityLock = Objects.requireNonNull(identityLock, "identityLock must not be null");
        liveObservations = sortedCopy(liveObservations, OBSERVED_AT_COMPARATOR);
        mirrorObservations = sortedCopy(mirrorObservations, OBSERVED_AT_COMPARATOR);
        streamObservations = sortedCopy(streamObservations, OBSERVED_AT_COMPARATOR);
        officialCandidates = sortedCopy(officialCandidates, OFFICIAL_CANDIDATE_COMPARATOR);
        databaseCandidates = sortedCopy(databaseCandidates, DATABASE_CANDIDATE_COMPARATOR);
        contradictions = copyList(contradictions);
        coverageState = coverageState == null ? CoverageState.DARK : coverageState;
        if (ambiguityScore < 0.0 || ambiguityScore > 1.0) {
            throw new IllegalArgumentException("ambiguityScore must be between 0.0 and 1.0");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        bundleAsOf = Objects.requireNonNull(bundleAsOf, "bundleAsOf must not be null");
    }

    public List<Observation> allObservations() {
        List<Observation> combined = new ArrayList<>(liveObservations.size()
                + mirrorObservations.size()
                + streamObservations.size()
                + officialCandidates.size()
                + databaseCandidates.size());
        combined.addAll(liveObservations);
        combined.addAll(mirrorObservations);
        combined.addAll(streamObservations);
        combined.addAll(officialObservations());
        combined.addAll(databaseObservations());
        combined.sort(OBSERVED_AT_COMPARATOR);
        return List.copyOf(combined);
    }

    public List<OfficialObservation> officialObservations() {
        if (officialCandidates.isEmpty()) {
            return List.of();
        }
        return officialCandidates.stream()
                .map(candidate -> new OfficialObservation(
                        candidate.source(),
                        candidate.observedAt(),
                        candidate.confidence(),
                        candidate.completed() ? MatchPhase.FINISHED : MatchPhase.UNKNOWN,
                        ScoreState.unknown(),
                        candidate.rawPayloadRef(),
                        candidate.completed(),
                        candidate.player1Id(),
                        candidate.player2Id(),
                        candidate.winnerPlayerId()
                ))
                .toList();
    }

    public List<DatabaseObservation> databaseObservations() {
        if (databaseCandidates.isEmpty()) {
            return List.of();
        }
        return databaseCandidates.stream()
                .map(candidate -> new DatabaseObservation(
                        candidate.observedAt(),
                        candidate.confidence(),
                        candidate.completed() ? MatchPhase.FINISHED : MatchPhase.UNKNOWN,
                        ScoreState.unknown(),
                        candidate.rawPayloadRef(),
                        candidate.completed(),
                        candidate.player1Id(),
                        candidate.player2Id(),
                        candidate.winnerPlayerId()
                ))
                .toList();
    }

    public Set<SourceId> distinctSources() {
        LinkedHashSet<SourceId> sources = new LinkedHashSet<>();
        liveObservations.forEach(observation -> sources.add(observation.source()));
        mirrorObservations.forEach(observation -> sources.add(observation.source()));
        streamObservations.forEach(observation -> sources.add(observation.source()));
        officialCandidates.forEach(candidate -> sources.add(candidate.source()));
        databaseCandidates.forEach(candidate -> sources.add(candidate.source()));
        return Set.copyOf(sources);
    }

    public boolean hasAnyObservation() {
        return !liveObservations.isEmpty() || !mirrorObservations.isEmpty() || !streamObservations.isEmpty();
    }

    public boolean hasContradictions() {
        return !contradictions.isEmpty();
    }

    private static <T> List<T> copyList(List<T> items) {
        return items == null || items.isEmpty() ? List.of() : List.copyOf(items);
    }

    private static <T> List<T> sortedCopy(List<T> items, Comparator<? super T> comparator) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(items);
        copy.sort(comparator);
        return List.copyOf(copy);
    }
}
