package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.Glicko2TauTuningDto;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.util.MatchResultParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class Glicko2RatingServiceTests {

    @Autowired
    private Glicko2RatingService glicko2RatingService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Test
    void tuneTauRanksCandidatesByPredictiveLoss() {
        Player p1 = playerRepository.save(new Player("Top", "Seed"));
        Player p2 = playerRepository.save(new Player("Upset", "Risk"));

        LocalDate start = LocalDate.now().minusDays(21);
        for (int i = 0; i < 8; i++) {
            saveMatch("tau-" + i, p1, p2, i % 3 == 0 ? "3:2" : "3:1", start.plusDays(i * 2L));
        }

        Glicko2TauTuningDto tuned = glicko2RatingService.tuneTau(
                start,
                start.plusDays(20),
                List.of(0.3, 0.5, 0.8)
        );

        assertNotNull(tuned);
        assertFalse(tuned.candidates().isEmpty());
        assertEquals(3, tuned.candidates().size());
        assertTrue(tuned.candidates().get(0).averageLogLoss() <= tuned.candidates().get(1).averageLogLoss());
        assertTrue(tuned.candidates().get(1).averageLogLoss() <= tuned.candidates().get(2).averageLogLoss());
        assertEquals(tuned.candidates().get(0).tau(), tuned.bestTau(), 0.000001);
        assertTrue(tuned.candidates().stream().allMatch(c -> c.predictions() > 0));
    }

    private void saveMatch(String externalId, Player p1, Player p2, String result, LocalDate date) {
        Match m = new Match();
        m.setExternalId(externalId);
        m.setDate(date);
        m.setPlayer1(p1);
        m.setPlayer2(p2);
        MatchResultParser.applyToMatch(m, result);
        matchRepository.save(m);
    }
}
