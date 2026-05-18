package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.PlayerAlias;
import com.ttl.tabletennis.dto.DuplicatePlayerCandidateDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerAliasRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.util.NameUtils;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class PlayerIdentityService {

    private final PlayerRepository playerRepository;
    private final PlayerAliasRepository playerAliasRepository;
    private final MatchRepository matchRepository;
    private final PlayerCanonicaliser playerCanonicaliser;

    public PlayerIdentityService(PlayerRepository playerRepository,
                                 PlayerAliasRepository playerAliasRepository,
                                 MatchRepository matchRepository,
                                 PlayerCanonicaliser playerCanonicaliser) {
        this.playerRepository = playerRepository;
        this.playerAliasRepository = playerAliasRepository;
        this.matchRepository = matchRepository;
        this.playerCanonicaliser = playerCanonicaliser;
    }

    @Transactional
    public Player resolveOrCreatePlayer(String rawName) {
        String normalized = NameUtils.normalizeForLookup(rawName);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Player name is blank");
        }

        PlayerCanonicaliser.CanonicalisationResult canonicalised = playerCanonicaliser.canonicalise(rawName);
        if (canonicalised.acceptedMatch().isPresent()) {
            Player existing = canonicalised.acceptedMatch().get().player();
            ensureAlias(existing, rawName);
            return existing;
        }

        Optional<Player> initialPlusLast = findByInitialLastName(rawName);
        if (initialPlusLast.isPresent()) {
            ensureAlias(initialPlusLast.get(), rawName);
            return initialPlusLast.get();
        }

        String[] split = NameUtils.splitFirstLast(rawName);
        String first = split[0].isBlank() ? "Unknown" : split[0];
        String last = split[1].isBlank() ? split[0] : split[1];
        Player created = new Player(first, last);
        created.setNormalizedName(normalized);
        created = playerRepository.save(created);
        ensureAlias(created, rawName);
        return created;
    }

    public Optional<Player> findCanonicalPlayer(String rawName) {
        String normalized = NameUtils.normalizeForLookup(rawName);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        PlayerCanonicaliser.CanonicalisationResult canonicalised = playerCanonicaliser.canonicalise(rawName);
        if (canonicalised.acceptedMatch().isPresent()) {
            return Optional.of(canonicalised.acceptedMatch().get().player());
        }
        return findByInitialLastName(rawName);
    }

    public List<PlayerAlias> listAliases(Long playerId) {
        if (playerId == null) {
            return playerAliasRepository.findAllByOrderByAliasNameAsc();
        }
        return playerAliasRepository.findByPlayerIdOrderByAliasNameAsc(playerId);
    }

    public List<DuplicatePlayerCandidateDto> findPotentialDuplicates(double minSimilarity, int limit) {
        double threshold = Math.max(0.5, Math.min(minSimilarity, 0.99));
        int maxRows = Math.max(1, Math.min(limit, 200));
        LevenshteinDistance distance = LevenshteinDistance.getDefaultInstance();

        List<Player> players = playerRepository.findAllByOrderByLastNameAscFirstNameAsc();
        List<DuplicatePlayerCandidateDto> out = new ArrayList<>();

        for (int i = 0; i < players.size(); i++) {
            Player a = players.get(i);
            String aNorm = a.getNormalizedName();
            for (int j = i + 1; j < players.size(); j++) {
                Player b = players.get(j);
                String bNorm = b.getNormalizedName();
                if (aNorm == null || bNorm == null || aNorm.isBlank() || bNorm.isBlank()) {
                    continue;
                }

                if (aNorm.equals(bNorm)) {
                    out.add(new DuplicatePlayerCandidateDto(a.getId(), a.getName(), b.getId(), b.getName(), 1.0));
                    continue;
                }
                if (!NameUtils.areNamesSimilar(aNorm, bNorm)) {
                    continue;
                }

                int edits = distance.apply(aNorm, bNorm);
                int maxLen = Math.max(aNorm.length(), bNorm.length());
                if (maxLen == 0) continue;

                double similarity = 1.0 - ((double) edits / maxLen);
                if (similarity >= threshold) {
                    out.add(new DuplicatePlayerCandidateDto(a.getId(), a.getName(), b.getId(), b.getName(), similarity));
                }
            }
        }

        return out.stream()
                .sorted(Comparator.comparingDouble(DuplicatePlayerCandidateDto::similarityScore).reversed())
                .limit(maxRows)
                .toList();
    }

    @Transactional
    public PlayerAlias upsertAlias(Long playerId, String aliasName) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId));

        String normalized = NameUtils.normalizeForLookup(aliasName);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Alias name is blank");
        }

        Optional<PlayerAlias> existing = playerAliasRepository.findByNormalizedAlias(normalized);
        if (existing.isPresent()) {
            PlayerAlias alias = existing.get();
            if (!alias.getPlayer().getId().equals(playerId)) {
                throw new IllegalArgumentException("Alias already mapped to player " + alias.getPlayer().getId());
            }
            if (!aliasName.equals(alias.getAliasName())) {
                alias.setAliasName(aliasName.trim());
                return playerAliasRepository.save(alias);
            }
            return alias;
        }

        PlayerAlias alias = new PlayerAlias(player, aliasName.trim());
        return playerAliasRepository.save(alias);
    }

    @Transactional
    public int mergePlayers(Long sourcePlayerId, Long targetPlayerId) {
        if (sourcePlayerId.equals(targetPlayerId)) {
            throw new IllegalArgumentException("sourcePlayerId and targetPlayerId must be different");
        }

        Player source = playerRepository.findById(sourcePlayerId)
                .orElseThrow(() -> new ResourceNotFoundException("Source player not found: " + sourcePlayerId));
        Player target = playerRepository.findById(targetPlayerId)
                .orElseThrow(() -> new ResourceNotFoundException("Target player not found: " + targetPlayerId));

        int impactedMatches = 0;
        impactedMatches += matchRepository.reassignPlayer1(source, target);
        impactedMatches += matchRepository.reassignPlayer2(source, target);
        impactedMatches += matchRepository.reassignWinner(sourcePlayerId, targetPlayerId);

        List<PlayerAlias> sourceAliases = playerAliasRepository.findByPlayerIdOrderByAliasNameAsc(sourcePlayerId);
        for (PlayerAlias alias : sourceAliases) {
            alias.setPlayer(target);
            playerAliasRepository.save(alias);
        }

        ensureAlias(target, source.getName());
        playerRepository.delete(source);
        return impactedMatches;
    }

    @Transactional
    public void ensureAlias(Player player, String rawName) {
        String normalized = NameUtils.normalizeForLookup(rawName);
        if (normalized.isBlank()) {
            return;
        }
        if (playerAliasRepository.existsByNormalizedAlias(normalized)) {
            return;
        }
        playerAliasRepository.save(new PlayerAlias(player, rawName.trim()));
    }

    private Optional<Player> findByInitialLastName(String rawName) {
        String[] split = NameUtils.splitFirstLast(rawName);
        if (split.length < 2) {
            return Optional.empty();
        }

        String first = split[0] == null ? "" : split[0].trim();
        String last = split[1] == null ? "" : split[1].trim();
        if (first.isBlank() || last.isBlank()) {
            return Optional.empty();
        }

        String normalizedFirst = NameUtils.normalizeForLookup(first);
        if (normalizedFirst.isBlank()) {
            return Optional.empty();
        }
        String initial = normalizedFirst.substring(0, 1).toLowerCase(Locale.ROOT);

        List<Player> candidates = playerRepository.findByLastNameIgnoreCase(last)
                .stream()
                .filter(player -> {
                    String normalizedCandidateFirst = NameUtils.normalizeForLookup(player.getFirstName());
                    return !normalizedCandidateFirst.isBlank()
                            && normalizedCandidateFirst.startsWith(initial);
                })
                .toList();

        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        return Optional.empty();
    }
}
