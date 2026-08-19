package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.MetricDefinitionDto;
import com.ttl.tabletennis.service.MetricGlossaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v3/definitions")
public class V3DefinitionsController {
    private final MetricGlossaryService glossaryService;

    public V3DefinitionsController(MetricGlossaryService glossaryService) {
        this.glossaryService = glossaryService;
    }

    @GetMapping("/metrics")
    public List<MetricDefinitionDto> metrics() { return glossaryService.all(); }
}
