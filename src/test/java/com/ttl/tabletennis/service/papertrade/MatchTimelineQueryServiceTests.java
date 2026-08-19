package com.ttl.tabletennis.service.papertrade;

import com.ttl.tabletennis.domain.TrackedMatchObservation;
import com.ttl.tabletennis.dto.TrackedMatchObservationDto;
import com.ttl.tabletennis.repository.TrackedMatchObservationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchTimelineQueryServiceTests {

    private final TrackedMatchObservationRepository repository = mock(TrackedMatchObservationRepository.class);
    private final MatchTimelineQueryService service = new MatchTimelineQueryService(repository);

    @Test
    void emptyResultForBlankEventKey() {
        assertTrue(service.getMatchTimeline(null).isEmpty());
        assertTrue(service.getMatchTimeline("").isEmpty());
        assertTrue(service.getMatchTimeline("   ").isEmpty());
        verify(repository, never()).findByEventKeyOrderByObservedAtAsc(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void trimsKeyAndMapsObservations() {
        when(repository.findByEventKeyOrderByObservedAtAsc(eq("event-7"))).thenReturn(List.of(
                observation("event-7", 0.7314),
                observation("event-7", 0.9182)
        ));

        List<TrackedMatchObservationDto> out = service.getMatchTimeline("  event-7  ");

        assertEquals(2, out.size());
        assertEquals("event-7", out.get(0).eventKey());
        assertEquals(0.7314, out.get(0).sourceConfidence(), 1e-9);
        assertEquals(0.9182, out.get(1).sourceConfidence(), 1e-9);
    }

    @Test
    void roundsConfidenceToFourDecimals() {
        when(repository.findByEventKeyOrderByObservedAtAsc(eq("event-9"))).thenReturn(List.of(
                observation("event-9", 0.123456789)
        ));

        List<TrackedMatchObservationDto> out = service.getMatchTimeline("event-9");

        assertEquals(0.1235, out.get(0).sourceConfidence(), 1e-9);
    }

    private static TrackedMatchObservation observation(String eventKey, double confidence) {
        TrackedMatchObservation obs = new TrackedMatchObservation();
        obs.setSessionId(1L);
        obs.setBetId(999L);
        obs.setEventKey(eventKey);
        obs.setSource("TEST");
        obs.setSourceKind("MARKET_BOARD");
        obs.setSourceConfidence(confidence);
        obs.setObservedAt(LocalDateTime.of(2026, 5, 19, 18, 0));
        return obs;
    }
}
