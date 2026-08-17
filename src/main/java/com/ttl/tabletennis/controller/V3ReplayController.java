package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.ReplayDefinitionRequest;
import com.ttl.tabletennis.dto.ReplayDto;
import com.ttl.tabletennis.service.ReplayService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v3/replay")
public class V3ReplayController {
    private final ReplayService replayService;

    public V3ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @GetMapping
    public List<ReplayDto> all() { return replayService.all(); }

    @GetMapping("/{id}")
    public ReplayDto get(@PathVariable long id) { return replayService.get(id); }

    @PostMapping("/definitions")
    public ReplayDto create(@Valid @RequestBody ReplayDefinitionRequest request) {
        return replayService.create(request);
    }

    @PostMapping("/{id}/start")
    public ReplayDto start(@PathVariable long id) { return replayService.start(id); }

    @PostMapping("/{id}/branch")
    public ReplayDto branch(@PathVariable long id) { return replayService.branch(id); }
}
