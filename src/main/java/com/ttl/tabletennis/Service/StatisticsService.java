package com.ttl.tabletennis.Service;

import com.ttl.tabletennis.Entity.Match;
import com.ttl.tabletennis.Entity.Player;
import com.ttl.tabletennis.Entity.PlayerStatistics;
import com.ttl.tabletennis.Repository.MatchRepository;
import com.ttl.tabletennis.Repository.PlayerRepository;
import com.ttl.tabletennis.Utility.NameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class StatisticsService {

    private final PlayerRepository playerRepository;
    private final MatchRepository matchRepository;

    @Autowired
    public StatisticsService(PlayerRepository playerRepository, MatchRepository matchRepository) {
        this.playerRepository = playerRepository;
        this.matchRepository = matchRepository;
    }

    public Map<String, PlayerStatistics> computePlayerStatistics() {
        List<Player> players = playerRepository.findAll();
        List<Match> matches = matchRepository.findAll();

        Map<Long, Long> winsCount = new HashMap<>();
        Map<Long, Long> totalCount = new HashMap<>();

        for (Player p : players) {
            winsCount.put(p.getId(), 0L);
            totalCount.put(p.getId(), 0L);
        }

        for (Match m : matches) {
            Player p1 = m.getPlayer1();
            Player p2 = m.getPlayer2();
            if (p1 == null || p2 == null) continue;

            totalCount.put(p1.getId(), totalCount.get(p1.getId()) + 1);
            totalCount.put(p2.getId(), totalCount.get(p2.getId()) + 1);

            Integer winner = parseMatchResult(m.getResult());
            if (winner != null) {
                if (winner == 1) winsCount.put(p1.getId(), winsCount.get(p1.getId()) + 1);
                else              winsCount.put(p2.getId(), winsCount.get(p2.getId()) + 1);
            }
        }

        Map<String, PlayerStatistics> statsMap = new HashMap<>();
        for (Player p : players) {
            long total   = totalCount.get(p.getId());
            long wins    = winsCount.get(p.getId());
            long losses  = total - wins;
            double rate  = (total > 0) ? (double) wins / total : 0.0;
            statsMap.put(p.getName(), new PlayerStatistics(total, wins, losses, rate));
        }
        return statsMap;
    }

    public double[] getHeadToHeadWinPercentage(String player1Name, String player2Name) {
        List<Match> matches = matchRepository.findAll();
        long p1Wins = 0, p2Wins = 0;

        for (Match m : matches) {
            String p1Name = m.getPlayer1().getName();
            String p2Name = m.getPlayer2().getName();

            Integer winner;
            if (p1Name.equalsIgnoreCase(player1Name) && p2Name.equalsIgnoreCase(player2Name)) {
                winner = parseMatchResult(m.getResult());
                if (winner != null && winner == 1) p1Wins++;
                else if (winner != null && winner == 2) p2Wins++;
            } else if (p1Name.equalsIgnoreCase(player2Name) && p2Name.equalsIgnoreCase(player1Name)) {
                winner = parseMatchResult(m.getResult());
                if (winner != null && winner == 1) p2Wins++;
                else if (winner != null && winner == 2) p1Wins++;
            }
        }

        long total = p1Wins + p2Wins;
        if (total == 0) return new double[]{0.0, 0.0};
        return new double[]{(double) p1Wins / total, (double) p2Wins / total};
    }

    public List<Match> getRecentMatchesBetweenPlayers(Player player1, Player player2, int limit) {
        List<Match> matches = matchRepository.findMatchesByPlayers(player1.getId(), player2.getId());
        matches.sort(Comparator.comparing(Match::getDate).reversed());
        return matches.size() > limit ? matches.subList(0, limit) : matches;
    }

    public List<Match> getRecentMatchesForPlayer(Player player, int limit) {
        List<Match> matches = matchRepository.findMatchesByPlayer(player);
        matches.sort(Comparator.comparing(Match::getDate).reversed());
        return matches.size() > limit ? matches.subList(0, limit) : matches;
    }

    public double computeWinPercentageForPlayer(List<Match> matches, Player player) {
        if (matches.isEmpty()) return 0.0;
        int wins = 0;
        for (Match m : matches) {
            Integer winner = parseMatchResult(m.getResult());
            if (winner == null) continue;
            if (m.getPlayer1().equals(player) && winner == 1) wins++;
            else if (m.getPlayer2().equals(player) && winner == 2) wins++;
        }
        return (double) wins / matches.size();
    }

    public double computeAverageMargin(List<Match> matches, Player player) {
        double totalMargin = 0.0;
        int count = 0;

        for (Match m : matches) {
            String result = m.getResult();
            if (result == null || result.isEmpty()) continue;

            String[] parts = result.split("[:/-]");
            if (parts.length != 2) continue;

            try {
                int score1 = Integer.parseInt(parts[0].trim());
                int score2 = Integer.parseInt(parts[1].trim());

                boolean isP1 = m.getPlayer1().equals(player);
                if (isP1 && score1 > score2)       { totalMargin += (score1 - score2); count++; }
                else if (!isP1 && score2 > score1) { totalMargin += (score2 - score1); count++; }
                else if (isP1 && score1 < score2)  { totalMargin -= (score2 - score1); count++; }
                else if (!isP1 && score2 < score1) { totalMargin -= (score1 - score2); count++; }
            } catch (NumberFormatException ignore) {}
        }
        return count == 0 ? 0.0 : totalMargin / count;
    }

    public double getSimilarOpponentWinPercentage(Player player, Player targetOpponent, int limit) {
        List<Match> recentMatches = getRecentMatchesForPlayer(player, 50);
        List<Match> similarMatches = new ArrayList<>();

        for (Match m : recentMatches) {
            Player opp = m.getPlayer1().equals(player) ? m.getPlayer2() : m.getPlayer1();
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
        double w_h2h1 = recentH2H.isEmpty() ? 0.5 : computeWinPercentageForPlayer(recentH2H, player1);
        double w_h2h2 = recentH2H.isEmpty() ? 0.5 : (1 - w_h2h1);

        List<Match> recent50P1 = getRecentMatchesForPlayer(player1, 50);
        double w_ind1 = computeWinPercentageForPlayer(recent50P1, player1);
        List<Match> recent50P2 = getRecentMatchesForPlayer(player2, 50);
        double w_ind2 = computeWinPercentageForPlayer(recent50P2, player2);

        List<Match> recent10P1 = getRecentMatchesForPlayer(player1, 10);
        double trend1 = 0.5 + (computeWinPercentageForPlayer(recent10P1, player1) - w_ind1);
        List<Match> recent10P2 = getRecentMatchesForPlayer(player2, 10);
        double trend2 = 0.5 + (computeWinPercentageForPlayer(recent10P2, player2) - w_ind2);

        double margin1 = computeAverageMargin(recentH2H, player1);
        double margin2 = computeAverageMargin(recentH2H, player2);
        double marginFactor1 = 0.5 + ((margin1 - margin2) / 6.0);
        if (marginFactor1 < 0) marginFactor1 = 0;
        if (marginFactor1 > 1) marginFactor1 = 1;
        double marginFactor2 = 1 - marginFactor1;

        double similar1 = getSimilarOpponentWinPercentage(player1, player2, 10);
        double similar2 = getSimilarOpponentWinPercentage(player2, player1, 10);

        double composite1 = 0.30 * w_h2h1 + 0.35 * w_ind1 + 0.15 * trend1 + 0.10 * marginFactor1 + 0.10 * similar1;
        double composite2 = 0.30 * w_h2h2 + 0.35 * w_ind2 + 0.15 * trend2 + 0.10 * marginFactor2 + 0.10 * similar2;

        double total = composite1 + composite2;
        if (total == 0) return new double[]{0.5, 0.5};
        return new double[]{composite1 / total, composite2 / total};
    }

    /** Convert probability → American odds. */
    public int computeAmericanOdds(double probability) {
        if (probability >= 0.5) {
            return (int) Math.round(- (probability / (1 - probability)) * 100);
        } else {
            return (int) Math.round(((1 - probability) / probability) * 100);
        }
    }

    /** Convert American odds → probability. */
    public double americanOddsToProbability(int odds) {
        if (odds < 0) {
            return (-odds) / ((-odds) + 100.0);
        } else {
            return 100.0 / (odds + 100.0);
        }
    }

    /** Parse "3-1", "3:2", etc. Returns 1 or 2 for winner, null otherwise. */
    private Integer parseMatchResult(String result) {
        if (result == null || result.isEmpty()) return null;
        String[] parts = result.split("[:/-]");
        if (parts.length != 2) return null;
        try {
            int left  = Integer.parseInt(parts[0].trim());
            int right = Integer.parseInt(parts[1].trim());
            if (left == 3 && right != 3)  return 1;
            if (right == 3 && left  != 3) return 2;
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}