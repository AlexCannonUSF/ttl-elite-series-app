package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.MlQualityDto;
import com.ttl.tabletennis.dto.ModelLearningAuditDto;
import com.ttl.tabletennis.service.ModelLearningAuditService;
import com.ttl.tabletennis.service.MlQualityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/ml")
public class V3MlController {

    private final MlQualityService mlQualityService;
    private final ModelLearningAuditService modelLearningAuditService;

    public V3MlController(MlQualityService mlQualityService,
                          ModelLearningAuditService modelLearningAuditService) {
        this.mlQualityService = mlQualityService;
        this.modelLearningAuditService = modelLearningAuditService;
    }

    /**
     * Returns the dashboard payload for {@code /v3/ml/quality}
     * (Phase 05 item 10). {@code windowDays} bounds how far back the
     * recent reliability + drift calculations look; defaults to 14 days.
     */
    @GetMapping("/quality")
    public MlQualityDto quality(
            @RequestParam(required = false, defaultValue = "14") int windowDays,
            @RequestParam(required = false, defaultValue = "10") int binCount) {
        return mlQualityService.snapshot(windowDays, binCount);
    }

    @GetMapping("/learning-audit")
    public ModelLearningAuditDto learningAudit(
            @RequestParam(required = false, defaultValue = "180") int windowDays) {
        return modelLearningAuditService.snapshot(windowDays);
    }
}
