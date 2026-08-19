package com.ttl.tabletennis.dto;

import java.time.Instant;

public record ScoreTruthReviewActionDto(Long id,
                                        Long decisionId,
                                        String action,
                                        String reviewer,
                                        String comment,
                                        Instant reviewedAt) {
}
