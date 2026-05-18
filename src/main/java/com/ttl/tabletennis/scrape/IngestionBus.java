package com.ttl.tabletennis.scrape;

public interface IngestionBus {

    void publish(IngestEvent<?> event);

    default void publishAll(Iterable<? extends IngestEvent<?>> events) {
        if (events == null) {
            return;
        }
        for (IngestEvent<?> event : events) {
            if (event != null) {
                publish(event);
            }
        }
    }
}
