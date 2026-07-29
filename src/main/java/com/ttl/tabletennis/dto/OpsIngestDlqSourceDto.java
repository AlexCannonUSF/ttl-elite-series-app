package com.ttl.tabletennis.dto;

public record OpsIngestDlqSourceDto(String sourceId,
                                    String trustTier,
                                    long depth) {
}
