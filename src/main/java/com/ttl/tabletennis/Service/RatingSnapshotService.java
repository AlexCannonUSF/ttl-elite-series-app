package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.RatingSnapshot;
import com.ttl.tabletennis.dto.RatingSnapshotDto;
import com.ttl.tabletennis.mapper.RatingSnapshotMapper;
import com.ttl.tabletennis.repository.RatingSnapshotRepository;
import com.ttl.tabletennis.request.RatingSnapshotRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class RatingSnapshotService {

    private final RatingSnapshotRepository ratingSnapshotRepository;
    private final PlayerService playerService;

    public RatingSnapshotService(RatingSnapshotRepository ratingSnapshotRepository,
                                 PlayerService playerService) {
        this.ratingSnapshotRepository = ratingSnapshotRepository;
        this.playerService = playerService;
    }

    public List<RatingSnapshotDto> getByPlayer(Long playerId) {
        return ratingSnapshotRepository.findByPlayerIdOrderBySnapshotDateAsc(playerId)
                .stream()
                .map(RatingSnapshotMapper::toDto)
                .toList();
    }

    @Transactional
    public RatingSnapshotDto upsert(RatingSnapshotRequest request) {
        Player player = playerService.getPlayerOrThrow(request.playerId());
        String ratingSystem = (request.ratingSystem() == null || request.ratingSystem().isBlank())
                ? "ELO"
                : request.ratingSystem().trim().toUpperCase();

        RatingSnapshot snapshot = ratingSnapshotRepository
                .findByPlayerIdAndSnapshotDateAndRatingSystem(player.getId(), request.snapshotDate(), ratingSystem)
                .orElseGet(RatingSnapshot::new);

        snapshot.setPlayer(player);
        snapshot.setSnapshotDate(request.snapshotDate());
        snapshot.setRating(request.rating());
        snapshot.setRatingDeviation(request.ratingDeviation());
        snapshot.setVolatility(request.volatility());
        snapshot.setRatingSystem(ratingSystem);

        return RatingSnapshotMapper.toDto(ratingSnapshotRepository.save(snapshot));
    }
}
