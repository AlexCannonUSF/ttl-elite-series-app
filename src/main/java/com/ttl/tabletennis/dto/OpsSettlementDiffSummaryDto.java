package com.ttl.tabletennis.dto;

public record OpsSettlementDiffSummaryDto(long totalRows,
                                          long agreeRows,
                                          long disagreementRows,
                                          long contradictionRows,
                                          long outcomeDiffRows) {
}
