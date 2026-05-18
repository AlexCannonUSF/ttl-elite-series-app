package com.ttl.tabletennis.scrape;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedClientContractTests {

    @Test
    void phaseZeroDefaultsAreSafeForPollOnlyClients() {
        FeedClient<String> client = new FeedClient<>() {
            @Override
            public SourceId source() {
                return SourceId.HR_TGT;
            }

            @Override
            public List<IngestEvent<String>> pullOnce(PullContext ctx) {
                return List.of(new IngestEvent<>(
                        SourceId.HR_TGT,
                        "odds.updated",
                        Instant.parse("2026-04-16T12:00:00Z"),
                        0.92,
                        ctx.correlationId(),
                        "raw://test/1",
                        "payload"
                ));
            }

            @Override
            public FeedHealth currentHealth() {
                return FeedHealth.idle(SourceId.HR_TGT);
            }
        };

        FeedClient.PullContext pullContext = FeedClient.PullContext.now("corr-123");
        FeedClient.StreamContext streamContext = FeedClient.StreamContext.now("corr-456");

        assertEquals(SourceId.HR_TGT, client.source());
        assertEquals(1, client.pullOnce(pullContext).size());
        assertEquals(Optional.empty(), client.stream(streamContext));
        assertEquals(Set.of(), client.capabilities());
        assertEquals(new FeedClient.BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30), 2.0, 0.20), client.backoff());
        assertEquals(SourceId.HR_TGT, client.currentHealth().source());
    }

    @Test
    void recordsValidateAndNormalizeInput() {
        IngestEvent<String> event = new IngestEvent<>(
                SourceId.HR_MKT,
                " odds.updated ",
                null,
                0.80,
                null,
                null,
                "row"
        );
        assertEquals(SourceId.HR_MKT, event.source());
        assertEquals("odds.updated", event.topic());
        assertEquals("", event.correlationId());
        assertEquals("", event.rawPayloadRef());

        FeedHealth health = new FeedHealth(SourceId.HR_MKT, null, null, 0.75, 125.0, 320.0, 8L, 2, null, null);
        assertEquals("IDLE", health.backoffState());
        assertEquals("", health.lastError());
        assertEquals(125.0, health.rollingP50LatencyMs(), 0.0001);
        assertEquals(8L, health.stalenessSeconds());

        assertEquals(TrustTier.T1_SPORTSBOOK, SourceId.fromValue(" hr_mkt ").orElseThrow().tier());
        assertEquals(TrustTier.T1_SPORTSBOOK, SourceId.fromValue(" hr_tree ").orElseThrow().tier());
        assertEquals(TrustTier.T4_CONFIRMATION, SourceId.fromValue(" ittf_wtt ").orElseThrow().tier());

        FeedClient.PullContext pullContext = new FeedClient.PullContext(null, null, null);
        FeedClient.StreamContext streamContext = new FeedClient.StreamContext(null, null, Map.of("mode", "shadow"));
        assertTrue(!pullContext.requestedAt().isAfter(Instant.now()));
        assertEquals("", pullContext.correlationId());
        assertEquals("shadow", streamContext.attributes().get("mode"));

        assertThrows(IllegalArgumentException.class, () -> new IngestEvent<>(null, "topic", Instant.now(), 1.1, "", "", "payload"));
        assertThrows(IllegalArgumentException.class, () -> new FeedHealth(null, null, null, -0.1, -1.0, -1.0, -1L, 0, "IDLE", ""));
        assertThrows(IllegalArgumentException.class, () -> new FeedClient.BackoffPolicy(Duration.ZERO, Duration.ofSeconds(5), 2.0, 0.1));
    }
}
