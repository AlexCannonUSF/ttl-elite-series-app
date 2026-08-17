package com.ttl.tabletennis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExperimentCollectionRequest(
        @NotBlank @Size(max = 140) String name,
        @Size(max = 2000) String description,
        @Size(max = 2000) String hypothesis,
        @Size(max = 80) String createdBy) { }
