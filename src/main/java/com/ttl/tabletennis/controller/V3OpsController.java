package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.CounterfactualSkippedReportDto;
import com.ttl.tabletennis.dto.OpsFeedsDto;
import com.ttl.tabletennis.dto.OpsIngestDto;
import com.ttl.tabletennis.dto.OpsSettlementDiffsDto;
import com.ttl.tabletennis.dto.OpsStreamsDto;
import com.ttl.tabletennis.service.CounterfactualSkippedPickAnalysisService;
import com.ttl.tabletennis.service.OpsFeedsService;
import com.ttl.tabletennis.service.OpsIngestService;
import com.ttl.tabletennis.service.OpsSettlementDiffService;
import com.ttl.tabletennis.service.OpsStreamsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/ops")
public class V3OpsController {

    private final OpsFeedsService opsFeedsService;
    private final OpsIngestService opsIngestService;
    private final OpsSettlementDiffService opsSettlementDiffService;
    private final OpsStreamsService opsStreamsService;
    private final com.ttl.tabletennis.prediction.staking.Soak11Monitor soak11Monitor;
    private final CounterfactualSkippedPickAnalysisService counterfactualSkippedPickAnalysisService;

    @Autowired
    public V3OpsController(OpsFeedsService opsFeedsService,
                           OpsIngestService opsIngestService,
                           OpsSettlementDiffService opsSettlementDiffService,
                           OpsStreamsService opsStreamsService,
                           com.ttl.tabletennis.prediction.staking.Soak11Monitor soak11Monitor,
                           CounterfactualSkippedPickAnalysisService counterfactualSkippedPickAnalysisService) {
        this.opsFeedsService = opsFeedsService;
        this.opsIngestService = opsIngestService;
        this.opsSettlementDiffService = opsSettlementDiffService;
        this.opsStreamsService = opsStreamsService;
        this.soak11Monitor = soak11Monitor;
        this.counterfactualSkippedPickAnalysisService = counterfactualSkippedPickAnalysisService;
    }

    /** Back-compat constructor for existing tests that don't exercise the
     *  soak endpoint or the counterfactual analyzer. Those endpoints fall
     *  back to safe stubs when their dependencies are null. */
    public V3OpsController(OpsFeedsService opsFeedsService,
                           OpsIngestService opsIngestService,
                           OpsSettlementDiffService opsSettlementDiffService,
                           OpsStreamsService opsStreamsService) {
        this(opsFeedsService, opsIngestService, opsSettlementDiffService, opsStreamsService, null, null);
    }

    /**
     * §11 production-soak progress. Returns the five exit-gate states plus
     * elapsed/required days. {@code overallPass} only flips true when all
     * gates are green AND 14 days have elapsed since {@code ttl.soak11.startAt}.
     */
    @GetMapping("/soak")
    public com.ttl.tabletennis.prediction.staking.Soak11Monitor.Soak11Status soak() {
        if (soak11Monitor == null) {
            return com.ttl.tabletennis.prediction.staking.Soak11Monitor.Soak11Status.uninitialised();
        }
        return soak11Monitor.refresh();
    }

    @GetMapping("/feeds")
    public OpsFeedsDto feeds() {
        return opsFeedsService.snapshot();
    }

    @GetMapping("/ingest")
    public OpsIngestDto ingest() {
        return opsIngestService.snapshot();
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

    /**
     * Counterfactual analysis of picks the staking policy considered but
     * passed on. For each skipped row in {@code paper_trade_decision_sample}
     * we join to {@code matches} (±2 days from the decision) and compute
     * the would-have P/L at the proposed stake, then aggregate by
     * {@code decisionReason}. Surfaces whether each staking gate is currently
     * saving EV or costing it.
     *
     * @param days lookback window in days (clamped to [1, 90]; default 7)
     */
    @GetMapping("/counterfactual-skips")
    public CounterfactualSkippedReportDto counterfactualSkips(@RequestParam(defaultValue = "7") int days) {
        if (counterfactualSkippedPickAnalysisService == null) {
            return new CounterfactualSkippedReportDto(
                    days,
                    java.time.LocalDateTime.now(),
                    0, 0, 0, 0, 0,
                    0.0, 0.0,
                    java.util.List.of());
        }
        return counterfactualSkippedPickAnalysisService.analyze(days);
    }
}
