package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.PlayerStatistics;
import com.ttl.tabletennis.dto.HeadToHeadStatsDto;
import com.ttl.tabletennis.dto.PlayerStatisticsDto;
import com.ttl.tabletennis.projection.HeadToHeadAggregateProjection;
import com.ttl.tabletennis.projection.PlayerStatsAggregateProjection;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.util.NameUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class StatisticsService {

    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;

    public StatisticsService(PlayerRepository playerRepository, MatchRepository matchRepository) {
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
    }

    public List<PlayerStatisticsDto> computePlayerStatisticsDto() {
        List<PlayerStatsAggregateProjection> rows = matchRepository.aggregatePlayerStatistics();
        List<PlayerStatisticsDto> out = new ArrayList<>(rows.size());
        for (PlayerStatsAggregateProjection row : rows) {
            long matches = valueOrZero(row.getMatches());
            long wins = valueOrZero(row.getWins());
            long losses = valueOrZero(row.getLosses());
            double winPct = matches == 0 ? 0.0 : (double) wins / matches;
            out.add(new PlayerStatisticsDto(
                    row.getPlayerId(),
                    fullName(row.getFirstName(), row.getLastName()),
                    wins,
                    losses,
                    matches,
                    winPct
            ));
        }
        return out;
    }

    public Map<String, PlayerStatistics> computePlayerStatistics() {
        Map<String, PlayerStatistics> map = new HashMap<>();
        for (PlayerStatisticsDto row : computePlayerStatisticsDto()) {
            map.put(row.playerName(), new PlayerStatistics(row.wins(), row.losses(), row.matches(), row.winPct()));
        }
        return map;
    }

    public HeadToHeadStatsDto getHeadToHeadStats(Long player1Id, Long player2Id) {
        Player p1 = playerRepository.findById(player1Id).orElse(null);
        Player p2 = playerRepository.findById(player2Id).orElse(null);
        if (p1 == null || p2 == null) {
            return new HeadToHeadStatsDto("Unknown", "Unknown", 0, 0, 0, 0.0, 0.0);
        }

        HeadToHeadAggregateProjection row = matchRepository.summarizeHeadToHead(player1Id, player2Id);
        long p1Wins = row == null ? 0 : valueOrZero(row.getPlayer1Wins());
        long p2Wins = row == null ? 0 : valueOrZero(row.getPlayer2Wins());
        long total = row == null ? 0 : valueOrZero(row.getTotalMatches());

        double p1Pct = total == 0 ? 0.0 : (double) p1Wins / total;
        double p2Pct = total == 0 ? 0.0 : (double) p2Wins / total;

        return new HeadToHeadStatsDto(p1.getName(), p2.getName(), p1Wins, p2Wins, total, p1Pct, p2Pct);
    }

    public double[] getHeadToHeadWinPercentage(String player1Name, String player2Name) {
        String p1Key = NameUtils.normalizeForLookup(player1Name);
        String p2Key = NameUtils.normalizeForLookup(player2Name);

        Player p1 = playerRepository.findByNormalizedName(p1Key).orElse(null);
        Player p2 = playerRepository.findByNormalizedName(p2Key).orElse(null);
        if (p1 == null || p2 == null) {
            return new double[]{0.0, 0.0};
        }

        HeadToHeadStatsDto stats = getHeadToHeadStats(p1.getId(), p2.getId());
        return new double[]{stats.player1WinPct(), stats.player2WinPct()};
    }

    public List<Match> getRecentMatchesBetweenPlayers(Player player1, Player player2, int limit) {
        Pageable page = PageRequest.of(0, clampLimit(limit));
        return matchRepository.findRecentMatchesByPlayers(player1.getId(), player2.getId(), page);
    }

    public List<Match> getRecentMatchesForPlayer(Player player, int limit) {
        Pageable page = PageRequest.of(0, clampLimit(limit));
        return matchRepository.findRecentMatchesByPlayerId(player.getId(), page);
    }

    public double computeWinPercentageForPlayer(List<Match> matches, Player player) {
        if (matches.isEmpty()) return 0.0;
        int wins = 0;
        int completed = 0;
        for (Match m : matches) {
            if (!m.isComplete() || m.getWinnerPlayerId() == null) continue;
            completed++;
            if (m.getWinnerPlayerId().equals(player.getId())) {
                wins++;
            }
        }
        return completed == 0 ? 0.0 : (double) wins / completed;
    }

    public double computeAverageMargin(List<Match> matches, Player player) {
        double totalMargin = 0.0;
        int count = 0;

        for (Match m : matches) {
            if (!m.isComplete() || m.getPlayer1SetsWon() == null || m.getPlayer2SetsWon() == null) continue;

            int sets1 = m.getPlayer1SetsWon();
            int sets2 = m.getPlayer2SetsWon();
            boolean isP1 = m.getPlayer1().getId().equals(player.getId());
            int margin = isP1 ? (sets1 - sets2) : (sets2 - sets1);

            totalMargin += margin;
            count++;
        }
        return count == 0 ? 0.0 : totalMargin / count;
    }

    public double getSimilarOpponentWinPercentage(Player player, Player targetOpponent, int limit) {
        List<Match> recentMatches = getRecentMatchesForPlayer(player, 50);
        List<Match> similarMatches = new ArrayList<>();

        for (Match m : recentMatches) {
            Player opp = m.getPlayer1().getId().equals(player.getId()) ? m.getPlayer2() : m.getPlayer1();
            if (NameUtils.areNamesSimilar(opp.getName(), targetOpponent.getName())) {
                similarMatches.add(m);
            }
            if (similarMatches.size() >= limit) break;
        }
        if (similarMatches.isEmpty()) return 0.5;
        return computeWinPercentageForPlayer(similarMatches, player);
    }

    public double[] getAdvancedMatchupStatistics(Player player1, Player player2) {
        List<Match> recentH2H = getRecentMatchesBetweenPlayers(player1, player2, 10);
        double wH2h1 = recentH2H.isEmpty() ? 0.5 : computeWinPercentageForPlayer(recentH2H, player1);
        double wH2h2 = recentH2H.isEmpty() ? 0.5 : (1 - wH2h1);

        List<Match> recent50P1 = getRecentMatchesForPlayer(player1, 50);
        double wInd1 = computeWinPercentageForPlayer(recent50P1, player1);
        List<Match> recent50P2 = getRecentMatchesForPlayer(player2, 50);
        double wInd2 = computeWinPercentageForPlayer(recent50P2, player2);

        List<Match> recent10P1 = getRecentMatchesForPlayer(player1, 10);
        double trend1 = 0.5 + (computeWinPercentageForPlayer(recent10P1, player1) - wInd1);
        List<Match> recent10P2 = getRecentMatchesForPlayer(player2, 10);
        double trend2 = 0.5 + (computeWinPercentageForPlayer(recent10P2, player2) - wInd2);

        double margin1 = computeAverageMargin(recentH2H, player1);
        double margin2 = computeAverageMargin(recentH2H, player2);
        double marginFactor1 = 0.5 + ((margin1 - margin2) / 6.0);
        if (marginFactor1 < 0) marginFactor1 = 0;
        if (marginFactor1 > 1) marginFactor1 = 1;
        double marginFactor2 = 1 - marginFactor1;

        double similar1 = getSimilarOpponentWinPercentage(player1, player2, 10);
        double similar2 = getSimilarOpponentWinPercentage(player2, player1, 10);

        double composite1 = 0.30 * wH2h1 + 0.35 * wInd1 + 0.15 * trend1 + 0.10 * marginFactor1 + 0.10 * similar1;
        double composite2 = 0.30 * wH2h2 + 0.35 * wInd2 + 0.15 * trend2 + 0.10 * marginFactor2 + 0.10 * similar2;

        double total = composite1 + composite2;
        if (total == 0) return new double[]{0.5, 0.5};
        return new double[]{composite1 / total, composite2 / total};
    }

    public int computeAmericanOdds(double probability) {
        double p = Math.max(0.0001, Math.min(0.9999, probability));
        if (p >= 0.5) {
            return (int) Math.round(-(p / (1 - p)) * 100);
        }
        return (int) Math.round(((1 - p) / p) * 100);
    }

    public double americanOddsToProbability(int odds) {
        if (odds == 0) {
            throw new IllegalArgumentException("American odds cannot be 0");
        }

        if (odds < 0) {
            return (-odds) / ((-odds) + 100.0);
        }
        return 100.0 / (odds + 100.0);
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return 1;
        return Math.min(limit, 200);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private String fullName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }
}
