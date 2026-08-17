package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeModelCall;
import com.ttl.tabletennis.dto.LiveRunAnalyticsDto;
import com.ttl.tabletennis.dto.ModelCallMonitorDto;
import com.ttl.tabletennis.dto.ModelCallScorecardDto;
import com.ttl.tabletennis.dto.ModelRunHistoryDto;
import com.ttl.tabletennis.dto.ResearchRunComparisonDto;
import com.ttl.tabletennis.dto.ResearchRunDetailDto;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.service.papertrade.ModelCallLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchRunServiceTests {

    private ModelRunHistoryService historyService;
    private ModelCallLedgerService ledgerService;
    private PaperTradeModelCallRepository callRepository;
    private ResearchFoundationQueryService foundationQueryService;
    private ResearchRunService service;

    @BeforeEach
    void setUp() {
        historyService = mock(ModelRunHistoryService.class);
        ledgerService = mock(ModelCallLedgerService.class);
        callRepository = mock(PaperTradeModelCallRepository.class);
        foundationQueryService = mock(ResearchFoundationQueryService.class);
        service = new ResearchRunService(historyService, ledgerService, callRepository, foundationQueryService);
    }

    @Test
    void detailScopesEverySectionToRequestedHistoricalRun() {
        ModelRunHistoryDto.Run run = mock(ModelRunHistoryDto.Run.class);
        ModelCallScorecardDto scorecard = mock(ModelCallScorecardDto.class);
        LiveRunAnalyticsDto analytics = mock(LiveRunAnalyticsDto.class);
        ModelCallMonitorDto monitor = mock(ModelCallMonitorDto.class);
        when(historyService.run(42L)).thenReturn(run);
        when(ledgerService.scorecard(42L, 500)).thenReturn(scorecard);
        when(ledgerService.analytics(42L, 500)).thenReturn(analytics);
        when(ledgerService.monitorAllForResearch(42L)).thenReturn(monitor);
        when(callRepository.findBySessionIdOrderByCapturedAtDesc(42L)).thenReturn(List.of());
        when(foundationQueryService.forRun(42L)).thenReturn(mock(com.ttl.tabletennis.dto.ResearchRunFoundationDto.class));

        ResearchRunDetailDto result = service.detail(42L);

        assertSame(run, result.run());
        assertSame(scorecard, result.scorecard());
        assertSame(analytics, result.analytics());
        assertSame(monitor, result.pipeline());
        assertFalse(result.integrity().datasetWindowKnown());
        verify(historyService).run(42L);
        verify(ledgerService).analytics(42L, 500);
    }

    @Test
    void compareReportsSharedOpportunityCoverageWithoutInflatingSamples() {
        ModelRunHistoryDto.Run run1 = mock(ModelRunHistoryDto.Run.class);
        ModelRunHistoryDto.Run run2 = mock(ModelRunHistoryDto.Run.class);
        LiveRunAnalyticsDto analytics1 = mock(LiveRunAnalyticsDto.class);
        LiveRunAnalyticsDto analytics2 = mock(LiveRunAnalyticsDto.class);
        when(historyService.run(1L)).thenReturn(run1);
        when(historyService.run(2L)).thenReturn(run2);
        when(ledgerService.analytics(1L, 250)).thenReturn(analytics1);
        when(ledgerService.analytics(2L, 250)).thenReturn(analytics2);
        when(callRepository.findBySessionIdOrderByCapturedAtDesc(1L)).thenReturn(List.of(
                call("shared"), call("only-one")));
        when(callRepository.findBySessionIdOrderByCapturedAtDesc(2L)).thenReturn(List.of(
                call("shared"), call("only-two")));

        ResearchRunComparisonDto result = service.compare(List.of(1L, 2L), 250);

        assertEquals(1, result.sharedOpportunityCount());
        assertEquals(2, result.runs().size());
        assertEquals(2, result.runs().get(0).distinctOpportunityCount());
        assertEquals(50.0, result.runs().get(0).sharedCoveragePct());
        assertSame(analytics1, result.runs().get(0).naturalCohort());
    }

    @Test
    void detailFlagsAnyCallCapturedAfterRunClosure() {
        LocalDateTime closedAt = LocalDateTime.of(2026, 8, 17, 9, 0);
        ModelRunHistoryDto.Run run = mock(ModelRunHistoryDto.Run.class);
        when(run.closedAt()).thenReturn(closedAt);
        when(run.effectiveModelVersion()).thenReturn("model-r3");
        when(run.effectiveArtifactChecksum()).thenReturn("artifact");
        when(run.featureSchemaChecksum()).thenReturn("schema");
        when(run.calibrationId()).thenReturn("platt");
        when(run.policyVersion()).thenReturn("balanced-r3");
        when(run.codeRevision()).thenReturn("abc123");
        LiveRunAnalyticsDto analytics = mock(LiveRunAnalyticsDto.class);
        when(historyService.run(42L)).thenReturn(run);
        when(ledgerService.scorecard(42L, 500)).thenReturn(mock(ModelCallScorecardDto.class));
        when(ledgerService.analytics(42L, 500)).thenReturn(analytics);
        when(ledgerService.monitorAllForResearch(42L)).thenReturn(mock(ModelCallMonitorDto.class));
        PaperTradeModelCall call = call("late");
        call.setCapturedAt(closedAt.plusSeconds(1));
        call.setMatchIdHighWatermark(55L);
        when(callRepository.findBySessionIdOrderByCapturedAtDesc(42L)).thenReturn(List.of(call));
        when(foundationQueryService.forRun(42L)).thenReturn(mock(com.ttl.tabletennis.dto.ResearchRunFoundationDto.class));

        ResearchRunDetailDto result = service.detail(42L);

        assertFalse(result.integrity().closedRunImmutable());
        assertEquals(1, result.integrity().postCloseCallCount());
        assertEquals("POST_CLOSE_MUTATION", result.integrity().status());
    }

    private static PaperTradeModelCall call(String externalEventId) {
        PaperTradeModelCall call = new PaperTradeModelCall();
        call.setExternalEventId(externalEventId);
        call.setEventKey("event-" + externalEventId);
        return call;
    }
}
