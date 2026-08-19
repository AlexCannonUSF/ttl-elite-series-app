package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.ScrapeRun;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.ScrapeErrorRepository;
import com.ttl.tabletennis.repository.ScrapeRunRepository;
import com.ttl.tabletennis.service.PlayerIdentityService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtSeriesScraperMutationEventTests {

    @Test
    void committedMatchPublishesAnUpdatedMutationEventEvenIfBatchLaterFails() throws Exception {
        MatchRepository matchRepository = mock(MatchRepository.class);
        PlayerIdentityService identityService = mock(PlayerIdentityService.class);
        ScrapeRunRepository runRepository = mock(ScrapeRunRepository.class);
        ScrapeErrorRepository errorRepository = mock(ScrapeErrorRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        TtSeriesScraper scraper = new TtSeriesScraper(
                matchRepository, identityService, runRepository, errorRepository, eventPublisher);

        Player player1 = player(11L, "Fresh", "Alpha");
        Player player2 = player(22L, "Fresh", "Beta");
        when(identityService.resolveOrCreatePlayer("Fresh Alpha")).thenReturn(player1);
        when(identityService.resolveOrCreatePlayer("Fresh Beta")).thenReturn(player2);

        Match existing = new Match();
        existing.setExternalId("existing-1");
        existing.setDate(LocalDate.of(2026, 8, 15));
        existing.setPlayer1(player1);
        existing.setPlayer2(player2);
        when(matchRepository.findByExternalId("existing-1")).thenReturn(Optional.of(existing));

        ScrapeRun persistedRun = new ScrapeRun();
        ReflectionTestUtils.setField(persistedRun, "id", 91L);
        when(runRepository.findMaxRunNumber()).thenReturn(0);
        when(runRepository.save(any(ScrapeRun.class))).thenAnswer(invocation -> {
            ScrapeRun row = invocation.getArgument(0);
            if (row.getId() == null) ReflectionTestUtils.setField(row, "id", 91L);
            return row;
        });
        when(runRepository.findById(91L)).thenReturn(Optional.of(persistedRun));

        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(scraper, "markStart", "OFFICIAL_RESULTS")));
        Object row = newMatchRow();
        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(scraper, "upsertMatch", "post-1", row)));
        ReflectionTestUtils.invokeMethod(scraper, "markError", new java.io.IOException("later page failed"));
        ReflectionTestUtils.invokeMethod(scraper, "markFinish");

        assertTrue(existing.isComplete());
        assertEquals("3:1", existing.getResult());
        verify(matchRepository).save(existing);
        ArgumentCaptor<ScrapeCompletedEvent> event = ArgumentCaptor.forClass(ScrapeCompletedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(1, event.getValue().savedMatches());
        assertEquals(0, event.getValue().newMatches());
        assertEquals(1, event.getValue().updatedMatches());
        assertEquals(java.util.Set.of(11L, 22L), event.getValue().affectedPlayerIds());
    }

    private static Player player(Long id, String first, String last) {
        Player player = new Player(first, last);
        player.setId(id);
        return player;
    }

    private static Object newMatchRow() throws Exception {
        Class<?> rowType = Class.forName("com.ttl.tabletennis.scrape.TtSeriesScraper$MatchRow");
        var constructor = rowType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object row = constructor.newInstance();
        ReflectionTestUtils.setField(row, "player1Raw", "Fresh Alpha");
        ReflectionTestUtils.setField(row, "player2Raw", "Fresh Beta");
        ReflectionTestUtils.setField(row, "result", "3:1");
        ReflectionTestUtils.setField(row, "date", LocalDate.of(2026, 8, 15));
        ReflectionTestUtils.setField(row, "externalId", "existing-1");
        ReflectionTestUtils.setField(row, "slotKey", "12:00");
        ReflectionTestUtils.setField(row, "rowOrdinal", 1);
        return row;
    }
}
