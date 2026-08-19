package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.MatchDto;
import com.ttl.tabletennis.mapper.MatchMapper;
import com.ttl.tabletennis.service.PlayerService;
import com.ttl.tabletennis.service.StatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final StatisticsService statisticsService;
    private final PlayerService playerService;

    public MatchController(StatisticsService statisticsService,
                           PlayerService playerService) {
        this.statisticsService = statisticsService;
        this.playerService = playerService;
    }

    @GetMapping("/recent/player/{playerId}")
    public List<MatchDto> recentByPlayer(@PathVariable Long playerId,
                                         @RequestParam(defaultValue = "20") int limit) {
        return statisticsService
                .getRecentMatchesForPlayer(playerService.getPlayerOrThrow(playerId), limit)
                .stream()
                .map(MatchMapper::toDto)
                .toList();
    }

    @GetMapping("/recent/head-to-head")
    public List<MatchDto> recentHeadToHead(@RequestParam Long player1Id,
                                           @RequestParam Long player2Id,
                                           @RequestParam(defaultValue = "20") int limit) {
        return statisticsService
                .getRecentMatchesBetweenPlayers(playerService.getPlayerOrThrow(player1Id), playerService.getPlayerOrThrow(player2Id), limit)
                .stream()
                .map(MatchMapper::toDto)
                .toList();
    }
}
