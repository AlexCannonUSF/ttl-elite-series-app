package com.ttl.tabletennis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RunAnnotationRequest(
        @Size(max = 32) String targetType,
        @Size(max = 160) String targetId,
        @NotBlank @Size(max = 2000) String text,
        List<@Size(max = 80) String> tags,
        @Size(max = 80) String author) { }
