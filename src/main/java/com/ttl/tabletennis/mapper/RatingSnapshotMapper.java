package com.ttl.tabletennis.mapper;

import com.ttl.tabletennis.domain.RatingSnapshot;
import com.ttl.tabletennis.dto.RatingSnapshotDto;

public final class RatingSnapshotMapper {

    private RatingSnapshotMapper() {
    }

    public static RatingSnapshotDto toDto(RatingSnapshot snapshot) {
        if (snapshot == null) return null;
        Double rd = snapshot.getRatingDeviation();
        Double confidenceLow = rd == null ? null : snapshot.getRating() - (2.0 * rd);
        Double confidenceHigh = rd == null ? null : snapshot.getRating() + (2.0 * rd);
        return new RatingSnapshotDto(
                snapshot.getId(),
                snapshot.getPlayer().getId(),
                snapshot.getPlayer().getName(),
                snapshot.getSnapshotDate(),
                snapshot.getRating(),
                rd,
                snapshot.getVolatility(),
                confidenceLow,
                confidenceHigh,
                snapshot.getRatingSystem()
        );
    }
}
