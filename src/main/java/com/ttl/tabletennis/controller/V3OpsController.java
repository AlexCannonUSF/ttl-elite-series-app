package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.OpsFeedsDto;
import com.ttl.tabletennis.dto.OpsSettlementDiffsDto;
import com.ttl.tabletennis.dto.OpsStreamsDto;
import com.ttl.tabletennis.service.OpsFeedsService;
import com.ttl.tabletennis.service.OpsSettlementDiffService;
import com.ttl.tabletennis.service.OpsStreamsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/ops")
public class V3OpsController {

    private final OpsFeedsService opsFeedsService;
    private final OpsSettlementDiffService opsSettlementDiffService;
    private final OpsStreamsService opsStreamsService;

    public V3OpsController(OpsFeedsService opsFeedsService,
                           OpsSettlementDiffService opsSettlementDiffService,
                           OpsStreamsService opsStreamsService) {
        this.opsFeedsService = opsFeedsService;
        this.opsSettlementDiffService = opsSettlementDiffService;
        this.opsStreamsService = opsStreamsService;
    }

    @GetMapping("/feeds")
    public OpsFeedsDto feeds() {
        return opsFeedsService.snapshot();
    }

    @GetMapping("/feeds/streams")
    public OpsStreamsDto streams() {
        return opsStreamsService.snapshot();
    }

    @GetMapping("/diffs")
    public OpsSettlementDiffsDto diffs(@RequestParam(required = false) String focus,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return opsSettlementDiffService.snapshot(focus, page, size);
    }
}
