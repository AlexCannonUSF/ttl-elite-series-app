package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.SettlementDiffLog;
import com.ttl.tabletennis.dto.OpsSettlementDiffsDto;
import com.ttl.tabletennis.repository.SettlementDiffLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpsSettlementDiffServiceTests {

    @Test
    void snapshotAggregatesCountsAndLatestRows() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        SettlementDiffLog row = new SettlementDiffLog();
        row.setBetId(77L);
        row.setDiffKind(SettlementDiffLog.DIFF_KIND_CONTRADICTION);
        row.setOldReason("SETTLED_FROM_OFFICIAL_RESULT");
        row.setNewReason("MANUAL_REVIEW_AWAITING");
        row.setOldWinner(10L);
        row.setNewWinner(null);
        row.setDecidedAt(LocalDateTime.of(2026, 4, 19, 23, 20));
        row.setCorrelationId("corr-77");

        when(repository.count()).thenReturn(12L);
        when(repository.countByDiffKind(SettlementDiffLog.DIFF_KIND_AGREE)).thenReturn(9L);
        when(repository.countByDiffKind(SettlementDiffLog.DIFF_KIND_CONTRADICTION)).thenReturn(2L);
        when(repository.countByDiffKind(SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF)).thenReturn(1L);
        when(repository.findAllByOrderByDecidedAtDescIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));

        OpsSettlementDiffService service = new OpsSettlementDiffService(repository);

        OpsSettlementDiffsDto snapshot = service.snapshot(null, null, null);

        assertEquals(12L, snapshot.summary().totalRows());
        assertEquals(9L, snapshot.summary().agreeRows());
        assertEquals(3L, snapshot.summary().disagreementRows());
        assertEquals(2L, snapshot.summary().contradictionRows());
        assertEquals(1L, snapshot.summary().outcomeDiffRows());
        assertEquals(OpsSettlementDiffService.FOCUS_ALL, snapshot.focus());
        assertEquals(0, snapshot.page());
        assertEquals(25, snapshot.size());
        assertEquals(1L, snapshot.filteredRows());
        assertEquals(1, snapshot.rows().size());
        assertEquals(77L, snapshot.rows().get(0).betId());
        assertEquals(SettlementDiffLog.DIFF_KIND_CONTRADICTION, snapshot.rows().get(0).diffKind());
        assertEquals("corr-77", snapshot.rows().get(0).correlationId());
    }

    @Test
    void snapshotFiltersContradictionsAndRespectsRequestedPageSize() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        mockSummaryCounts(repository);
        SettlementDiffLog contradiction = row(91L, SettlementDiffLog.DIFF_KIND_CONTRADICTION, "SETTLED", "MANUAL_REVIEW_AWAITING");

        when(repository.findByDiffKindOrderByDecidedAtDescIdDesc(eq(SettlementDiffLog.DIFF_KIND_CONTRADICTION), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contradiction)));

        OpsSettlementDiffService service = new OpsSettlementDiffService(repository);

        OpsSettlementDiffsDto snapshot = service.snapshot("contradiction", 2, 10);

        assertEquals(OpsSettlementDiffService.FOCUS_CONTRADICTION, snapshot.focus());
        assertEquals(2, snapshot.page());
        assertEquals(10, snapshot.size());
        assertEquals(1, snapshot.rows().size());
        assertEquals(91L, snapshot.rows().get(0).betId());
    }

    @Test
    void snapshotFiltersAmbiguityRowsByShadowReason() {
        SettlementDiffLogRepository repository = mock(SettlementDiffLogRepository.class);
        mockSummaryCounts(repository);
        SettlementDiffLog ambiguous = row(103L, SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF, "SETTLED", "MANUAL_REVIEW_AWAITING");

        when(repository.findByNewReasonInOrderByDecidedAtDescIdDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ambiguous)));

        OpsSettlementDiffService service = new OpsSettlementDiffService(repository);

        OpsSettlementDiffsDto snapshot = service.snapshot("ambiguity", 0, 15);

        assertEquals(OpsSettlementDiffService.FOCUS_AMBIGUITY, snapshot.focus());
        assertEquals(1, snapshot.rows().size());
        assertEquals("MANUAL_REVIEW_AWAITING", snapshot.rows().get(0).newReason());
    }

    private void mockSummaryCounts(SettlementDiffLogRepository repository) {
        when(repository.count()).thenReturn(12L);
        when(repository.countByDiffKind(SettlementDiffLog.DIFF_KIND_AGREE)).thenReturn(9L);
        when(repository.countByDiffKind(SettlementDiffLog.DIFF_KIND_CONTRADICTION)).thenReturn(2L);
        when(repository.countByDiffKind(SettlementDiffLog.DIFF_KIND_OUTCOME_DIFF)).thenReturn(1L);
    }

    private SettlementDiffLog row(long betId, String diffKind, String oldReason, String newReason) {
        SettlementDiffLog row = new SettlementDiffLog();
        row.setBetId(betId);
        row.setDiffKind(diffKind);
        row.setOldReason(oldReason);
        row.setNewReason(newReason);
        row.setOldWinner(10L);
        row.setNewWinner(null);
        row.setDecidedAt(LocalDateTime.of(2026, 4, 20, 0, 15));
        row.setCorrelationId("corr-" + betId);
        return row;
    }
}
