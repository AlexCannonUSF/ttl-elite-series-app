package com.ttl.tabletennis.request;

import jakarta.validation.constraints.NotNull;

public record MergePlayersRequest(@NotNull Long sourcePlayerId,
                                  @NotNull Long targetPlayerId) {
}
