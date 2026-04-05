package com.ttl.tabletennis.mapper;

import com.ttl.tabletennis.domain.PlayerAlias;
import com.ttl.tabletennis.dto.PlayerAliasDto;

public final class AliasMapper {

    private AliasMapper() {
    }

    public static PlayerAliasDto toDto(PlayerAlias alias) {
        if (alias == null) return null;
        return new PlayerAliasDto(
                alias.getId(),
                alias.getPlayer().getId(),
                alias.getPlayer().getName(),
                alias.getAliasName(),
                alias.getNormalizedAlias(),
                alias.getCreatedAt()
        );
    }
}
