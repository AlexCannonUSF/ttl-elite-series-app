package com.ttl.tabletennis.dto;

public record OpsIngestPartitionDto(String streamKey,
                                    String family,
                                    String status,
                                    long streamLength,
                                    long consumerGroups,
                                    long pendingCount,
                                    Long oldestPendingAgeSeconds,
                                    long redeliveryCount,
                                    Long lag,
                                    String lastGeneratedId,
                                    String detail) {
}
