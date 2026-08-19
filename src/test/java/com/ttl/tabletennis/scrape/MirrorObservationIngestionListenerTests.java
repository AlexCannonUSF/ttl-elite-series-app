package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.MirrorObservation;
import com.ttl.tabletennis.repository.MirrorObservationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MirrorObservationIngestionListenerTests {

    @Test
    void onIngestEventPersistsTierTwoMirrorPayloads() {
        MirrorObservationRepository repository = mock(MirrorObservationRepository.class);
        MirrorObservationIngestionListener listener = new MirrorObservationIngestionListener(repository, new MirrorObservationFactory());

        listener.onIngestEvent(new IngestEvent<>(
                SourceId.SOFASCORE,
                "score.observed",
                Instant.parse("2026-04-19T18:45:00Z"),
                0.78,
                "corr-sofa",
                "",
                new MirrorObservationPayload(
                        "tracked-123",
                        "sofa-456",
                        "Adam Staniczek",
                        "Dariusz Maszczynski",
                        "TT Cup",
                        "LIVE_LATE",
                        2,
                        2,
                        9,
                        7,
                        "P1",
                        false,
                        "{\"fixtureId\":\"sofa-456\"}"
                )
        ));

        ArgumentCaptor<MirrorObservation> captor = ArgumentCaptor.forClass(MirrorObservation.class);
        verify(repository).save(captor.capture());
        assertEquals("tracked-123", captor.getValue().getTrackedEventId());
        assertEquals(SourceId.SOFASCORE, captor.getValue().getSourceId());
    }

    @Test
    void onIngestEventIgnoresNonMirrorPayloads() {
        MirrorObservationRepository repository = mock(MirrorObservationRepository.class);
        MirrorObservationIngestionListener listener = new MirrorObservationIngestionListener(repository, new MirrorObservationFactory());

        listener.onIngestEvent(new IngestEvent<>(
                SourceId.TTS_POST,
                "result.confirmed",
                Instant.now(),
                1.0,
                "corr-official",
                "",
                new TtSeriesScraper.OfficialLedgerMatch("official", "url", "A", "B", "3:1", null, "A")
        ));

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(MirrorObservation.class));
    }
}
