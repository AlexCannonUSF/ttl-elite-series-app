package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.ScoreTruthDecisionsDto;
import com.ttl.tabletennis.dto.ScoreTruthEvidenceDto;
import com.ttl.tabletennis.dto.ScoreTruthReviewActionDto;
import com.ttl.tabletennis.dto.ScoreTruthReviewActionRequest;
import com.ttl.tabletennis.dto.ScoreTruthReviewQueueDto;
import com.ttl.tabletennis.dto.SettlementReviewPageDto;
import com.ttl.tabletennis.service.ScoreTruthQueryService;
import com.ttl.tabletennis.service.ScoreTruthReviewService;
import com.ttl.tabletennis.service.SettlementReviewService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreTruthControllerTests {

    @Test
    void evidenceDelegatesToQueryService() {
        ScoreTruthQueryService queryService = mock(ScoreTruthQueryService.class);
        ScoreTruthController controller = controller(queryService, mock(ScoreTruthReviewService.class), mock(SettlementReviewService.class));
        ScoreTruthEvidenceDto dto = new ScoreTruthEvidenceDto(Instant.now(), "match-1", null, List.of(), List.of());

        when(queryService.evidence("match-1")).thenReturn(dto);

        assertSame(dto, controller.evidence("match-1"));
        verify(queryService).evidence("match-1");
    }

    @Test
    void decisionsDelegatesToQueryService() {
        ScoreTruthQueryService queryService = mock(ScoreTruthQueryService.class);
        ScoreTruthController controller = controller(queryService, mock(ScoreTruthReviewService.class), mock(SettlementReviewService.class));
        Instant from = Instant.parse("2026-04-19T18:30:00Z");
        ScoreTruthDecisionsDto dto = new ScoreTruthDecisionsDto(Instant.now(), from, List.of());

        when(queryService.decisions(from, 15)).thenReturn(dto);

        assertSame(dto, controller.decisions(from, 15));
        verify(queryService).decisions(from, 15);
    }

    @Test
    void evidenceByBetDelegatesToQueryService() {
        ScoreTruthQueryService queryService = mock(ScoreTruthQueryService.class);
        ScoreTruthController controller = controller(queryService, mock(ScoreTruthReviewService.class), mock(SettlementReviewService.class));
        ScoreTruthEvidenceDto dto = new ScoreTruthEvidenceDto(Instant.now(), "41", null, List.of(), List.of());

        when(queryService.evidenceByBetId(41L)).thenReturn(dto);

        assertSame(dto, controller.evidenceByBet(41L));
        verify(queryService).evidenceByBetId(41L);
    }

    @Test
    void reviewQueueDelegatesToReviewService() {
        ScoreTruthReviewService reviewService = mock(ScoreTruthReviewService.class);
        ScoreTruthController controller = controller(mock(ScoreTruthQueryService.class), reviewService, mock(SettlementReviewService.class));
        ScoreTruthReviewQueueDto dto = new ScoreTruthReviewQueueDto(Instant.now(), 1, 10, 0, 0, true, false, List.of());

        when(reviewService.queue(1, 10)).thenReturn(dto);

        assertSame(dto, controller.reviewQueue(1, 10));
        verify(reviewService).queue(1, 10);
    }

    @Test
    void reviewActionDelegatesToReviewService() {
        ScoreTruthReviewService reviewService = mock(ScoreTruthReviewService.class);
        ScoreTruthController controller = controller(mock(ScoreTruthQueryService.class), reviewService, mock(SettlementReviewService.class));
        ScoreTruthReviewActionRequest request = new ScoreTruthReviewActionRequest("ACCEPT", "looks correct", "ops");
        ScoreTruthReviewActionDto dto = new ScoreTruthReviewActionDto(88L, 701L, "ACCEPT", "ops", "looks correct", Instant.now());

        when(reviewService.recordAction(701L, request)).thenReturn(dto);

        assertSame(dto, controller.reviewAction(701L, request));
        verify(reviewService).recordAction(701L, request);
    }

    @Test
    void settlementReviewDelegatesToForensicsService() {
        SettlementReviewService settlementReviewService = mock(SettlementReviewService.class);
        ScoreTruthController controller = controller(
                mock(ScoreTruthQueryService.class),
                mock(ScoreTruthReviewService.class),
                settlementReviewService
        );
        SettlementReviewPageDto dto = new SettlementReviewPageDto(
                Instant.now(), 0, 20, 0, 0, false, false, 0, 0, 0, List.of());

        when(settlementReviewService.review(0, 20, true)).thenReturn(dto);

        assertSame(dto, controller.settlementReview(0, 20, true));
        verify(settlementReviewService).review(0, 20, true);
    }

    private ScoreTruthController controller(ScoreTruthQueryService queryService,
                                            ScoreTruthReviewService reviewService,
                                            SettlementReviewService settlementReviewService) {
        return new ScoreTruthController(queryService, reviewService, settlementReviewService);
    }
}
