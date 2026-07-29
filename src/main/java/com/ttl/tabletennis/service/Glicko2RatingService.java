package com.ttl.tabletennis.service;

import com.ttl.tabletennis.analytics.Glicko2;
import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.RatingSnapshot;
import com.ttl.tabletennis.dto.Glicko2RebuildDto;
import com.ttl.tabletennis.dto.Glicko2TauTuningDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.repository.RatingSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class Glicko2RatingService {

    private static final String RATING_SYSTEM = "GLICKO2";
    private static final LocalDate MIN_REASONABLE_MATCH_DATE = LocalDate.of(1990, 1, 1);

    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;
    private final RatingSnapshotRepository ratingSnapshotRepository;

    @Value("${ttl.glicko2.tau:0.5}")
    private double tau;

    @Value("${ttl.glicko2.defaultRating:1500.0}")
    private double defaultRating;

    @Value("${ttl.glicko2.defaultRd:350.0}")
    private double defaultRd;

    @Value("${ttl.glicko2.defaultVolatility:0.06}")
    private double defaultVolatility;

    @Value("${ttl.glicko2.periodDays:7}")
    private int periodDays;

    public Glicko2RatingService(MatchRepository matchRepository,
                                PlayerRepository playerRepository,
                                RatingSnapshotRepository ratingSnapshotRepository) {
        this.matchRepository = matchRepository;
        this.playerRepository = playerRepository;
        this.ratingSnapshotRepository = ratingSnapshotRepository;
    }

    @Transactional
    public Glicko2RebuildDto rebuild(LocalDate fromDate, LocalDate toDate) {
        LocalDate firstMatchDate = matchRepository.findFirstCompletedMatchDate();
        LocalDate lastMatchDate = matchRepository.findLastCompletedMatchDate();
        if (firstMatchDate == null || lastMatchDate == null) {
            return new Glicko2RebuildDto(null, null, 0, 0, 0, tau);
        }

        LocalDate maxReasonableDate = LocalDate.now().plusDays(1);
        LocalDate clampedFirstMatchDate = firstMatchDate.isBefore(MIN_REASONABLE_MATCH_DATE)
                ? MIN_REASONABLE_MATCH_DATE
                : firstMatchDate;
        LocalDate clampedLastMatchDate = lastMatchDate.isAfter(maxReasonableDate)
                ? maxReasonableDate
                : lastMatchDate;
        if (clampedLastMatchDate.isBefore(clampedFirstMatchDate)) {
            return new Glicko2RebuildDto(null, null, 0, 0, 0, tau);
        }

        LocalDate from = fromDate == null ? clampedFirstMatchDate : fromDate;
        LocalDate to = toDate == null ? clampedLastMatchDate : toDate;
        if (from.isBefore(MIN_REASONABLE_MATCH_DATE)) {
            from = MIN_REASONABLE_MATCH_DATE;
        }
        if (to.isAfter(maxReasonableDate)) {
            to = maxReasonableDate;
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("toDate cannot be before fromDate");
        }

        List<Player> players = playerRepository.findAllByOrderByLastNameAscFirstNameAsc();
        Map<Long, Glicko2.Rating> ratingByPlayer = new HashMap<>();
        for (Player player : players) {
            ratingByPlayer.put(player.getId(), new Glicko2.Rating(defaultRating, defaultRd, defaultVolatility));
        }

        ratingSnapshotRepository.deleteByRatingSystem(RATING_SYSTEM);
        ratingSnapshotRepository.flush();

        int processedPeriods = 0;
        long snapshotsWritten = 0;

        int daysPerPeriod = Math.max(1, periodDays);
        List<Match> completedMatches = matchRepository.findCompletedMatchesBetween(from, to);
        List<List<Match>> periods = partitionMatchesByPeriod(completedMatches, from, to, daysPerPeriod);

        for (int periodIndex = 0; periodIndex < periods.size(); periodIndex++) {
            LocalDate periodEnd = from.plusDays(((long) periodIndex + 1L) * daysPerPeriod - 1L);
            if (periodEnd.isAfter(to)) {
                periodEnd = to;
            }

            List<Match> periodMatches = periods.get(periodIndex);
            Map<Long, List<Glicko2.OpponentResult>> outcomes = buildOutcomes(periodMatches, ratingByPlayer);

            List<RatingSnapshot> snapshots = new ArrayList<>(players.size());
            for (Player player : players) {
                Glicko2.Rating current = ratingByPlayer.getOrDefault(
                        player.getId(),
                        new Glicko2.Rating(defaultRating, defaultRd, defaultVolatility)
                );
                List<Glicko2.OpponentResult> playerResults = outcomes.getOrDefault(player.getId(), List.of());
                Glicko2.Rating updated = Glicko2.update(current, playerResults, tau);

                ratingByPlayer.put(player.getId(), updated);

                RatingSnapshot snapshot = new RatingSnapshot();
                snapshot.setPlayer(player);
                snapshot.setSnapshotDate(periodEnd);
                snapshot.setRating(updated.rating());
                snapshot.setRatingDeviation(updated.ratingDeviation());
                snapshot.setVolatility(updated.volatility());
                snapshot.setRatingSystem(RATING_SYSTEM);
                snapshots.add(snapshot);
            }

            ratingSnapshotRepository.saveAll(snapshots);
            snapshotsWritten += snapshots.size();
            processedPeriods++;
        }

        return new Glicko2RebuildDto(from, to, processedPeriods, players.size(), snapshotsWritten, tau);
    }

    public Glicko2TauTuningDto tuneTau(LocalDate fromDate, LocalDate toDate, List<Double> tauCandidates) {
        LocalDate firstMatchDate = matchRepository.findFirstCompletedMatchDate();
        LocalDate lastMatchDate = matchRepository.findLastCompletedMatchDate();
        if (firstMatchDate == null || lastMatchDate == null) {
            return new Glicko2TauTuningDto(null, null, tau, List.of());
        }

        LocalDate maxReasonableDate = LocalDate.now().plusDays(1);
        LocalDate from = fromDate == null ? firstMatchDate : fromDate;
        LocalDate to = toDate == null ? lastMatchDate : toDate;
        if (from.isBefore(MIN_REASONABLE_MATCH_DATE)) {
            from = MIN_REASONABLE_MATCH_DATE;
        }
        if (to.isAfter(maxReasonableDate)) {
            to = maxReasonableDate;
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("toDate cannot be before fromDate");
        }

        List<Double> candidates = sanitizeTauCandidates(tauCandidates);
        if (candidates.isEmpty()) {
            return new Glicko2TauTuningDto(from, to, tau, List.of());
        }

        List<Player> players = playerRepository.findAllByOrderByLastNameAscFirstNameAsc();
        List<Match> matches = matchRepository.findCompletedMatchesBetween(from, to);
        if (matches.isEmpty()) {
            List<Glicko2TauTuningDto.CandidateScoreDto> emptyScores = candidates.stream()
                    .map(c -> new Glicko2TauTuningDto.CandidateScoreDto(c, 0.0, 0.0, 0))
                    .toList();
            return new Glicko2TauTuningDto(from, to, candidates.get(0), emptyScores);
        }

        int daysPerPeriod = Math.max(1, periodDays);
        List<List<Match>> periods = partitionMatchesByPeriod(matches, from, to, daysPerPeriod);
        List<Glicko2TauTuningDto.CandidateScoreDto> candidateScores = new ArrayList<>(candidates.size());

        for (double candidateTau : candidates) {
            TauEvaluation eval = evaluateTau(players, periods, candidateTau);
            candidateScores.add(new Glicko2TauTuningDto.CandidateScoreDto(
                    candidateTau,
                    eval.averageLogLoss(),
                    eval.averageBrierScore(),
                    eval.predictions()
            ));
        }

        List<Glicko2TauTuningDto.CandidateScoreDto> ranked = candidateScores.stream()
                .sorted((a, b) -> {
                    int c = Double.compare(a.averageLogLoss(), b.averageLogLoss());
                    if (c != 0) return c;
                    c = Double.compare(a.averageBrierScore(), b.averageBrierScore());
                    if (c != 0) return c;
                    return Double.compare(a.tau(), b.tau());
                })
                .toList();

        double bestTau = ranked.get(0).tau();
        return new Glicko2TauTuningDto(from, to, bestTau, ranked);
    }

    private Map<Long, List<Glicko2.OpponentResult>> buildOutcomes(List<Match> matches,
                                                                   Map<Long, Glicko2.Rating> ratingsByPlayer) {
        Map<Long, List<Glicko2.OpponentResult>> outcomes = new HashMap<>();
        for (Match match : matches) {
            if (match.getPlayer1() == null || match.getPlayer2() == null) {
                continue;
            }

            Long p1Id = match.getPlayer1().getId();
            Long p2Id = match.getPlayer2().getId();
            if (p1Id == null || p2Id == null) {
                continue;
            }

            Glicko2.Rating p1Rating = ratingsByPlayer.getOrDefault(
                    p1Id,
                    new Glicko2.Rating(defaultRating, defaultRd, defaultVolatility)
            );
            Glicko2.Rating p2Rating = ratingsByPlayer.getOrDefault(
                    p2Id,
                    new Glicko2.Rating(defaultRating, defaultRd, defaultVolatility)
            );

            double score1;
            double score2;
            if (match.getWinnerPlayerId() == null) {
                score1 = 0.5;
                score2 = 0.5;
            } else if (match.getWinnerPlayerId().equals(p1Id)) {
                score1 = 1.0;
                score2 = 0.0;
            } else if (match.getWinnerPlayerId().equals(p2Id)) {
                score1 = 0.0;
                score2 = 1.0;
            } else {
                score1 = 0.5;
                score2 = 0.5;
            }

            outcomes.computeIfAbsent(p1Id, ignored -> new ArrayList<>())
                    .add(new Glicko2.OpponentResult(p2Rating.rating(), p2Rating.ratingDeviation(), score1));

            outcomes.computeIfAbsent(p2Id, ignored -> new ArrayList<>())
                    .add(new Glicko2.OpponentResult(p1Rating.rating(), p1Rating.ratingDeviation(), score2));
        }
        return outcomes;
    }

    private TauEvaluation evaluateTau(List<Player> players,
                                      List<List<Match>> periods,
                                      double candidateTau) {
        Map<Long, Glicko2.Rating> ratingByPlayer = new HashMap<>();
        for (Player player : players) {
            ratingByPlayer.put(player.getId(), new Glicko2.Rating(defaultRating, defaultRd, defaultVolatility));
        }

        double logLossSum = 0.0;
        double brierSum = 0.0;
        long predictions = 0L;

        for (List<Match> periodMatches : periods) {
            for (Match match : periodMatches) {
                if (match.getPlayer1() == null || match.getPlayer2() == null) continue;
                Long p1Id = match.getPlayer1().getId();
                Long p2Id = match.getPlayer2().getId();
                if (p1Id == null || p2Id == null) continue;

                Glicko2.Rating p1 = ratingByPlayer.getOrDefault(p1Id, new Glicko2.Rating(defaultRating, defaultRd, defaultVolatility));
                Glicko2.Rating p2 = ratingByPlayer.getOrDefault(p2Id, new Glicko2.Rating(defaultRating, defaultRd, defaultVolatility));

                double probability = clampProbability(Glicko2.expectedScore(
                        p1.rating(),
                        p1.ratingDeviation(),
                        p2.rating(),
                        p2.ratingDeviation()
                ));

                double actualScore = resolveActualScore(match, p1Id, p2Id);
                logLossSum += -(actualScore * Math.log(probability) + (1.0 - actualScore) * Math.log(1.0 - probability));
                brierSum += Math.pow(probability - actualScore, 2);
                predictions++;
            }

            Map<Long, List<Glicko2.OpponentResult>> outcomes = buildOutcomes(periodMatches, ratingByPlayer);
            for (Player player : players) {
                Long playerId = player.getId();
                if (playerId == null) continue;
                Glicko2.Rating current = ratingByPlayer.getOrDefault(
                        playerId,
                        new Glicko2.Rating(defaultRating, defaultRd, defaultVolatility)
                );
                List<Glicko2.OpponentResult> playerResults = outcomes.getOrDefault(playerId, List.of());
                ratingByPlayer.put(playerId, Glicko2.update(current, playerResults, candidateTau));
            }
        }

        if (predictions == 0) return new TauEvaluation(0.0, 0.0, 0);
        return new TauEvaluation(logLossSum / predictions, brierSum / predictions, predictions);
    }

    private List<Double> sanitizeTauCandidates(List<Double> tauCandidates) {
        List<Double> source = (tauCandidates == null || tauCandidates.isEmpty())
                ? List.of(0.3, 0.5, 0.7, 1.0)
                : tauCandidates;

        List<Double> out = source.stream()
                .filter(Objects::nonNull)
                .map(Double::doubleValue)
                .filter(v -> v > 0.0)
                .distinct()
                .sorted()
                .toList();

        if (!out.isEmpty()) {
            return out;
        }
        return List.of(Math.max(0.05, tau));
    }

    private List<List<Match>> partitionMatchesByPeriod(List<Match> matches,
                                                       LocalDate fromDate,
                                                       LocalDate toDate,
                                                       int daysPerPeriod) {
        int periodCount = (int) (ChronoUnit.DAYS.between(fromDate, toDate) / daysPerPeriod) + 1;
        List<List<Match>> buckets = new ArrayList<>(periodCount);
        for (int i = 0; i < periodCount; i++) {
            buckets.add(new ArrayList<>());
        }
        for (Match match : matches) {
            if (match.getDate() == null) continue;
            long delta = ChronoUnit.DAYS.between(fromDate, match.getDate());
            if (delta < 0) continue;
            int idx = (int) (delta / daysPerPeriod);
            if (idx >= 0 && idx < buckets.size()) {
                buckets.get(idx).add(match);
            }
        }
        return buckets;
    }

    private double resolveActualScore(Match match, Long p1Id, Long p2Id) {
        Long winner = match.getWinnerPlayerId();
        if (winner == null) {
            return 0.5;
        }
        if (winner.equals(p1Id)) {
            return 1.0;
        }
        if (winner.equals(p2Id)) {
            return 0.0;
        }
        return 0.5;
    }

    private double clampProbability(double probability) {
        double eps = 1e-6;
        if (probability <= eps) return eps;
        if (probability >= 1.0 - eps) return 1.0 - eps;
        return probability;
    }

    private record TauEvaluation(double averageLogLoss,
                                 double averageBrierScore,
                                 long predictions) {
    }
}
