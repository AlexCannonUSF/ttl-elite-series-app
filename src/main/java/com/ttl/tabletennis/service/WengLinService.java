package com.ttl.tabletennis.service;

import com.ttl.tabletennis.analytics.WengLin;
import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.PlayerRatingWl;
import com.ttl.tabletennis.dto.WengLinMatchupDto;
import com.ttl.tabletennis.dto.WengLinRatingDto;
import com.ttl.tabletennis.dto.WengLinRebuildDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRatingWlRepository;
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
public class WengLinService {

    private static final LocalDate MIN_REASONABLE_MATCH_DATE = LocalDate.of(1990, 1, 1);

    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;
    private final PlayerRatingWlRepository playerRatingWlRepository;

    @Value("${ttl.wenglin.defaultRating:0.0}")
    private double defaultRating;

    @Value("${ttl.wenglin.defaultUncertainty:1.0}")
    private double defaultUncertainty;

    @Value("${ttl.wenglin.beta:1.0}")
    private double beta;

    @Value("${ttl.wenglin.dynamicFactor:0.015}")
    private double dynamicFactor;

    @Value("${ttl.wenglin.uncertaintyFloor:0.05}")
    private double uncertaintyFloor;

    @Value("${ttl.wenglin.learningRate:1.0}")
    private double learningRate;

    public WengLinService(MatchRepository matchRepository,
                          PlayerRepository playerRepository,
                          PlayerRatingWlRepository playerRatingWlRepository) {
        this.matchRepository = matchRepository;
        this.playerRepository = playerRepository;
        this.playerRatingWlRepository = playerRatingWlRepository;
    }

    @Transactional
    public WengLinRebuildDto rebuild(LocalDate fromDate, LocalDate toDate) {
        LocalDate firstMatchDate = matchRepository.findFirstCompletedMatchDate();
        LocalDate lastMatchDate = matchRepository.findLastCompletedMatchDate();
        if (firstMatchDate == null || lastMatchDate == null) {
            return new WengLinRebuildDto(null, null, 0, 0, 0, 0, beta, learningRate);
        }

        LocalDate maxReasonableDate = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        LocalDate clampedFirstMatchDate = firstMatchDate.isBefore(MIN_REASONABLE_MATCH_DATE)
                ? MIN_REASONABLE_MATCH_DATE
                : firstMatchDate;
        LocalDate clampedLastMatchDate = lastMatchDate.isAfter(maxReasonableDate)
                ? maxReasonableDate
                : lastMatchDate;
        if (clampedLastMatchDate.isBefore(clampedFirstMatchDate)) {
            return new WengLinRebuildDto(null, null, 0, 0, 0, 0, beta, learningRate);
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
                ratingsByPlayer.put(player.getId(), RatingState.initial(defaultRating, defaultUncertainty));
            }
        }

        List<Match> matches = matchRepository.findCompletedMatchesBetween(from, to);
        Map<LocalDate, List<Match>> matchesByDate = matches.stream()
                .filter(m -> m.getDate() != null)
                .collect(Collectors.groupingBy(Match::getDate));

        playerRatingWlRepository.deleteAllInBatch();

        long snapshotsWritten = 0L;
        long matchesProcessed = 0L;
        int daysProcessed = 0;
        WengLin.Parameters parameters = parameters();

        LocalDate day = from;
        while (!day.isAfter(to)) {
            List<Match> dayMatches = matchesByDate.getOrDefault(day, List.of());
            for (Match match : dayMatches) {
                if (applyMatch(match, ratingsByPlayer, parameters)) {
                    matchesProcessed++;
                }
            }

            List<PlayerRatingWl> snapshots = new ArrayList<>(players.size());
            for (Player player : players) {
                if (player.getId() == null) {
                    continue;
                }
                RatingState rating = ratingsByPlayer.getOrDefault(
                        player.getId(),
                        RatingState.initial(defaultRating, defaultUncertainty)
                );
                snapshots.add(toEntity(player.getId(), day, rating));
            }
            playerRatingWlRepository.saveAll(snapshots);
            snapshotsWritten += snapshots.size();
            daysProcessed++;
            day = day.plusDays(1);
        }

