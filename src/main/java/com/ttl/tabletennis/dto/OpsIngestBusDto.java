package com.ttl.tabletennis.dto;

public record OpsIngestBusDto(String mode,
                              String status,
                              boolean redisAvailable,
                              String activeBus,
                              String streamPrefix,
                              long partitionLagWarning,
                              long partitionLagCritical,
                              String detail) {
}
