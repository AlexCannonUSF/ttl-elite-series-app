package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;

public record PlayerAliasDto(Long id,
                             Long playerId,
                             String playerName,
                             String aliasName,
                             String normalizedAlias,
                             LocalDateTime createdAt) {
}