        return new WengLinRebuildDto(from, to, daysProcessed, players.size(), matchesProcessed,
                snapshotsWritten, beta, learningRate);
    }

    public WengLinRatingDto ratingForPlayer(Long playerId, LocalDate asOfDate) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId is required");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown player: " + playerId));
        LocalDate effectiveDate = asOfDate == null ? LocalDate.now(ZoneOffset.UTC) : asOfDate;

        return playerRatingWlRepository
                .findTopByPlayerIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(playerId, effectiveDate)
                .map(snapshot -> toDto(snapshot, player))
                .orElseGet(() -> defaultRating(player, effectiveDate));
    }

    public WengLinMatchupDto matchup(Long player1Id, Long player2Id, LocalDate asOfDate) {
        if (player1Id == null || player2Id == null) {
            throw new IllegalArgumentException("player ids are required");
        }
        if (player1Id.equals(player2Id)) {
            throw new IllegalArgumentException("Select two different players");
        }
        LocalDate effectiveDate = asOfDate == null ? LocalDate.now(ZoneOffset.UTC) : asOfDate;
        WengLinRatingDto p1 = ratingForPlayer(player1Id, effectiveDate);
        WengLinRatingDto p2 = ratingForPlayer(player2Id, effectiveDate);
        double probability = WengLin.winProbability(
                new WengLin.Rating(p1.rating(), p1.uncertainty()),
                new WengLin.Rating(p2.rating(), p2.uncertainty()),
                parameters()
        );

        return new WengLinMatchupDto(
                effectiveDate,
                probability,
                p1.rating() - p2.rating(),
                p1.conservativeRating() - p2.conservativeRating(),
                p1,
                p2
        );
    }

    public List<WengLinRatingDto> historyForPlayer(Long playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId is required");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown player: " + playerId));
        return playerRatingWlRepository.findByPlayerIdOrderBySnapshotDateAsc(playerId)
                .stream()
                .map(snapshot -> toDto(snapshot, player))
                .toList();
    }

    private boolean applyMatch(Match match,
                               Map<Long, RatingState> ratingsByPlayer,
                               WengLin.Parameters parameters) {
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
        RatingState winner = ratingsByPlayer.getOrDefault(
                winnerId,
                RatingState.initial(defaultRating, defaultUncertainty)
        );
        RatingState loser = ratingsByPlayer.getOrDefault(
                loserId,
                RatingState.initial(defaultRating, defaultUncertainty)
        );
        WengLin.Update update = WengLin.updateWinner(winner.rating(), loser.rating(), parameters);

        ratingsByPlayer.put(winnerId, winner.withWin(update.winner(), match.getDate()));
        ratingsByPlayer.put(loserId, loser.withLoss(update.loser(), match.getDate()));
        return true;
    }

    private PlayerRatingWl toEntity(Long playerId, LocalDate snapshotDate, RatingState rating) {
        PlayerRatingWl snapshot = new PlayerRatingWl();
        snapshot.setPlayerId(playerId);
        snapshot.setSnapshotDate(snapshotDate);
        snapshot.setRating(rating.ratingValue());
        snapshot.setUncertainty(rating.uncertainty());
        snapshot.setConservativeRating(WengLin.conservativeRating(rating.rating()));
        snapshot.setMatchesSeen(rating.matchesSeen());
        snapshot.setWins(rating.wins());
        snapshot.setLosses(rating.losses());
        snapshot.setLastMatchDate(rating.lastMatchDate());
        return snapshot;
    }

    private WengLinRatingDto toDto(PlayerRatingWl snapshot, Player player) {
        return new WengLinRatingDto(
                snapshot.getPlayerId(),
                player.getName(),
                snapshot.getSnapshotDate(),
                snapshot.getRating(),
                snapshot.getUncertainty(),
                snapshot.getConservativeRating(),
                snapshot.getMatchesSeen(),
                snapshot.getWins(),
                snapshot.getLosses(),
                snapshot.getLastMatchDate()
        );
    }

    private WengLinRatingDto defaultRating(Player player, LocalDate asOfDate) {
        WengLin.Rating rating = new WengLin.Rating(defaultRating, defaultUncertainty);
        return new WengLinRatingDto(
                player.getId(),
                player.getName(),
                asOfDate,
                rating.rating(),
                rating.uncertainty(),
                WengLin.conservativeRating(rating),
                0,
                0,
                0,
                null
        );
    }

    private WengLin.Parameters parameters() {
        return new WengLin.Parameters(beta, dynamicFactor, uncertaintyFloor, defaultUncertainty, learningRate)
                .canonical();
    }

    private record RatingState(double ratingValue,
                               double uncertainty,
                               long matchesSeen,
                               long wins,
                               long losses,
                               LocalDate lastMatchDate) {
        private static RatingState initial(double rating, double uncertainty) {
            return new RatingState(rating, uncertainty, 0, 0, 0, null);
        }

        private WengLin.Rating rating() {
            return new WengLin.Rating(ratingValue, uncertainty);
        }

        private RatingState withWin(WengLin.Rating rating, LocalDate date) {
            return new RatingState(rating.rating(), rating.uncertainty(), matchesSeen + 1, wins + 1, losses, date);
        }

        private RatingState withLoss(WengLin.Rating rating, LocalDate date) {
            return new RatingState(rating.rating(), rating.uncertainty(), matchesSeen + 1, wins, losses + 1, date);
        }
    }
}
