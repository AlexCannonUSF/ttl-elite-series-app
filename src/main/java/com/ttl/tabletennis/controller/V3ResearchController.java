package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.LiveRunAnalyticsDto;
import com.ttl.tabletennis.dto.ModelCallMonitorDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.dto.ModelRunHistoryDto;
import com.ttl.tabletennis.dto.ResearchRunCompareRequest;
import com.ttl.tabletennis.dto.ResearchRunComparisonDto;
import com.ttl.tabletennis.dto.ResearchRunDetailDto;
import com.ttl.tabletennis.dto.ResearchRunFoundationDto;
import com.ttl.tabletennis.dto.RunAnnotationRequest;
import com.ttl.tabletennis.dto.ExperimentCollectionDto;
import com.ttl.tabletennis.dto.ExperimentCollectionRequest;
import com.ttl.tabletennis.dto.ExperimentRunLinkRequest;
import com.ttl.tabletennis.service.ExperimentCollectionService;
import com.ttl.tabletennis.service.ModelRunHistoryService;
import com.ttl.tabletennis.service.ResearchFoundationQueryService;
import com.ttl.tabletennis.service.ResearchRunService;
import com.ttl.tabletennis.service.papertrade.ModelCallLedgerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/research")
public class V3ResearchController {

    private final ModelRunHistoryService runHistoryService;
    private final ModelCallLedgerService ledgerService;
    private final ResearchRunService researchRunService;
    private final ResearchFoundationQueryService foundationQueryService;
    private final ExperimentCollectionService experimentCollectionService;

    public V3ResearchController(ModelRunHistoryService runHistoryService,
                                ModelCallLedgerService ledgerService,
                                ResearchRunService researchRunService,
                                ResearchFoundationQueryService foundationQueryService,
                                ExperimentCollectionService experimentCollectionService) {
        this.runHistoryService = runHistoryService;
        this.ledgerService = ledgerService;
        this.researchRunService = researchRunService;
        this.foundationQueryService = foundationQueryService;
        this.experimentCollectionService = experimentCollectionService;
    }

    @GetMapping("/runs")
    public ModelRunHistoryDto runs(@RequestParam(defaultValue = "50") int limit) {
        return runHistoryService.history(limit);
    }

    @GetMapping("/runs/{runId}")
    public ResearchRunDetailDto run(@PathVariable long runId) {
        return researchRunService.detail(runId);
    }

    @GetMapping("/runs/{runId}/foundation")
    public ResearchRunFoundationDto foundation(@PathVariable long runId) {
        return foundationQueryService.forRun(runId);
    }

    @PostMapping("/runs/{runId}/annotations")
    public ResearchRunFoundationDto.Annotation annotate(@PathVariable long runId,
                                                        @Valid @RequestBody RunAnnotationRequest request) {
        return foundationQueryService.annotate(runId, request);
    }

    @GetMapping("/experiments")
    public java.util.List<ExperimentCollectionDto> experiments() {
        return experimentCollectionService.all();
    }

    @PostMapping("/experiments")
    public ExperimentCollectionDto createExperiment(@Valid @RequestBody ExperimentCollectionRequest request) {
        return experimentCollectionService.create(request);
    }

    @PostMapping("/experiments/{experimentId}/runs")
    public ExperimentCollectionDto linkRun(@PathVariable long experimentId,
                                           @Valid @RequestBody ExperimentRunLinkRequest request) {
        return experimentCollectionService.link(experimentId, request);
    }

    @GetMapping("/runs/{runId}/calls")
    public ModelCallMonitorDto calls(@PathVariable long runId,
                                     @RequestParam(defaultValue = "250") int limit) {
        return ledgerService.monitor(runId, limit);
    }

    @GetMapping("/runs/{runId}/analytics")
    public LiveRunAnalyticsDto analytics(@PathVariable long runId,
                                         @RequestParam(defaultValue = "250") int limit) {
        return ledgerService.analytics(runId, limit);
    }

    @GetMapping("/runs/{runId}/scorecard")
    public ModelCallScorecardDto scorecard(@PathVariable long runId,
                                           @RequestParam(defaultValue = "100") int limit) {
        return ledgerService.scorecard(runId, limit);
    }

    @PostMapping("/compare")
    public ResearchRunComparisonDto compare(@Valid @RequestBody ResearchRunCompareRequest request) {
        return researchRunService.compare(request.runIds(), request.trendLimit());
    }
}
