package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.dto.PlayerDto;
import com.ttl.tabletennis.mapper.PlayerMapper;
import com.ttl.tabletennis.request.CreatePlayerRequest;
import com.ttl.tabletennis.service.PlayerService;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerService playerService;

    public PlayerController(PlayerService playerService) {
        this.playerService = playerService;
    }

    @GetMapping
    public List<PlayerDto> getAllPlayers() {
        return playerService.getAllPlayerDtos();
    }

    @GetMapping("/search")
    public List<PlayerDto> searchPlayers(@RequestParam(value = "q", required = false) String q,
                                         @RequestParam(value = "query", required = false) String query) {
        String search = StringUtils.hasText(q) ? q : query;
        if (!StringUtils.hasText(search)) {
            return playerService.getAllPlayerDtos();
        }
        return playerService.searchPlayers(search)
                .stream()
                .map(PlayerMapper::toDto)
                .toList();
    }

    @PostMapping
    public PlayerDto addPlayer(@Valid @RequestBody CreatePlayerRequest request) {
        return playerService.createPlayer(request);
    }
}
