package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.FeedHealthSample;
import com.ttl.tabletennis.repository.FeedHealthSampleRepository;
import com.ttl.tabletennis.scrape.FeedClient;
import com.ttl.tabletennis.scrape.FeedHealth;
import com.ttl.tabletennis.scrape.IngestEvent;
import com.ttl.tabletennis.scrape.IngestionBus;
import com.ttl.tabletennis.scrape.SourceId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedHealthServiceTests {

    @Test
    void emitFeedHealthEventsPublishesOnePulsePerFeed() {
        @SuppressWarnings("unchecked")
        FeedClient<Object> feedClient = mock(FeedClient.class);
        FeedHealthSampleRepository repository = mock(FeedHealthSampleRepository.class);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        FeedHealthService service = new FeedHealthService(List.of(feedClient), repository, ingestionBus);

        FeedHealth health = new FeedHealth(
                SourceId.HR_MKT,
                Instant.parse("2026-04-19T16:02:00Z"),
                Instant.parse("2026-04-19T16:01:40Z"),
                0.88,
                140.0,
                290.0,
                5L,
                0,
                "IDLE",
                ""
        );
        when(feedClient.currentHealth()).thenReturn(health);

        Instant observedAt = Instant.parse("2026-04-19T16:02:05Z");
        service.emitFeedHealthEvents(observedAt);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IngestEvent<FeedHealth>> eventCaptor = ArgumentCaptor.forClass((Class) IngestEvent.class);
        verify(ingestionBus).publish(eventCaptor.capture());
        verify(repository, never()).saveAll(anyList());

        IngestEvent<FeedHealth> event = eventCaptor.getValue();
        assertEquals(SourceId.HR_MKT, event.source());
        assertEquals(FeedHealthService.TOPIC, event.topic());
        assertEquals(observedAt, event.observedAt());
        assertEquals(health, event.payload());
    }

    @Test
    void persistFeedHealthSamplesStoresLatencyQuantilesAndNullsUnavailableValues() {
        @SuppressWarnings("unchecked")
        FeedClient<Object> feedClient = mock(FeedClient.class);
        FeedHealthSampleRepository repository = mock(FeedHealthSampleRepository.class);
        IngestionBus ingestionBus = mock(IngestionBus.class);
        FeedHealthService service = new FeedHealthService(List.of(feedClient), repository, ingestionBus);

        when(feedClient.currentHealth()).thenReturn(new FeedHealth(
                SourceId.TTS_POST,
                Instant.parse("2026-04-19T16:00:00Z"),
                null,
                1.0,
                -1.0,
                -1.0,
                90L,
                1,
                "ACTIVE",
                ""
        ));

        Instant observedAt = Instant.parse("2026-04-19T16:03:00Z");
        service.persistFeedHealthSamples(observedAt);

        ArgumentCaptor<List<FeedHealthSample>> sampleCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(sampleCaptor.capture());

        FeedHealthSample sample = sampleCaptor.getValue().get(0);
        assertEquals(SourceId.TTS_POST, sample.getSourceId());
        assertEquals(LocalDateTime.ofInstant(observedAt, ZoneOffset.UTC), sample.getObservedAt());
        assertEquals(1.0, sample.getRollingSuccessRate5m(), 1.0e-9);
        assertNull(sample.getRollingP50LatencyMs());
        assertNull(sample.getRollingP95LatencyMs());
        assertEquals(1, sample.getInFlight());
        assertEquals("ACTIVE", sample.getBackoffState());
        assertNull(sample.getLastError());
    }
}
