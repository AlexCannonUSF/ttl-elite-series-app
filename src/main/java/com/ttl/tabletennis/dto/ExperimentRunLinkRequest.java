package com.ttl.tabletennis.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ExperimentRunLinkRequest(
        @NotNull @Positive Long runId,
        @Size(max = 32) String role,
        @Size(max = 1000) String note) { }
