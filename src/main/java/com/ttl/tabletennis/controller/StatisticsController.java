package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.HeadToHeadStatsDto;
import com.ttl.tabletennis.dto.PlayerStatisticsDto;
import com.ttl.tabletennis.service.StatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/players")
    public List<PlayerStatisticsDto> getPlayerStatistics() {
        return statisticsService.computePlayerStatisticsDto();
    }

    @GetMapping("/head-to-head")
    public HeadToHeadStatsDto getHeadToHead(@RequestParam Long player1Id,
                                            @RequestParam Long player2Id) {
        return statisticsService.getHeadToHeadStats(player1Id, player2Id);
    }
}
