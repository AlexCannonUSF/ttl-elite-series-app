package com.ttl.tabletennis.Controller;

import com.ttl.tabletennis.Entity.PlayerStatistics;
import com.ttl.tabletennis.Service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    @Autowired
    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/players")
    public Map<String, PlayerStatistics> getPlayerStatistics() {
        return statisticsService.computePlayerStatistics();
    }
}
