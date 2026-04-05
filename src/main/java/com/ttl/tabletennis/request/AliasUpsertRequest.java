package com.ttl.tabletennis.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AliasUpsertRequest(@NotNull Long playerId,
                                 @NotBlank @Size(max = 180) String aliasName) {
}
