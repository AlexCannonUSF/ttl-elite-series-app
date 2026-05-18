package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.PlayerDto;
import com.ttl.tabletennis.exception.ResourceNotFoundException;
import com.ttl.tabletennis.mapper.PlayerMapper;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.request.CreatePlayerRequest;
import com.ttl.tabletennis.util.NameUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class PlayerService {

    private final PlayerRepository playerRepository;

    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public List<Player> getAllPlayers() {
        return playerRepository.findAllByOrderByLastNameAscFirstNameAsc();
    }

    public List<PlayerDto> getAllPlayerDtos() {
        return getAllPlayers().stream()
                .map(PlayerMapper::toDto)
                .toList();
    }

    @Transactional
    public Player savePlayer(Player player) {
        String firstName = player.getFirstName() == null ? "" : player.getFirstName().trim();
        String lastName = player.getLastName() == null ? "" : player.getLastName().trim();
        if (firstName.isBlank() || lastName.isBlank()) {
            throw new IllegalArgumentException("firstName and lastName are required");
        }

        player.setFirstName(firstName);
        player.setLastName(lastName);
        player.setNormalizedName(NameUtils.normalizeForLookup(player.getName()));
        return playerRepository.save(player);
    }

    @Transactional
    public PlayerDto createPlayer(CreatePlayerRequest request) {
        Player player = new Player(request.firstName().trim(), request.lastName().trim());
        return PlayerMapper.toDto(savePlayer(player));
    }

    public List<Player> findPlayersByLastName(String lastName) {
        return playerRepository.findByLastNameIgnoreCase(lastName);
    }

    public List<Player> searchPlayers(String query) {
        if (query == null || query.isBlank()) {
            return getAllPlayers();
        }
        return playerRepository.searchPlayers(query.trim());
    }

    public Player findOrCreatePlayer(String firstName, String lastName) {
        Optional<Player> existingPlayer = playerRepository.findByFirstNameIgnoreCaseAndLastNameIgnoreCase(firstName, lastName);
        return existingPlayer.orElseGet(() -> savePlayer(new Player(firstName, lastName)));
    }

    public Player getPlayerOrThrow(Long playerId) {
        return playerRepository.findById(playerId)
                .orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId));
    }
}
