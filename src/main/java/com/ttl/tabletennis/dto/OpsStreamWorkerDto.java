package com.ttl.tabletennis.dto;

public record OpsStreamWorkerDto(String component,
                                 String workerType,
                                 String rolloutState,
                                 boolean enabled,
                                 String status,
                                 String detail) {
}
