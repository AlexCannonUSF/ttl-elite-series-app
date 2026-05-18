package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.OpsFeedsDto;
import com.ttl.tabletennis.dto.OpsSettlementDiffSummaryDto;
import com.ttl.tabletennis.dto.OpsSettlementDiffsDto;
import com.ttl.tabletennis.dto.OpsStreamsDto;
import com.ttl.tabletennis.service.OpsFeedsService;
import com.ttl.tabletennis.service.OpsSettlementDiffService;
import com.ttl.tabletennis.service.OpsStreamsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V3OpsControllerTests {

    @Test
    void feedsDelegatesToOpsFeedsService() {
        OpsFeedsService feedsService = mock(OpsFeedsService.class);
        OpsSettlementDiffService diffsService = mock(OpsSettlementDiffService.class);
        V3OpsController controller = new V3OpsController(feedsService, diffsService, mock(OpsStreamsService.class));
        OpsFeedsDto dto = new OpsFeedsDto(null, null, null);

        when(feedsService.snapshot()).thenReturn(dto);

        assertSame(dto, controller.feeds());
        verify(feedsService).snapshot();
    }

    @Test
    void streamsDelegatesToOpsStreamsService() {
        OpsFeedsService feedsService = mock(OpsFeedsService.class);
        OpsSettlementDiffService diffsService = mock(OpsSettlementDiffService.class);
        OpsStreamsService streamsService = mock(OpsStreamsService.class);
        V3OpsController controller = new V3OpsController(feedsService, diffsService, streamsService);
        OpsStreamsDto dto = new OpsStreamsDto(Instant.now(), null, null, List.of());

        when(streamsService.snapshot()).thenReturn(dto);

        assertSame(dto, controller.streams());
        verify(streamsService).snapshot();
    }

    @Test
    void diffsDelegatesToOpsSettlementDiffServiceWithQueryParams() {
        OpsFeedsService feedsService = mock(OpsFeedsService.class);
        OpsSettlementDiffService diffsService = mock(OpsSettlementDiffService.class);
        V3OpsController controller = new V3OpsController(feedsService, diffsService, mock(OpsStreamsService.class));
        OpsSettlementDiffsDto dto = new OpsSettlementDiffsDto(
                Instant.now(),
                "AMBIGUITY",
                1,
                10,
                3,
                1,
                true,
                false,
                new OpsSettlementDiffSummaryDto(12, 9, 3, 2, 1),
                List.of()
        );

        when(diffsService.snapshot("AMBIGUITY", 1, 10)).thenReturn(dto);

        assertSame(dto, controller.diffs("AMBIGUITY", 1, 10));
        verify(diffsService).snapshot("AMBIGUITY", 1, 10);
    }
}
