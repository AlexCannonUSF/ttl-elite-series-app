package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.MirrorObservation;
import com.ttl.tabletennis.repository.MirrorObservationRepository;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class MirrorObservationIngestionListener {

    private final MirrorObservationRepository mirrorObservationRepository;
    private final MirrorObservationFactory mirrorObservationFactory;

    public MirrorObservationIngestionListener(MirrorObservationRepository mirrorObservationRepository,
                                              MirrorObservationFactory mirrorObservationFactory) {
        this.mirrorObservationRepository = mirrorObservationRepository;
        this.mirrorObservationFactory = mirrorObservationFactory;
    }

    @Async("ttlIngestionBusExecutor")
    @EventListener
    public void onIngestEvent(IngestEvent<?> event) {
        if (event == null || !(event.payload() instanceof MirrorObservationPayload)) {
            return;
        }
        if (event.source() == null || event.source().tier() != TrustTier.T2_MIRROR) {
            return;
        }

        @SuppressWarnings("unchecked")
        IngestEvent<MirrorObservationPayload> mirrorEvent = (IngestEvent<MirrorObservationPayload>) event;
        MirrorObservation observation = mirrorObservationFactory.fromPayload(mirrorEvent);
        mirrorObservationRepository.save(observation);
    }
}
