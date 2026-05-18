package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.PlayerAlias;
import com.ttl.tabletennis.repository.PlayerAliasRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.util.NameUtils;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class PlayerCanonicaliser {

    static final double AUTO_ACCEPT_THRESHOLD = 0.92;
    private static final double SCORE_EPSILON = 1.0e-9;

    private static final Comparator<CanonicalPlayerMatch> MATCH_RANKING = Comparator
            .comparingDouble(CanonicalPlayerMatch::similarity).reversed()
            .thenComparing(CanonicalPlayerMatch::countryMatched, Comparator.reverseOrder())
            .thenComparing(match -> match.firstSeenAt() == null ? Instant.MAX : match.firstSeenAt())
            .thenComparing(match -> match.player().getId() == null ? Long.MAX_VALUE : match.player().getId());

    private final PlayerRepository playerRepository;
    private final PlayerAliasRepository playerAliasRepository;
    private final JaroWinklerSimilarity similarity = new JaroWinklerSimilarity();

    public PlayerCanonicaliser(PlayerRepository playerRepository,
                               PlayerAliasRepository playerAliasRepository) {
        this.playerRepository = playerRepository;
        this.playerAliasRepository = playerAliasRepository;
    }

    public CanonicalisationResult canonicalise(String rawName) {
        return canonicalise(new CanonicalisationRequest(rawName, null));
    }

    public CanonicalisationResult canonicalise(String rawName, String countryCode) {
        return canonicalise(new CanonicalisationRequest(rawName, countryCode));
    }

    public CanonicalisationResult canonicalise(CanonicalisationRequest request) {
        return canonicalise(request, loadCandidates());
    }

    CanonicalisationResult canonicalise(CanonicalisationRequest request,
                                        Collection<PlayerCandidate> candidates) {
        CanonicalisationRequest normalizedRequest = request == null
                ? new CanonicalisationRequest("", null)
                : request;
        String normalizedInput = NameUtils.normalizeForLookup(normalizedRequest.rawName());
        if (normalizedInput.isBlank()) {
            return new CanonicalisationResult(
                    normalizedRequest.rawName(),
                    normalizedInput,
                    Optional.empty(),
                    List.of(),
                    false
            );
        }

        Map<Long, CanonicalPlayerMatch> bestByPlayerId = new LinkedHashMap<>();
        if (candidates != null) {
            for (PlayerCandidate candidate : candidates) {
                CanonicalPlayerMatch scored = scoreCandidate(normalizedRequest, normalizedInput, candidate);
                if (scored == null) {
                    continue;
                }
                Long playerId = scored.player().getId();
                if (playerId == null) {
                    continue;
                }
                bestByPlayerId.merge(playerId, scored, this::pickBetter);
            }
        }

        List<CanonicalPlayerMatch> ranked = bestByPlayerId.values().stream()
                .sorted(MATCH_RANKING)
                .toList();
        if (ranked.isEmpty()) {
            return new CanonicalisationResult(
                    normalizedRequest.rawName(),
                    normalizedInput,
                    Optional.empty(),
                    ranked,
                    false
            );
        }

        boolean ambiguous = ranked.size() > 1 && isAmbiguousTie(ranked.get(0), ranked.get(1));
        Optional<CanonicalPlayerMatch> acceptedMatch = ambiguous
                ? Optional.empty()
                : Optional.of(ranked.get(0));
        return new CanonicalisationResult(
                normalizedRequest.rawName(),
                normalizedInput,
                acceptedMatch,
                ranked,
                ambiguous
        );
    }

    private List<PlayerCandidate> loadCandidates() {
        List<PlayerCandidate> candidates = new ArrayList<>();
        for (Player player : playerRepository.findAllByOrderByLastNameAscFirstNameAsc()) {
            candidates.add(new PlayerCandidate(
                    player,
                    player.getName(),
                    player.getNormalizedName(),
                    null,
                    null,
                    CandidateSource.PLAYER_NAME
            ));
        }
        for (PlayerAlias alias : playerAliasRepository.findAllWithPlayerOrderByAliasNameAsc()) {
            candidates.add(new PlayerCandidate(
                    alias.getPlayer(),
                    alias.getAliasName(),
                    alias.getNormalizedAlias(),
                    null,
                    toInstant(alias.getCreatedAt()),
                    CandidateSource.ALIAS
            ));
        }
        return candidates;
    }

    private CanonicalPlayerMatch scoreCandidate(CanonicalisationRequest request,
                                                String normalizedInput,
                                                PlayerCandidate candidate) {
        if (candidate == null || candidate.player() == null) {
            return null;
        }
        String candidateNormalized = candidate.normalizedCandidateName();
        if (candidateNormalized == null || candidateNormalized.isBlank()) {
            candidateNormalized = NameUtils.normalizeForLookup(candidate.candidateName());
        }
        if (candidateNormalized.isBlank()) {
            return null;
        }

        Double scoreValue = similarity.apply(normalizedInput, candidateNormalized);
        double score = normalizedInput.equals(candidateNormalized) ? 1.0 : (scoreValue == null ? 0.0 : scoreValue);
        if (score < AUTO_ACCEPT_THRESHOLD) {
            return null;
        }

        return new CanonicalPlayerMatch(
                candidate.player(),
                candidate.candidateName(),
                candidateNormalized,
                score,
                candidate.countryCode(),
                countryMatched(request.countryCode(), candidate.countryCode()),
                candidate.firstSeenAt(),
                candidate.source()
        );
    }

    private CanonicalPlayerMatch pickBetter(CanonicalPlayerMatch left, CanonicalPlayerMatch right) {
        return MATCH_RANKING.compare(left, right) <= 0 ? left : right;
    }

    private boolean isAmbiguousTie(CanonicalPlayerMatch left, CanonicalPlayerMatch right) {
        return nearlyEqual(left.similarity(), right.similarity())
                && left.countryMatched() == right.countryMatched()
                && Objects.equals(left.firstSeenAt(), right.firstSeenAt());
    }

    private boolean nearlyEqual(double a, double b) {
        return Math.abs(a - b) <= SCORE_EPSILON;
    }

    private boolean countryMatched(String requestCountryCode, String candidateCountryCode) {
        if (requestCountryCode == null || requestCountryCode.isBlank()
                || candidateCountryCode == null || candidateCountryCode.isBlank()) {
            return false;
        }
        return requestCountryCode.trim().toUpperCase(Locale.ROOT)
                .equals(candidateCountryCode.trim().toUpperCase(Locale.ROOT));
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record CanonicalisationRequest(String rawName, String countryCode) {

        public CanonicalisationRequest {
            rawName = rawName == null ? "" : rawName.trim();
            countryCode = countryCode == null ? null : countryCode.trim();
        }
    }

    public record CanonicalisationResult(String rawName,
                                         String normalizedInput,
                                         Optional<CanonicalPlayerMatch> acceptedMatch,
                                         List<CanonicalPlayerMatch> rankedCandidates,
                                         boolean ambiguous) {

        public CanonicalisationResult {
            rawName = rawName == null ? "" : rawName;
            normalizedInput = normalizedInput == null ? "" : normalizedInput;
            acceptedMatch = acceptedMatch == null ? Optional.empty() : acceptedMatch;
            rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
        }

        public boolean resolved() {
            return acceptedMatch.isPresent();
        }
    }

    public record CanonicalPlayerMatch(Player player,
                                       String matchedName,
                                       String normalizedMatchedName,
                                       double similarity,
                                       String countryCode,
                                       boolean countryMatched,
                                       Instant firstSeenAt,
                                       CandidateSource source) {
    }

    static record PlayerCandidate(Player player,
                                  String candidateName,
                                  String normalizedCandidateName,
                                  String countryCode,
                                  Instant firstSeenAt,
                                  CandidateSource source) {
    }

    public enum CandidateSource {
        PLAYER_NAME,
        ALIAS
    }
}
