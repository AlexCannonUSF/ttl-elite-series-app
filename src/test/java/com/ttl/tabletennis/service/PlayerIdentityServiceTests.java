package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.PlayerAlias;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerAliasRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.util.MatchResultParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class PlayerIdentityServiceTests {

    @Autowired
    private PlayerIdentityService playerIdentityService;

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private PlayerAliasRepository playerAliasRepository;

    @Autowired
    private MatchRepository matchRepository;

    @Test
    void resolveOrCreateUsesAliasNormalization() {
        Player a = playerIdentityService.resolveOrCreatePlayer("José O'Neil");
        Player b = playerIdentityService.resolveOrCreatePlayer("Jose ONeil");

        assertNotNull(a.getId());
        assertEquals(a.getId(), b.getId());

        List<PlayerAlias> aliases = playerIdentityService.listAliases(a.getId());
        assertFalse(aliases.isEmpty());
    }

    @Test
    void mergePlayersReassignsMatchAndWinner() {
        Player source = playerRepository.save(new Player("Alex", "Cannon"));
        Player target = playerRepository.save(new Player("Alexander", "Cannon"));
        Player opponent = playerRepository.save(new Player("Nima", "Alamian"));

        Match m = new Match();
        m.setExternalId("merge-case-1");
        m.setDate(LocalDate.now().minusDays(1));
        m.setPlayer1(source);
        m.setPlayer2(opponent);
        MatchResultParser.applyToMatch(m, "3:1");
        m = matchRepository.save(m);

        playerIdentityService.upsertAlias(source.getId(), "A. Cannon");
        int impacted = playerIdentityService.mergePlayers(source.getId(), target.getId());

        assertTrue(impacted >= 1);
        Match updated = matchRepository.findById(m.getId()).orElseThrow();
        assertEquals(target.getId(), updated.getPlayer1().getId());
        assertEquals(target.getId(), updated.getWinnerPlayerId());
        assertTrue(playerRepository.findById(source.getId()).isEmpty());

        List<PlayerAlias> targetAliases = playerAliasRepository.findByPlayerIdOrderByAliasNameAsc(target.getId());
        assertTrue(targetAliases.stream().anyMatch(alias -> alias.getAliasName().equals("A. Cannon")));
    }

    @Test
    void upsertAliasRejectsDuplicateAliasAcrossPlayers() {
        Player first = playerRepository.save(new Player("First", "Player"));
        Player second = playerRepository.save(new Player("Second", "Player"));

        playerIdentityService.upsertAlias(first.getId(), "A. Player");

        assertThrows(IllegalArgumentException.class,
                () -> playerIdentityService.upsertAlias(second.getId(), "A Player"));
    }

    @Test
    void potentialDuplicatesReturnsSimilarNames() {
        playerRepository.save(new Player("Jose", "ONeil"));
        playerRepository.save(new Player("José", "O'Neil"));

        var candidates = playerIdentityService.findPotentialDuplicates(0.7, 20);

        assertTrue(candidates.stream().anyMatch(c -> c.similarityScore() >= 0.99));
    }

    @Test
    void findCanonicalPlayerResolvesInitialPlusLastName() {
        // Keep this fixture unique across the shared Spring/H2 test context.
        // Other integration tests may commit real-looking player names, which
        // must not turn this initial-resolution unit into an order-dependent
        // assertion about which duplicate canonical row was inserted first.
        Player target = playerRepository.save(new Player("Alan", "InitialLookupFixture20260815"));

        Optional<Player> resolved = playerIdentityService.findCanonicalPlayer("A. InitialLookupFixture20260815");

        assertTrue(resolved.isPresent());
        assertEquals(target.getId(), resolved.get().getId());
    }

    @Test
    void findCanonicalPlayerDoesNotGuessWhenInitialAmbiguous() {
        playerRepository.save(new Player("Adam", "Nowak"));
        playerRepository.save(new Player("Artur", "Nowak"));

        Optional<Player> resolved = playerIdentityService.findCanonicalPlayer("A. Nowak");

        assertTrue(resolved.isEmpty());
    }
}
