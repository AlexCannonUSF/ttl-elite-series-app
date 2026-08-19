package com.ttl.tabletennis.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ModelCallApprovalRequest(
        @NotNull Long winnerPlayerId,
        @Size(max = 80) String score,
        @Size(max = 80) String reviewer,
        @Size(max = 400) String note) {
}
