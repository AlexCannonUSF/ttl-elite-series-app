package com.ttl.tabletennis.dto;

public record OpsIngestPartitionDto(String streamKey,
                                    String family,
                                    String status,
                                    long streamLength,
                                    long consumerGroups,
                                    long pendingCount,
                                    Long lag,
                                    String lastGeneratedId,
                                    String detail) {
}
