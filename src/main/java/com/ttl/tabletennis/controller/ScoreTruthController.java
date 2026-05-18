package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.ScoreTruthDecisionsDto;
import com.ttl.tabletennis.dto.ScoreTruthEvidenceDto;
import com.ttl.tabletennis.dto.ScoreTruthReviewActionDto;
import com.ttl.tabletennis.dto.ScoreTruthReviewActionRequest;
import com.ttl.tabletennis.dto.ScoreTruthReviewQueueDto;
import com.ttl.tabletennis.service.ScoreTruthQueryService;
import com.ttl.tabletennis.service.ScoreTruthReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/score-truth")
public class ScoreTruthController {

    private final ScoreTruthQueryService scoreTruthQueryService;
    private final ScoreTruthReviewService scoreTruthReviewService;

    public ScoreTruthController(ScoreTruthQueryService scoreTruthQueryService,
                                ScoreTruthReviewService scoreTruthReviewService) {
        this.scoreTruthQueryService = scoreTruthQueryService;
        this.scoreTruthReviewService = scoreTruthReviewService;
    }

    @GetMapping("/evidence/{matchId}")
    public ScoreTruthEvidenceDto evidence(@PathVariable String matchId) {
        return scoreTruthQueryService.evidence(matchId);
    }

    @GetMapping("/bets/{betId}/evidence")
    public ScoreTruthEvidenceDto evidenceByBet(@PathVariable long betId) {
        return scoreTruthQueryService.evidenceByBetId(betId);
    }

    @GetMapping("/decisions")
    public ScoreTruthDecisionsDto decisions(@RequestParam(required = false) Instant from,
                                            @RequestParam(defaultValue = "25") int limit) {
        return scoreTruthQueryService.decisions(from, limit);
    }

    @GetMapping("/review")
    public ScoreTruthReviewQueueDto reviewQueue(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return scoreTruthReviewService.queue(page, size);
    }

    @PostMapping("/review/{decisionId}")
    public ScoreTruthReviewActionDto reviewAction(@PathVariable long decisionId,
                                                  @RequestBody ScoreTruthReviewActionRequest request) {
        return scoreTruthReviewService.recordAction(decisionId, request);
    }
}
