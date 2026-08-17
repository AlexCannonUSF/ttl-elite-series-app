package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.PaperTradeSession;
import com.ttl.tabletennis.domain.ReplayDefinition;
import com.ttl.tabletennis.dto.ReplayDefinitionRequest;
import com.ttl.tabletennis.dto.ReplayDto;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import com.ttl.tabletennis.repository.ReplayDefinitionRepository;
import com.ttl.tabletennis.repository.ReplayEventLogRepository;
import com.ttl.tabletennis.service.papertrade.ModelCallLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayServiceTests {
    private ReplayDefinitionRepository definitions;
    private ReplayEventLogRepository events;
    private PaperTradeSessionRepository sessions;
    private ReplayService service;

    @BeforeEach
    void setUp() {
        definitions = mock(ReplayDefinitionRepository.class);
        events = mock(ReplayEventLogRepository.class);
        sessions = mock(PaperTradeSessionRepository.class);
        service = new ReplayService(definitions, events, sessions, mock(ModelCallLedgerService.class));
        when(definitions.findByDefinitionChecksum(anyString())).thenReturn(Optional.empty());
        when(definitions.save(any(ReplayDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(events.findByReplayIdOrderBySequenceNumberAsc(any())).thenReturn(List.of());
    }

    @Test
    void refusesMutableActiveRunAsReplaySource() {
        PaperTradeSession active = new PaperTradeSession();
        active.setStatus(PaperTradeSession.STATUS_ACTIVE);
        when(sessions.findById(42L)).thenReturn(Optional.of(active));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.create(request("HISTORICAL_AS_KNOWN")));

        assertTrue(failure.getMessage().contains("closed, immutable"));
    }

    @Test
    void createsFrozenHistoricalDefinitionButRejectsUnboundedModernRetrospective() {
        PaperTradeSession closed = new PaperTradeSession();
        closed.setStatus(PaperTradeSession.STATUS_CLOSED);
        when(sessions.findById(42L)).thenReturn(Optional.of(closed));

        ReplayDto created = service.create(request("HISTORICAL_AS_KNOWN"));

        assertEquals("DRAFT", created.status());
        assertEquals("PENDING", created.leakageAuditStatus());
        assertEquals(List.of(42L), created.sourceRunIds());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.create(request("MODERN_MODEL_RETROSPECTIVE")));
        assertTrue(failure.getMessage().contains("training cutoff"));
    }

    private static ReplayDefinitionRequest request(String mode) {
        return new ReplayDefinitionRequest("Replay test", List.of(42L), mode, null, null,
                "FROZEN_ORIGINAL_CALL", List.of("CHAMPION"), List.of("ALL_CALLS"),
                "HR_MKT", 1000.0, 45, 31_415_926L);
    }
}
