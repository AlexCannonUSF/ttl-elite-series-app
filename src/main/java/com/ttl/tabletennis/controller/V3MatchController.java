package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.PredictionPanelDto;
import com.ttl.tabletennis.service.PredictionPanelService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v3/matches")
public class V3MatchController {

    private final PredictionPanelService predictionPanelService;

    public V3MatchController(PredictionPanelService predictionPanelService) {
        this.predictionPanelService = predictionPanelService;
    }

    /**
     * Returns the composite prediction panel powering the v3
     * {@code /v3/matches/:id/prediction} route. The {@code id} path is a
     * convenience for the FE; identity is established by the
     * {@code player1Id} / {@code player2Id} query params.
     */
    @GetMapping("/prediction")
    public PredictionPanelDto prediction(
            @RequestParam long player1Id,
            @RequestParam long player2Id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) String modelFamily,
            @RequestParam(required = false, defaultValue = "6") int topK) {
        return predictionPanelService.build(player1Id, player2Id, asOfDate, modelFamily, topK);
    }
}
