package com.ttl.tabletennis.service;

import com.ttl.tabletennis.analytics.TrueSkill2;
import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.PlayerRatingTs2;
import com.ttl.tabletennis.dto.TrueSkill2MatchupDto;
import com.ttl.tabletennis.dto.TrueSkill2RatingDto;
import com.ttl.tabletennis.dto.TrueSkill2RebuildDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRatingTs2Repository;
import com.ttl.tabletennis.repository.PlayerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class TrueSkill2Service {

    private static final LocalDate MIN_REASONABLE_MATCH_DATE = LocalDate.of(1990, 1, 1);

    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;
    private final PlayerRatingTs2Repository playerRatingTs2Repository;

    @Value("${ttl.trueskill2.defaultMu:25.0}")
    private double defaultMu;

    @Value("${ttl.trueskill2.defaultSigma:8.3333333333}")
    private double defaultSigma;

    @Value("${ttl.trueskill2.beta:4.1666666667}")
    private double beta;

    @Value("${ttl.trueskill2.dynamicFactor:0.0833333333}")
    private double dynamicFactor;

    @Value("${ttl.trueskill2.sigmaFloor:0.75}")
    private double sigmaFloor;

    public TrueSkill2Service(MatchRepository matchRepository,
                             PlayerRepository playerRepository,
                             PlayerRatingTs2Repository playerRatingTs2Repository) {
        this.matchRepository = matchRepository;
        this.playerRepository = playerRepository;
        this.playerRatingTs2Repository = playerRatingTs2Repository;
    }

    @Transactional
    public TrueSkill2RebuildDto rebuild(LocalDate fromDate, LocalDate toDate) {
        LocalDate firstMatchDate = matchRepository.findFirstCompletedMatchDate();
        LocalDate lastMatchDate = matchRepository.findLastCompletedMatchDate();
        if (firstMatchDate == null || lastMatchDate == null) {
            return new TrueSkill2RebuildDto(null, null, 0, 0, 0, 0, beta);
        }

        LocalDate maxReasonableDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        LocalDate clampedFirstMatchDate = firstMatchDate.isBefore(MIN_REASONABLE_MATCH_DATE)
                ? MIN_REASONABLE_MATCH_DATE
                : firstMatchDate;
        LocalDate clampedLastMatchDate = lastMatchDate.isAfter(maxReasonableDate)
                ? maxReasonableDate
                : lastMatchDate;
        if (clampedLastMatchDate.isBefore(clampedFirstMatchDate)) {
            return new TrueSkill2RebuildDto(null, null, 0, 0, 0, 0, beta);
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
        Map<Long, RatingState> ratingsByPlayer = new HashMap<>();
        for (Player player : players) {
            if (player.getId() != null) {
                ratingsByPlayer.put(player.getId(), RatingState.initial(defaultMu, defaultSigma));
            }
        }

        List<Match> matches = matchRepository.findCompletedMatchesBetween(from, to);
        Map<LocalDate, List<Match>> matchesByDate = matches.stream()
                .filter(m -> m.getDate() != null)
                .collect(Collectors.groupingBy(Match::getDate));

        playerRatingTs2Repository.deleteAllInBatch();

        long snapshotsWritten = 0L;
        long matchesProcessed = 0L;
        int daysProcessed = 0;
        TrueSkill2.Parameters parameters = parameters();

        LocalDate day = from;
        while (!day.isAfter(to)) {
            List<Match> dayMatches = matchesByDate.getOrDefault(day, List.of());
            for (Match match : dayMatches) {
                if (applyMatch(match, ratingsByPlayer, parameters)) {
                    matchesProcessed++;
                }
            }

            List<PlayerRatingTs2> snapshots = new ArrayList<>(players.size());
            for (Player player : players) {
                if (player.getId() == null) {
                    continue;
                }
                RatingState rating = ratingsByPlayer.getOrDefault(player.getId(), RatingState.initial(defaultMu, defaultSigma));
                snapshots.add(toEntity(player.getId(), day, rating));
            }
            playerRatingTs2Repository.saveAll(snapshots);
            snapshotsWritten += snapshots.size();
            daysProcessed++;
            day = day.plusDays(1);
        }

        return new TrueSkill2RebuildDto(from, to, daysProcessed, players.size(), matchesProcessed, snapshotsWritten, beta);
    }

    public TrueSkill2RatingDto ratingForPlayer(Long playerId, LocalDate asOfDate) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId is required");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown player: " + playerId));
        LocalDate effectiveDate = asOfDate == null ? LocalDate.now(ZoneOffset.UTC) : asOfDate;

        return playerRatingTs2Repository
                .findTopByPlayerIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(playerId, effectiveDate)
                .map(snapshot -> toDto(snapshot, player))
                .orElseGet(() -> defaultRating(player, effectiveDate));
    }

    public TrueSkill2MatchupDto matchup(Long player1Id, Long player2Id, LocalDate asOfDate) {
        if (player1Id == null || player2Id == null) {
            throw new IllegalArgumentException("player ids are required");
        }
        if (player1Id.equals(player2Id)) {
            throw new IllegalArgumentException("Select two different players");
        }
        LocalDate effectiveDate = asOfDate == null ? LocalDate.now(ZoneOffset.UTC) : asOfDate;
        TrueSkill2RatingDto p1 = ratingForPlayer(player1Id, effectiveDate);
        TrueSkill2RatingDto p2 = ratingForPlayer(player2Id, effectiveDate);
        double probability = TrueSkill2.winProbability(
                new TrueSkill2.Rating(p1.mu(), p1.sigma()),
                new TrueSkill2.Rating(p2.mu(), p2.sigma()),
                parameters()
        );

        return new TrueSkill2MatchupDto(
                effectiveDate,
                probability,
                p1.mu() - p2.mu(),
                p1.conservativeSkill() - p2.conservativeSkill(),
                p1,
                p2
        );
    }

    public List<TrueSkill2RatingDto> historyForPlayer(Long playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId is required");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown player: " + playerId));
        return playerRatingTs2Repository.findByPlayerIdOrderBySnapshotDateAsc(playerId)
                .stream()
                .map(snapshot -> toDto(snapshot, player))
                .toList();
    }

    private boolean applyMatch(Match match,
                               Map<Long, RatingState> ratingsByPlayer,
                               TrueSkill2.Parameters parameters) {
        if (match.getPlayer1() == null || match.getPlayer2() == null || match.getWinnerPlayerId() == null) {
            return false;
        }
        Long player1Id = match.getPlayer1().getId();
        Long player2Id = match.getPlayer2().getId();
        Long winnerId = match.getWinnerPlayerId();
        if (player1Id == null || player2Id == null || (!winnerId.equals(player1Id) && !winnerId.equals(player2Id))) {
            return false;
        }

        Long loserId = winnerId.equals(player1Id) ? player2Id : player1Id;
        RatingState winner = ratingsByPlayer.getOrDefault(winnerId, RatingState.initial(defaultMu, defaultSigma));
        RatingState loser = ratingsByPlayer.getOrDefault(loserId, RatingState.initial(defaultMu, defaultSigma));
        TrueSkill2.Update update = TrueSkill2.updateWinner(winner.rating(), loser.rating(), parameters);

        ratingsByPlayer.put(winnerId, winner.withWin(update.winner(), match.getDate()));
        ratingsByPlayer.put(loserId, loser.withLoss(update.loser(), match.getDate()));
        return true;
    }

    private PlayerRatingTs2 toEntity(Long playerId, LocalDate snapshotDate, RatingState rating) {
        PlayerRatingTs2 snapshot = new PlayerRatingTs2();
        snapshot.setPlayerId(playerId);
        snapshot.setSnapshotDate(snapshotDate);
        snapshot.setMu(rating.mu());
        snapshot.setSigma(rating.sigma());
        snapshot.setConservativeSkill(TrueSkill2.conservativeSkill(rating.rating()));
        snapshot.setMatchesSeen(rating.matchesSeen());
        snapshot.setWins(rating.wins());
        snapshot.setLosses(rating.losses());
        snapshot.setLastMatchDate(rating.lastMatchDate());
        return snapshot;
    }

    private TrueSkill2RatingDto toDto(PlayerRatingTs2 snapshot, Player player) {
        return new TrueSkill2RatingDto(
                snapshot.getPlayerId(),
                player.getName(),
                snapshot.getSnapshotDate(),
                snapshot.getMu(),
                snapshot.getSigma(),
                snapshot.getConservativeSkill(),
                snapshot.getMatchesSeen(),
                snapshot.getWins(),
                snapshot.getLosses(),
                snapshot.getLastMatchDate()
        );
    }

    private TrueSkill2RatingDto defaultRating(Player player, LocalDate asOfDate) {
        TrueSkill2.Rating rating = new TrueSkill2.Rating(defaultMu, defaultSigma);
        return new TrueSkill2RatingDto(
                player.getId(),
                player.getName(),
                asOfDate,
                rating.mu(),
                rating.sigma(),
                TrueSkill2.conservativeSkill(rating),
                0,
                0,
                0,
                null
        );
    }

    private TrueSkill2.Parameters parameters() {
        return new TrueSkill2.Parameters(beta, dynamicFactor, sigmaFloor, defaultSigma).canonical();
    }

    private record RatingState(double mu,
                               double sigma,
                               long matchesSeen,
                               long wins,
                               long losses,
                               LocalDate lastMatchDate) {
        private static RatingState initial(double mu, double sigma) {
            return new RatingState(mu, sigma, 0, 0, 0, null);
        }

        private TrueSkill2.Rating rating() {
            return new TrueSkill2.Rating(mu, sigma);
        }

        private RatingState withWin(TrueSkill2.Rating rating, LocalDate date) {
            return new RatingState(rating.mu(), rating.sigma(), matchesSeen + 1, wins + 1, losses, date);
        }

        private RatingState withLoss(TrueSkill2.Rating rating, LocalDate date) {
            return new RatingState(rating.mu(), rating.sigma(), matchesSeen + 1, wins, losses + 1, date);
        }
    }
}
