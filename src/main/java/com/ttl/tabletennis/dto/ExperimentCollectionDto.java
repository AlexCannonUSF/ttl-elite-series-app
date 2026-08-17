package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ExperimentCollectionDto(Long id, String name, String description, String hypothesis,
                                      String status, String createdBy, LocalDateTime createdAt,
                                      LocalDateTime updatedAt, List<RunLink> runs) {
    public record RunLink(Long id, Long runId, String role, String note, LocalDateTime linkedAt) { }
}
