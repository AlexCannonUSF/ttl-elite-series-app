package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.PaperTradeBet;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.SettlementReviewItemDto;
import com.ttl.tabletennis.dto.SettlementReviewPageDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PaperTradeBetRepository;
import com.ttl.tabletennis.repository.SettlementContradictionRecordRepository;
import com.ttl.tabletennis.service.papertrade.ScoreWinnerResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementReviewServiceTests {

    @Test
    void explainsHighTrustArchiveSelectionWithNormalizedEvidence() {
        Fixture fixture = fixture();
        PaperTradeBet bet = settledArchiveBet();
        Match candidate = completedMatch(55L, 11L, 22L, 11L);

        when(fixture.betRepository.findAllByStatusInOrderBySettledAtDescIdDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bet), PageRequest.of(0, 20), 1));
        when(fixture.matchRepository.findById(55L)).thenReturn(Optional.of(candidate));
        when(fixture.matchRepository.findCompletedRecentMatchesByPlayersUpToDate(
                eq(11L), eq(22L), eq(LocalDate.of(2026, 8, 7)), any(Pageable.class)))
                .thenReturn(List.of(candidate));
        when(fixture.matchRepository.findCompletedMatchesByPlayersOnDate(
                11L, 22L, LocalDate.of(2026, 8, 7))).thenReturn(List.of(candidate));
        when(fixture.scoreWinnerResolver.determineWinnerFromScore(any(), eq(11L), eq(22L), any(), eq(true)))
                .thenReturn(Optional.of(11L));

        SettlementReviewPageDto page = fixture.service.review(0, 20, false);
        SettlementReviewItemDto item = page.items().get(0);

        assertEquals(55L, item.selectedCandidateMatchId());
        assertEquals(LocalDate.of(2026, 8, 7), item.selectedCandidateDate());
        assertEquals(1.0, item.playerSetConfidence());
        assertEquals(Boolean.TRUE, item.feedIdentityMatch());
        assertTrue(item.selectedCandidateInRecentCompleted());
        assertEquals("HIGH", item.trustBand());
        assertFalse(item.suspicious());
        assertTrue(item.explanation().contains("archive match #55"));
    }

    @Test
    void flagsMissingArchiveCandidateScoreConflictAndSameDayCollision() {
        Fixture fixture = fixture();
        PaperTradeBet bet = settledArchiveBet();
        Match first = completedMatch(56L, 11L, 22L, 22L);
        Match second = completedMatch(57L, 11L, 22L, 11L);

        when(fixture.betRepository.findAllByStatusInOrderBySettledAtDescIdDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bet), PageRequest.of(0, 20), 1));
        when(fixture.matchRepository.findById(55L)).thenReturn(Optional.empty());
        when(fixture.matchRepository.findCompletedRecentMatchesByPlayersUpToDate(
                eq(11L), eq(22L), eq(LocalDate.of(2026, 8, 7)), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(fixture.matchRepository.findCompletedMatchesByPlayersOnDate(
                11L, 22L, LocalDate.of(2026, 8, 7))).thenReturn(List.of(first, second));
        when(fixture.scoreWinnerResolver.determineWinnerFromScore(any(), eq(11L), eq(22L), any(), eq(true)))
                .thenReturn(Optional.of(22L));

        SettlementReviewItemDto item = fixture.service.review(0, 20, false).items().get(0);

        assertTrue(item.suspicious());
        assertEquals("LOW", item.trustBand());
        assertTrue(item.suspicionFlags().contains(SettlementReviewService.FLAG_ARCHIVE_NOT_RECENT));
        assertTrue(item.suspicionFlags().contains(SettlementReviewService.FLAG_ARCHIVE_SCORE_CONFLICT));
        assertTrue(item.suspicionFlags().contains(SettlementReviewService.FLAG_MULTIPLE_SAME_DAY));
        assertTrue(item.explanation().contains("conflicts with the late score direction"));
    }

    private Fixture fixture() {
        PaperTradeBetRepository betRepository = mock(PaperTradeBetRepository.class);
        MatchRepository matchRepository = mock(MatchRepository.class);
        SettlementContradictionRecordRepository contradictionRepository = mock(SettlementContradictionRecordRepository.class);
        ScoreWinnerResolver scoreWinnerResolver = mock(ScoreWinnerResolver.class);
        when(contradictionRepository.findByEvidenceIdOrderByObservedAtDesc(
                ArgumentMatchers.<Long>any(), any(Pageable.class))).thenReturn(List.of());
        return new Fixture(
                betRepository,
                matchRepository,
                scoreWinnerResolver,
                new SettlementReviewService(betRepository, matchRepository, contradictionRepository, scoreWinnerResolver)
        );
    }

    private PaperTradeBet settledArchiveBet() {
        PaperTradeBet bet = new PaperTradeBet();
        setId(bet, PaperTradeBet.class, 41L);
        bet.setSessionId(7L);
        bet.setStatus(PaperTradeBet.STATUS_WON);
        bet.setEventName("Ada Ace vs. Bea Backhand");
        bet.setCompetitionName("TT Elite Series");
        bet.setPlayer1Id(11L);
        bet.setPlayer2Id(22L);
        bet.setPlayer1Name("Ada Ace");
        bet.setPlayer2Name("Bea Backhand");
        bet.setSideName("Ada Ace");
        bet.setWinnerPlayerId(11L);
        bet.setResultMatchId(55L);
        bet.setSettlementSource("DATABASE_RESULT");
        bet.setSettlementReason("SETTLED_FROM_DATABASE_RESULT_FEED_IDENTITY");
        bet.setSettlementConfidence(0.96);
        bet.setSettledAt(LocalDateTime.of(2026, 8, 7, 18, 0));
        bet.setPlacedAt(LocalDateTime.of(2026, 8, 7, 16, 0));
        bet.setStartTimeIso("2026-08-07T16:30:00-04:00");
        bet.setLockedSourceFeedEventId("hr:match:55");
        bet.setLastObservedScore("3-1");
        bet.setLastObservedPhase("FINISHED");
        return bet;
    }

    private Match completedMatch(Long id, Long player1Id, Long player2Id, Long winnerId) {
        Player player1 = new Player();
        player1.setId(player1Id);
        Player player2 = new Player();
        player2.setId(player2Id);
        Match match = new Match();
        match.setId(id);
        match.setDate(LocalDate.of(2026, 8, 7));
        match.setPlayer1(player1);
        match.setPlayer2(player2);
        match.setWinnerPlayerId(winnerId);
        match.setComplete(true);
        match.setSourceFeedEventId("hr:match:55");
        return match;
    }

    private void setId(Object target, Class<?> type, Long id) {
        try {
            Field field = type.getDeclaredField("id");
            field.setAccessible(true);
            field.set(target, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("unable to set id for test", ex);
        }
    }

    private record Fixture(PaperTradeBetRepository betRepository,
                           MatchRepository matchRepository,
                           ScoreWinnerResolver scoreWinnerResolver,
                           SettlementReviewService service) {
    }
}
