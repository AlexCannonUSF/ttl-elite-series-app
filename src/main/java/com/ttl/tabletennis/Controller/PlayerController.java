package com.ttl.tabletennis.Controller;

import com.ttl.tabletennis.Entity.Player;
import com.ttl.tabletennis.Service.PlayerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private static final Logger logger = Logger.getLogger(PlayerController.class.getName());

    private final PlayerService playerService;

    @Autowired
    public PlayerController(PlayerService playerService) {
        this.playerService = playerService;
    }

    @GetMapping
    public List<Player> getAllPlayers() {
        logger.info("Fetching all players via REST");
        return playerService.getAllPlayers();
    }

    @PostMapping
    public Player addPlayer(@RequestBody Player player) {
        logger.info("Adding new player (REST): " + player.getName());
        return playerService.savePlayer(player);
    }
}
