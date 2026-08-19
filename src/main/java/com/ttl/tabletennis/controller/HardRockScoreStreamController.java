package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.HardRockScoreStreamStatusDto;
import com.ttl.tabletennis.scrape.HardRockScoreStreamClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/live-studio")
public class HardRockScoreStreamController {

    private final HardRockScoreStreamClient scoreStreamClient;

    public HardRockScoreStreamController(HardRockScoreStreamClient scoreStreamClient) {
        this.scoreStreamClient = scoreStreamClient;
    }

    @GetMapping("/score-stream")
    public HardRockScoreStreamStatusDto status() {
        return scoreStreamClient.status();
    }
}
