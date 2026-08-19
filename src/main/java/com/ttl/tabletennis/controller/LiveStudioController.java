package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.CompletedMatchLogDto;
import com.ttl.tabletennis.dto.LiveStudioIntegrityDto;
import com.ttl.tabletennis.dto.LiveOddsRecommendationDto;
import com.ttl.tabletennis.dto.LiveRunAnalyticsDto;
import com.ttl.tabletennis.dto.ModelCallApprovalRequest;
import com.ttl.tabletennis.dto.ModelCallMonitorDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.dto.ModelCallTrackingDto;
import com.ttl.tabletennis.dto.PaperTradeBetDto;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.dto.PaperTradingSyncResultDto;
import com.ttl.tabletennis.dto.TrackedMatchObservationDto;
import com.ttl.tabletennis.service.PaperTradingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/live-studio")
public class LiveStudioController {

    private final PaperTradingService paperTradingService;

    public LiveStudioController(PaperTradingService paperTradingService) {
        this.paperTradingService = paperTradingService;
    }

    @GetMapping("/board")
    public List<LiveOddsRecommendationDto> board(@RequestParam(defaultValue = "CONSERVATIVE") String strategy,
                                                 @RequestParam(required = false) String modelVersion,
                                                 @RequestParam(defaultValue = "80") Integer limit,
                                                 @RequestParam(defaultValue = "true") boolean includeUnresolved) {
        return paperTradingService.getLiveStudioBoard(strategy, modelVersion, limit, includeUnresolved);
    }

    @GetMapping("/session")
    public PaperTradingSessionDto session() {
        return paperTradingService.getSessionSnapshot();
    }

    @GetMapping("/open-bets")
    public List<PaperTradeBetDto> openBets() {
        return paperTradingService.getLiveStudioOpenBets();
    }

    @GetMapping("/settled-tape")
    public List<PaperTradeBetDto> settledTape(@RequestParam(defaultValue = "40") int limit) {
        return paperTradingService.getLiveStudioSettledTape(limit);
    }

    @GetMapping("/completed-matches")
    public List<CompletedMatchLogDto> completedMatches(@RequestParam(defaultValue = "3") int days,
                                                       @RequestParam(defaultValue = "120") int limit) {
        return paperTradingService.recentCompletedMatchesLog(days, limit);
    }

    @GetMapping("/model-scorecard")
    public ModelCallScorecardDto modelScorecard(@RequestParam(defaultValue = "40") int limit) {
        return paperTradingService.getModelCallScorecard(limit);
    }

    @GetMapping("/live-run-analytics")
    public LiveRunAnalyticsDto liveRunAnalytics(@RequestParam(defaultValue = "250") int limit) {
        return paperTradingService.getLiveRunAnalytics(limit);
    }

    @GetMapping("/model-calls")
    public ModelCallMonitorDto modelCalls(@RequestParam(defaultValue = "100") int limit) {
        return paperTradingService.getModelCallMonitor(limit);
    }

    @GetMapping("/model-calls/{callId}")
    public ModelCallTrackingDto modelCall(@PathVariable long callId) {
        return paperTradingService.getModelCallTracking(callId);
    }

    @PostMapping("/model-calls/{callId}/approve")
    public ModelCallTrackingDto approveModelCall(@PathVariable long callId,
                                                 @Valid @RequestBody ModelCallApprovalRequest request) {
        return paperTradingService.approveModelCall(callId, request);
    }

    @GetMapping("/integrity")
    public LiveStudioIntegrityDto integrity() {
        return paperTradingService.getLiveStudioIntegrity();
    }

    @GetMapping("/match/{eventKey}/timeline")
    public List<TrackedMatchObservationDto> matchTimeline(@PathVariable String eventKey) {
        return paperTradingService.getMatchTimeline(eventKey);
    }

    @PostMapping("/sync")
    public PaperTradingSyncResultDto sync(@RequestParam(defaultValue = "CONSERVATIVE") String strategy,
                                          @RequestParam(required = false) String modelVersion,
                                          @RequestParam(defaultValue = "80") Integer limit) {
        return paperTradingService.syncLiveSession(strategy, modelVersion, limit);
    }

    @PostMapping("/reset")
    public PaperTradingSessionDto reset(@RequestParam(required = false) Double startingBankroll,
                                        @RequestParam(required = false) String label,
                                        @RequestParam(defaultValue = "false") boolean clearHistory) {
        return paperTradingService.resetSession(startingBankroll, label, clearHistory);
    }
}
