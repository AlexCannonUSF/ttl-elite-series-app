package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.PlayerStatisticsDto;
import com.ttl.tabletennis.dto.StatisticsBenchmarkDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class StatisticsBenchmarkService {

    private final StatisticsService statisticsService;
    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;

    public StatisticsBenchmarkService(StatisticsService statisticsService,
                                      MatchRepository matchRepository,
                                      PlayerRepository playerRepository) {
        this.statisticsService = statisticsService;
        this.matchRepository = matchRepository;
        this.playerRepository = playerRepository;
    }

    public StatisticsBenchmarkDto benchmarkPlayerStats(int iterations) {
        int loops = Math.max(1, Math.min(iterations, 100));

        long optimizedStart = System.nanoTime();
        for (int i = 0; i < loops; i++) {
            List<PlayerStatisticsDto> ignored = statisticsService.computePlayerStatisticsDto();
            if (ignored.isEmpty() && i == -1) {
                throw new IllegalStateException("Unreachable");
            }
        }
        long optimizedMillis = nanosToMillis(System.nanoTime() - optimizedStart);

        long legacyStart = System.nanoTime();
        for (int i = 0; i < loops; i++) {
            legacyScanPlayerStats();
        }
        long legacyMillis = nanosToMillis(System.nanoTime() - legacyStart);

        double speedup = optimizedMillis == 0
                ? (legacyMillis == 0 ? 1.0 : Double.POSITIVE_INFINITY)
                : (double) legacyMillis / optimizedMillis;

        return new StatisticsBenchmarkDto(
                loops,
                playerRepository.count(),
                matchRepository.count(),
                optimizedMillis,
                legacyMillis,
                speedup
        );
    }

    private Map<Long, long[]> legacyScanPlayerStats() {
        List<Player> players = playerRepository.findAll();
        List<Match> matches = matchRepository.findAll();

        Map<Long, long[]> counters = new HashMap<>();
        for (Player player : players) {
            counters.put(player.getId(), new long[]{0, 0, 0});
        }

        for (Match match : matches) {
            if (match.getPlayer1() == null || match.getPlayer2() == null || !match.isComplete()) continue;

            long[] p1 = counters.computeIfAbsent(match.getPlayer1().getId(), id -> new long[]{0, 0, 0});
            long[] p2 = counters.computeIfAbsent(match.getPlayer2().getId(), id -> new long[]{0, 0, 0});

            p1[0]++;
            p2[0]++;
            if (match.getWinnerPlayerId() != null) {
                if (match.getWinnerPlayerId().equals(match.getPlayer1().getId())) {
                    p1[1]++;
                    p2[2]++;
                } else if (match.getWinnerPlayerId().equals(match.getPlayer2().getId())) {
                    p2[1]++;
                    p1[2]++;
                }
            }
        }
        return counters;
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000;
    }
}
