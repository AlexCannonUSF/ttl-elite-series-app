package com.ttl.tabletennis.mapper;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.dto.PlayerDto;

public final class PlayerMapper {

    private PlayerMapper() {
    }

    public static PlayerDto toDto(Player player) {
        if (player == null) return null;
        return new PlayerDto(player.getId(), player.getFirstName(), player.getLastName(), player.getName());
    }
}
