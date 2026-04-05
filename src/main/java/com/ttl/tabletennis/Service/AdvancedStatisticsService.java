package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.model.AdvancedPlayerStats;
import com.ttl.tabletennis.repository.MatchRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class AdvancedStatisticsService {

    private final MatchRepository matches;

    public AdvancedStatisticsService(MatchRepository matches) {
        this.matches = matches;
    }

    public AdvancedPlayerStats recent(Player player, int n) {
        List<Match> mine = matches.findRecentMatchesByPlayerId(player.getId(), PageRequest.of(0, Math.max(n, 1)));

        int wins = 0;
        int losses = 0;
        int completed = 0;
        int streak = 0;
        Boolean streakWin = null;
        int setDiffSum = 0;

        for (Match m : mine) {
            if (!m.isComplete() || m.getWinnerPlayerId() == null || m.getPlayer1SetsWon() == null || m.getPlayer2SetsWon() == null) {
                continue;
            }

            boolean isWin = m.getWinnerPlayerId().equals(player.getId());
            int wonSets = m.getPlayer1().getId().equals(player.getId()) ? m.getPlayer1SetsWon() : m.getPlayer2SetsWon();
            int lostSets = m.getPlayer1().getId().equals(player.getId()) ? m.getPlayer2SetsWon() : m.getPlayer1SetsWon();

            if (isWin) {
                wins++;
                if (streakWin == null || streakWin) {
                    streak++;
                }
            } else {
                losses++;
                if (streakWin == null || !streakWin) {
                    streak++;
                }
            }
            if (streakWin == null) streakWin = isWin;

            setDiffSum += wonSets - lostSets;
            completed++;
            if (completed >= n) break;
        }

        double avg = completed > 0 ? (double) setDiffSum / completed : 0.0;
        return new AdvancedPlayerStats(completed, wins, losses, avg, streak, streakWin != null && streakWin);
    }

    public AdvancedPlayerStats last5(Player player) {
        return recent(player, 5);
    }

    public AdvancedPlayerStats last10(Player player) {
        return recent(player, 10);
    }

    public AdvancedPlayerStats last50(Player player) {
        return recent(player, 50);
    }
}
