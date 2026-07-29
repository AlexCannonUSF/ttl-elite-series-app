package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RawPayloadStoringIngestionBus implements IngestionBus {

    private static final Logger log = LoggerFactory.getLogger(RawPayloadStoringIngestionBus.class);

    private final IngestionBus delegate;
    private final RawPayloadStore rawPayloadStore;
    private final ObjectMapper objectMapper;

    public RawPayloadStoringIngestionBus(IngestionBus delegate,
                                         RawPayloadStore rawPayloadStore,
                                         ObjectMapper objectMapper) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (rawPayloadStore == null) {
            throw new IllegalArgumentException("rawPayloadStore must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.delegate = delegate;
        this.rawPayloadStore = rawPayloadStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(IngestEvent<?> event) {
        if (event == null) {
            return;
        }
        delegate.publish(withRawPayloadRef(event));
    }

    IngestEvent<?> withRawPayloadRef(IngestEvent<?> event) {
        if (!rawPayloadStore.isEnabled()) {
            return event;
        }
        if (hasText(event.rawPayloadRef())) {
            return event;
        }
        if (!hasText(event.correlationId())) {
            return event;
        }

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(event.payload());
        } catch (Exception e) {
            log.warn("[raw-store] serialization failed; forwarding without rawPayloadRef. topic={} source={} correlationId={}: {}",
                    event.topic(), event.source().id(), event.correlationId(), e.getMessage());
            return event;
        }

        String ref;
        try {
            ref = rawPayloadStore.put(event.source(), event.correlationId(), event.observedAt(), body);
        } catch (RuntimeException e) {
            log.warn("[raw-store] upload failed; forwarding without rawPayloadRef. topic={} source={} correlationId={}: {}",
                    event.topic(), event.source().id(), event.correlationId(), e.getMessage());
            return event;
        }

        if (!hasText(ref)) {
            return event;
        }

        return rebuildWithRef(event, ref);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IngestEvent<?> rebuildWithRef(IngestEvent<?> event, String ref) {
        return new IngestEvent(
                event.source(),
                event.topic(),
                event.observedAt(),
                event.confidence(),
                event.correlationId(),
                ref,
                event.payload()
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
