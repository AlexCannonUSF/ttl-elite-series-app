package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import com.ttl.tabletennis.service.PlayerIdentityService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PlayerAliasIngestionListenerTests {

    @Test
    void onIngestEventSeedsAliasesFromMatchOddsPlayers() {
        PlayerIdentityService playerIdentityService = mock(PlayerIdentityService.class);
        PlayerAliasIngestionListener listener = new PlayerAliasIngestionListener(playerIdentityService);

        MatchOdds odds = new MatchOdds("Adam Staniczek", "Dariusz Maszczynski", 1.74, 2.15);
        IngestEvent<MatchOdds> event = new IngestEvent<>(
                SourceId.HR_MKT,
                "odds.updated",
                null,
                0.95,
                "corr-hr",
                "",
                odds
        );

        listener.onIngestEvent(event);

        verify(playerIdentityService).resolveOrCreatePlayer("Adam Staniczek");
        verify(playerIdentityService).resolveOrCreatePlayer("Dariusz Maszczynski");
    }

    @Test
    void onIngestEventSeedsDistinctOfficialLedgerNamesOnlyOnce() {
        PlayerIdentityService playerIdentityService = mock(PlayerIdentityService.class);
        PlayerAliasIngestionListener listener = new PlayerAliasIngestionListener(playerIdentityService);

        TtSeriesScraper.OfficialLedgerMatch match = new TtSeriesScraper.OfficialLedgerMatch(
                "official-h2h",
                "https://www.tt-series.com/h2h/adam-dariusz",
                "Adam Staniczek",
                "Dariusz Maszczynski",
                "3:1",
                LocalDate.of(2026, 4, 19),
                "Adam Staniczek"
        );

        listener.onIngestEvent(new IngestEvent<>(
                SourceId.TTS_POST,
                "result.confirmed",
                null,
                1.0,
                "corr-tts",
                "",
                match
        ));

        verify(playerIdentityService, times(1)).resolveOrCreatePlayer("Adam Staniczek");
        verify(playerIdentityService, times(1)).resolveOrCreatePlayer("Dariusz Maszczynski");
    }

    @Test
    void extractPlayerNamesIgnoresUnsupportedPayloadsAndBlankNames() {
        PlayerAliasIngestionListener listener = new PlayerAliasIngestionListener(mock(PlayerIdentityService.class));

        Set<String> unsupported = listener.extractPlayerNames("not-a-match-payload");
        Set<String> blanks = listener.extractPlayerNames(new MatchOdds(" ", null, 1.80, 2.00));

        assertEquals(Set.of(), unsupported);
        assertEquals(Set.of(), blanks);
    }

    @Test
    void onIngestEventIgnoresNullPayloads() {
        PlayerIdentityService playerIdentityService = mock(PlayerIdentityService.class);
        PlayerAliasIngestionListener listener = new PlayerAliasIngestionListener(playerIdentityService);

        listener.onIngestEvent(null);

        verifyNoInteractions(playerIdentityService);
    }
}
