package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.repository.PlayerAliasRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PlayerCanonicaliserTests {

    private final PlayerCanonicaliser canonicaliser = new PlayerCanonicaliser(
            mock(PlayerRepository.class),
            mock(PlayerAliasRepository.class)
    );

    @Test
    void canonicaliseUsesCountryTiebreakBeforeFirstSeenAnchor() {
        Player polish = player(1L, "Jan", "Kowalski");
        Player czech = player(2L, "Jan", "Kowalski");

        PlayerCanonicaliser.CanonicalisationResult result = canonicaliser.canonicalise(
                new PlayerCanonicaliser.CanonicalisationRequest("Jan Kowalski", "CZ"),
                List.of(
                        candidate(polish, "Jan Kowalski", "PL", Instant.parse("2026-01-01T00:00:00Z")),
                        candidate(czech, "Jan Kowalski", "CZ", Instant.parse("2026-02-01T00:00:00Z"))
                )
        );

        assertTrue(result.resolved());
        assertEquals(2L, result.acceptedMatch().orElseThrow().player().getId());
        assertTrue(result.acceptedMatch().orElseThrow().countryMatched());
    }

    @Test
    void canonicaliseUsesFirstSeenAnchorWhenCountryIsNotAvailable() {
        Player early = player(10L, "Damian", "Fira");
        Player late = player(11L, "Damian", "Fira");

        PlayerCanonicaliser.CanonicalisationResult result = canonicaliser.canonicalise(
                new PlayerCanonicaliser.CanonicalisationRequest("Damian Fira", null),
                List.of(
                        candidate(late, "Damian Fira", null, Instant.parse("2026-03-01T00:00:00Z")),
                        candidate(early, "Damian Fira", null, Instant.parse("2026-01-01T00:00:00Z"))
                )
        );

        assertTrue(result.resolved());
        assertEquals(10L, result.acceptedMatch().orElseThrow().player().getId());
    }

    @Test
    void canonicaliseReturnsAmbiguousWhenTopCandidatesRemainTied() {
        Player first = player(20L, "Adam", "Nowak");
        Player second = player(21L, "Adam", "Nowak");

        PlayerCanonicaliser.CanonicalisationResult result = canonicaliser.canonicalise(
                new PlayerCanonicaliser.CanonicalisationRequest("Adam Nowak", null),
                List.of(
                        candidate(first, "Adam Nowak", null, null),
                        candidate(second, "Adam Nowak", null, null)
                )
        );

        assertFalse(result.resolved());
        assertTrue(result.ambiguous());
        assertEquals(2, result.rankedCandidates().size());
    }

    @Test
    void canonicaliseReturnsEmptyWhenNoCandidateMeetsThreshold() {
        Player target = player(30L, "Jan", "Lis");

        PlayerCanonicaliser.CanonicalisationResult result = canonicaliser.canonicalise(
                new PlayerCanonicaliser.CanonicalisationRequest("Mateusz Czernik", null),
                List.of(candidate(target, "Jan Lis", null, null))
        );

        assertFalse(result.resolved());
        assertFalse(result.ambiguous());
        assertTrue(result.rankedCandidates().isEmpty());
    }

    @Test
    void canonicaliseNormalizesAliasStyleNamesIntoExactMatches() {
        Player target = player(40L, "Jose", "ONeil");

        PlayerCanonicaliser.CanonicalisationResult result = canonicaliser.canonicalise(
                new PlayerCanonicaliser.CanonicalisationRequest("José O'Neil", null),
                List.of(candidate(target, "Jose ONeil", null, Instant.parse("2026-01-01T00:00:00Z")))
        );

        assertTrue(result.resolved());
        assertEquals(40L, result.acceptedMatch().orElseThrow().player().getId());
        assertEquals(1.0, result.acceptedMatch().orElseThrow().similarity(), 0.000001);
    }

    @Test
    void canonicaliseRejectsSharedFirstNameWhenSurnamesAreUnrelated() {
        Player wrongTarget = player(50L, "Mateusz", "Kalinowski");

        PlayerCanonicaliser.CanonicalisationResult result = canonicaliser.canonicalise(
                new PlayerCanonicaliser.CanonicalisationRequest("Sikon, Mateusz", null),
                List.of(candidate(wrongTarget, "Mateusz Kalinowski", null, null))
        );

        assertFalse(result.resolved());
        assertFalse(result.ambiguous());
        assertTrue(result.rankedCandidates().isEmpty());
    }

    private PlayerCanonicaliser.PlayerCandidate candidate(Player player,
                                                          String candidateName,
                                                          String countryCode,
                                                          Instant firstSeenAt) {
        return new PlayerCanonicaliser.PlayerCandidate(
                player,
                candidateName,
                null,
                countryCode,
                firstSeenAt,
                PlayerCanonicaliser.CandidateSource.ALIAS
        );
    }

    private Player player(Long id, String firstName, String lastName) {
        Player player = new Player(firstName, lastName);
        player.setId(id);
        player.setNormalizedName((firstName + " " + lastName).toLowerCase());
        return player;
    }
}
