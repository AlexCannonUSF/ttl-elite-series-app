package com.ttl.tabletennis.scrape;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.repository.PaperTradeModelCallRepository;
import com.ttl.tabletennis.repository.PaperTradeSessionRepository;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class HardRockScoreStreamClientTests {

    @Test
    void preservesInitialIdentityAcrossPatchesAndPublishesTerminalScore() {
        IngestionBus ingestionBus = mock(IngestionBus.class);
        HardRockScoreStreamClient client = client(ingestionBus);
        MatchOdds seed = new MatchOdds(
                "Live Alpha", "Live Beta", 1.84, 2.02,
                "Live Alpha vs Live Beta", "TT Elite Series", true,
                "2026-08-08T20:00:00Z", "HARD_ROCK_GQL|event=event-42"
        );
        seed.setExternalEventId("event-42");
        client.track(seed);

        client.acceptMessage("""
                {"SubscriptionResponse":{"data":{
                  "id":"event-42","name":"Live Alpha vs Live Beta","inplay":true,"displayed":true,
                  "participants":[{"name":"Live Alpha"},{"name":"Live Beta"}],
                  "simpleMatchState":{"gamesA":1,"gamesB":0,"pointsInCurrentGameA":1,"pointsInCurrentGameB":3,
                    "preMatch":false,"matchCompleted":false,"sourceFeedCode":"BETRADAR_UF",
                    "sourceFeedEventId":"sr:match:42","gameScoreInGameN":{"1":{"A":11,"B":8}}}
                }}}
                """);

        List<MatchOdds> liveRows = client.snapshotsForEventIds(List.of("event-42"));
        assertEquals(1, liveRows.size());
        assertEquals("1-0 (1-3)", liveRows.get(0).getLiveScore());
        assertEquals("LIVE_EARLY", liveRows.get(0).getMatchPhase());
        assertEquals("11-8", liveRows.get(0).getScoreDetail());
        assertTrue(liveRows.get(0).isLive());
        assertFalse(liveRows.get(0).isMatchCompleted());

        client.acceptMessage("""
                {"Event":{"id":"event-42","displayed":false,"resulted":true,
                  "simpleMatchState":{"gamesA":3,"gamesB":1,"pointsInCurrentGameA":0,"pointsInCurrentGameB":0,
                    "matchCompleted":true,"gameScoreInGameN":{"4":{"A":11,"B":7}}}}}
                """);

        MatchOdds completed = client.snapshotsForEventIds(List.of("event-42")).get(0);
        assertEquals("Live Alpha", completed.getPlayerA());
        assertEquals("Live Beta", completed.getPlayerB());
        assertEquals("3-1", completed.getLiveScore());
        assertEquals("FINISHED", completed.getMatchPhase());
        assertEquals("11-8, 11-7", completed.getScoreDetail());
        assertFalse(completed.isLive());
        assertTrue(completed.isResulted());
        assertTrue(completed.isMatchCompleted());
        assertEquals("BETRADAR_UF", completed.getSourceFeedCode());
        assertEquals("sr:match:42", completed.getSourceFeedEventId());
        assertEquals(HardRockScoreStreamClient.SOURCE_TYPE, completed.getSourceType());

        var eventCaptor = forClass(IngestEvent.class);
        verify(ingestionBus, times(2)).publish(eventCaptor.capture());
        IngestEvent<?> terminalEvent = eventCaptor.getAllValues().get(1);
        assertEquals(SourceId.HR_TGT, terminalEvent.source());
        assertEquals("score.observed", terminalEvent.topic());
        assertEquals(0.99, terminalEvent.confidence(), 0.0001);
    }

    @Test
    void identicalPatchDoesNotPublishDuplicateObservation() {
        IngestionBus ingestionBus = mock(IngestionBus.class);
        HardRockScoreStreamClient client = client(ingestionBus);
        MatchOdds seed = new MatchOdds("Alpha", "Beta", 1.90, 1.90);
        seed.setExternalEventId("event-dup");
        client.track(seed);
        String message = """
                {"SubscriptionResponse":{"data":{"id":"event-dup","inplay":true,
                  "simpleMatchState":{"gamesA":0,"gamesB":0,"pointsInCurrentGameA":4,"pointsInCurrentGameB":5,
                    "preMatch":false,"matchCompleted":false}}}}
                """;

        client.acceptMessage(message);
        client.acceptMessage(message);

        verify(ingestionBus, times(1)).publish(org.mockito.ArgumentMatchers.any(IngestEvent.class));
    }

    private static HardRockScoreStreamClient client(IngestionBus ingestionBus) {
        return new HardRockScoreStreamClient(
                new ObjectMapper(),
                ingestionBus,
                mock(PaperTradeSessionRepository.class),
                mock(PaperTradeModelCallRepository.class),
                mock(HttpClient.class),
                true,
                "wss://example.invalid/graphql-ws",
                "FLORIDA_ONLINE",
                "enus",
                "us",
                "r.fl",
                600,
                720,
                12
        );
    }
}
