package com.ttl.tabletennis.mapper;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.dto.MatchDto;

public final class MatchMapper {

    private MatchMapper() {
    }

    public static MatchDto toDto(Match match) {
        if (match == null) return null;
        return new MatchDto(
                match.getId(),
                match.getExternalId(),
                match.getDate(),
                PlayerMapper.toDto(match.getPlayer1()),
                PlayerMapper.toDto(match.getPlayer2()),
                match.getResult(),
                match.getPlayer1SetsWon(),
                match.getPlayer2SetsWon(),
                match.getWinnerPlayerId(),
                match.isComplete()
        );
    }
}
