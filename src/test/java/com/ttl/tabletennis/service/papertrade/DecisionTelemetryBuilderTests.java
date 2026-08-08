package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionTelemetryBuilderTests {

    private static final double EPS = 1e-9;

    @Test
    void nullSessionIdYieldsEmptyDtoAndRepoIsNotCalled() {
        PaperTradeDecisionSampleRepository repo = mock(PaperTradeDecisionSampleRepository.class);
        DecisionTelemetryBuilder builder = new DecisionTelemetryBuilder(repo);

        PaperTradingSessionDto.DecisionTelemetryDto dto = builder.buildDecisionTelemetry(null);

        assertEquals(0, dto.consideredCount());
        assertEquals(0, dto.placedCount());
        assertEquals(0, dto.skippedCount());
        assertEquals(0, dto.fallbackPlacedCount());
        assertEquals(0.0, dto.placementRatePct(), EPS);
        assertTrue(dto.topSkipReasons().isEmpty());
        verify(repo, never()).summarizeBySessionId(anyLong());
    }

    @Test
    void emptyRepoResultYieldsEmptyDto() {
        PaperTradeDecisionSampleRepository repo = mock(PaperTradeDecisionSampleRepository.class);
        when(repo.summarizeBySessionId(7L)).thenReturn(List.of());
        DecisionTelemetryBuilder builder = new DecisionTelemetryBuilder(repo);

        PaperTradingSessionDto.DecisionTelemetryDto dto = builder.buildDecisionTelemetry(7L);

        assertEquals(0, dto.consideredCount());
        assertTrue(dto.topSkipReasons().isEmpty());
        verify(repo, never()).summarizeSkipReasonsBySessionId(7L);
    }

    @Test
    void aggregatesPlacedSkippedAndFallbackFromCompactProjections() {
        PaperTradeDecisionSampleRepository repo = mock(PaperTradeDecisionSampleRepository.class);
        doReturn(List.of(
                summary("PLACED", false, 1, 1, 0.1, 1, 0.5, 1, 0.04),
                summary("placed", true, 1, 1, 0.2, 1, 0.6, 1, 0.02),
                summary("SKIPPED", false, 3, 2, 0.8, 3, 1.4, 3, 0.08)
        )).when(repo).summarizeBySessionId(11L);
        doReturn(List.of(
                reason("BELOW_EDGE", 2),
                reason("EXPOSURE_CAP", 1)
        )).when(repo).summarizeSkipReasonsBySessionId(11L);
        DecisionTelemetryBuilder builder = new DecisionTelemetryBuilder(repo);

        PaperTradingSessionDto.DecisionTelemetryDto dto = builder.buildDecisionTelemetry(11L);

        assertEquals(5, dto.consideredCount());
        assertEquals(2, dto.placedCount());
        assertEquals(3, dto.skippedCount());
        assertEquals(1, dto.fallbackPlacedCount());
        assertEquals(40.0, dto.placementRatePct(), 1e-6);
        assertEquals(0.275, dto.avgSelectionScore(), 1e-6);
        assertEquals(50.0, dto.avgSignalQualityPct(), 1e-6);
        assertEquals(3.0, dto.avgPlacedEdgePct(), 1e-6);
        assertEquals(2.6667, dto.avgSkippedEdgePct(), 1e-3);
        assertEquals("BELOW_EDGE", dto.topSkipReasons().get(0).reason());
        assertEquals(2, dto.topSkipReasons().get(0).count());
        assertEquals("EXPOSURE_CAP", dto.topSkipReasons().get(1).reason());
    }

    @Test
    void capsTopSkipReasonsAtFiveAndMergesBlankReasonsAsUnknown() {
        PaperTradeDecisionSampleRepository repo = mock(PaperTradeDecisionSampleRepository.class);
        doReturn(List.of(
                summary("SKIPPED", false, 8, 8, 0.0, 8, 0.0, 8, 0.0)
        )).when(repo).summarizeBySessionId(1L);
        doReturn(List.of(
                reason("AAA", 1), reason("BBB", 1), reason("CCC", 1),
                reason("DDD", 1), reason("EEE", 1), reason("FFF", 1),
                reason(null, 1), reason("  ", 1)
        )).when(repo).summarizeSkipReasonsBySessionId(1L);
        DecisionTelemetryBuilder builder = new DecisionTelemetryBuilder(repo);

        PaperTradingSessionDto.DecisionTelemetryDto dto = builder.buildDecisionTelemetry(1L);

        assertEquals(5, dto.topSkipReasons().size());
        assertEquals("UNKNOWN", dto.topSkipReasons().get(0).reason());
        assertEquals(2, dto.topSkipReasons().get(0).count());
        assertEquals("AAA", dto.topSkipReasons().get(1).reason());
    }

    private static PaperTradeDecisionSampleRepository.DecisionSummary summary(
            String status,
            boolean fallback,
            long rows,
            long selectionCount,
            double selectionSum,
            long qualityCount,
            double qualitySum,
            long edgeCount,
            double edgeSum) {
        PaperTradeDecisionSampleRepository.DecisionSummary summary =
                mock(PaperTradeDecisionSampleRepository.DecisionSummary.class);
        when(summary.getDecisionStatus()).thenReturn(status);
        when(summary.getFallbackPick()).thenReturn(fallback);
        when(summary.getRowCount()).thenReturn(rows);
        when(summary.getSelectionScoreCount()).thenReturn(selectionCount);
        when(summary.getSelectionScoreSum()).thenReturn(selectionSum);
        when(summary.getSignalQualityCount()).thenReturn(qualityCount);
        when(summary.getSignalQualitySum()).thenReturn(qualitySum);
        when(summary.getSuggestedEdgeCount()).thenReturn(edgeCount);
        when(summary.getSuggestedEdgeSum()).thenReturn(edgeSum);
        return summary;
    }

    private static PaperTradeDecisionSampleRepository.SkipReasonSummary reason(String value, long count) {
        PaperTradeDecisionSampleRepository.SkipReasonSummary summary =
                mock(PaperTradeDecisionSampleRepository.SkipReasonSummary.class);
        when(summary.getDecisionReason()).thenReturn(value);
        when(summary.getRowCount()).thenReturn(count);
        return summary;
    }

    private static Long anyLong() {
        return org.mockito.ArgumentMatchers.any();
    }
}
