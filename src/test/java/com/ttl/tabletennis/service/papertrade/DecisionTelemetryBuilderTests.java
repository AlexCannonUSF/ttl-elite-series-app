package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.PaperTradeDecisionSample;
import com.ttl.tabletennis.dto.PaperTradingSessionDto;
import com.ttl.tabletennis.repository.PaperTradeDecisionSampleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionTelemetryBuilderTests {

    private static final double EPS = 1e-9;

    @Test
    void nullSessionId_yieldsEmptyDto_andRepoIsNotCalled() {
        PaperTradeDecisionSampleRepository repo = mock(PaperTradeDecisionSampleRepository.class);
        DecisionTelemetryBuilder builder = new DecisionTelemetryBuilder(repo);

        PaperTradingSessionDto.DecisionTelemetryDto dto = builder.buildDecisionTelemetry(null);

        assertEquals(0, dto.consideredCount());
        assertEquals(0, dto.placedCount());
        assertEquals(0, dto.skippedCount());
        assertEquals(0, dto.fallbackPlacedCount());
        assertEquals(0.0, dto.placementRatePct(), EPS);
        assertTrue(dto.topSkipReasons().isEmpty());
        verify(repo, never()).findBySessionIdOrderByCreatedAtAsc(any());
    }

    @Test
    void emptyRepoResult_yieldsEmptyDto() {
        PaperTradeDecisionSampleRepository repo = mock(PaperTradeDecisionSampleRepository.class);
        when(repo.findBySessionIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        DecisionTelemetryBuilder builder = new DecisionTelemetryBuilder(repo);

        PaperTradingSessionDto.DecisionTelemetryDto dto = builder.buildDecisionTelemetry(7L);

        assertEquals(0, dto.consideredCount());
        assertTrue(dto.topSkipReasons().isEmpty());
    }

    @Test
    void aggregatesPlacedSkippedAndFallback() {
        // Five samples: 2 PLACED (one fallback), 3 SKIPPED with distinct reasons.
        // Selection scores: 0.1, 0.2, null, 0.3, 0.5 → mean over non-null = 0.275
        // Signal quality: 0.5, 0.6, 0.7, 0.4, 0.3 → mean = 0.50 → 50.0 pct
        // Placed edges (0.04, 0.02) → mean 0.03 → 3.0 pct
        // Skipped edges (0.01, 0.02, 0.05) → mean ≈ 0.0267 → 2.67 pct
        List<PaperTradeDecisionSample> rows = List.of(
                sample("PLACED", false, "OK", 0.1, 0.5, 0.04),
                sample("PLACED", true, "OK", 0.2, 0.6, 0.02),
                sample("SKIPPED", false, "BELOW_EDGE", null, 0.7, 0.01),
                sample("SKIPPED", false, "BELOW_EDGE", 0.3, 0.4, 0.02),
                sample("SKIPPED", false, "EXPOSURE_CAP", 0.5, 0.3, 0.05)
        );
        PaperTradeDecisionSampleRepository repo = mock(PaperTradeDecisionSampleRepository.class);
        when(repo.findBySessionIdOrderByCreatedAtAsc(11L)).thenReturn(rows);
        DecisionTelemetryBuilder builder = new DecisionTelemetryBuilder(repo);

        PaperTradingSessionDto.DecisionTelemetryDto dto = builder.buildDecisionTelemetry(11L);

        assertEquals(5, dto.consideredCount());
        assertEquals(2, dto.placedCount());
        assertEquals(3, dto.skippedCount());
        assertEquals(1, dto.fallbackPlacedCount(), "one PLACED row is a fallback pick");
        // placementRate = (2 * 100) / 5 = 40.0
        assertEquals(40.0, dto.placementRatePct(), 1e-6);
        // selection score mean over non-null = (0.1+0.2+0.3+0.5)/4 = 0.275
        assertEquals(0.275, dto.avgSelectionScore(), 1e-6);
        // signal quality mean × 100 = 50.0
        assertEquals(50.0, dto.avgSignalQualityPct(), 1e-6);
        // placed-edge mean × 100 = 3.0
        assertEquals(3.0, dto.avgPlacedEdgePct(), 1e-6);
        // skipped-edge mean × 100 ≈ 2.6667
        assertEquals(2.6667, dto.avgSkippedEdgePct(), 1e-3);

        // Two distinct skip reasons; BELOW_EDGE appears 2x, EXPOSURE_CAP 1x.
        assertEquals(2, dto.topSkipReasons().size());
        assertEquals("BELOW_EDGE", dto.topSkipReasons().get(0).reason());
        assertEquals(2, dto.topSkipReasons().get(0).count());
        assertEquals("EXPOSURE_CAP", dto.topSkipReasons().get(1).reason());
    }

    @Test
    void cappsTopSkipReasonsAtFive() {
        // 7 distinct skip reasons, each with one occurrence → only top 5 returned.
        // Tie-break is by reason key ascending (stable sort).
        List<PaperTradeDecisionSample> rows = List.of(
                sample("SKIPPED", false, "AAA", 0.0, 0.0, 0.0),
                sample("SKIPPED", false, "BBB", 0.0, 0.0, 0.0),
                sample("SKIPPED", false, "CCC", 0.0, 0.0, 0.0),
                sample("SKIPPED", false, "DDD", 0.0, 0.0, 0.0),
                sample("SKIPPED", false, "EEE", 0.0, 0.0, 0.0),
                sample("SKIPPED", false, "FFF", 0.0, 0.0, 0.0),
                sample("SKIPPED", false, "GGG", 0.0, 0.0, 0.0)
        );
        PaperTradeDecisionSampleRepository repo = mock(PaperTradeDecisionSampleRepository.class);
        when(repo.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(rows);
        DecisionTelemetryBuilder builder = new DecisionTelemetryBuilder(repo);

        PaperTradingSessionDto.DecisionTelemetryDto dto = builder.buildDecisionTelemetry(1L);

        assertEquals(5, dto.topSkipReasons().size());
        // Ties go to the lexicographically earlier reason — AAA first.
        assertEquals("AAA", dto.topSkipReasons().get(0).reason());
        assertEquals("EEE", dto.topSkipReasons().get(4).reason());
    }

    @Test
    void averageNonNull_returnsZeroOnEmptyOrAllNullExtractor() {
        assertEquals(0.0, DecisionTelemetryBuilder.averageNonNull(null, s -> 1.0), EPS);
        assertEquals(0.0, DecisionTelemetryBuilder.averageNonNull(List.of(), s -> 1.0), EPS);
        // All extractor results null → 0.0
        List<PaperTradeDecisionSample> rows = List.of(sample("PLACED", false, "OK", 0.0, 0.0, 0.0));
        assertEquals(0.0, DecisionTelemetryBuilder.averageNonNull(rows, s -> null), EPS);
    }

    private static PaperTradeDecisionSample sample(String status,
                                                   boolean fallback,
                                                   String reason,
                                                   Double selectionScore,
                                                   Double signalQuality,
                                                   double suggestedEdge) {
        PaperTradeDecisionSample s = new PaperTradeDecisionSample();
        s.setDecisionStatus(status);
        s.setDecisionReason(reason);
        s.setFallbackPick(fallback);
        s.setSelectionScore(selectionScore);
        s.setSignalQuality(signalQuality);
        s.setSuggestedEdge(suggestedEdge);
        return s;
    }

    private static Long any() {
        // Convenience overload of Mockito.any() with explicit Long type for verify(never()).
        return org.mockito.ArgumentMatchers.any();
    }
}
