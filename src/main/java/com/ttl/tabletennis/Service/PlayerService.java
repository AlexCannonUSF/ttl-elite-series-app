package com.ttl.tabletennis.Service;

import com.ttl.tabletennis.Entity.Player;
import com.ttl.tabletennis.Repository.PlayerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PlayerService {

    private final PlayerRepository playerRepository;

    @Autowired
    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public List<Player> getAllPlayers() {
        return playerRepository.findAll();
    }

    public Player savePlayer(Player player) {
        return playerRepository.save(player);
    }

    public List<Player> findPlayersByLastName(String lastName) {
        return playerRepository.findByLastNameIgnoreCase(lastName);
    }

    public Player findOrCreatePlayer(String firstName, String lastName) {
        Optional<Player> existingPlayer = playerRepository.findByFirstNameIgnoreCaseAndLastNameIgnoreCase(firstName, lastName);
        return existingPlayer.orElseGet(() -> playerRepository.save(new Player(firstName, lastName)));
    }
}
