package com.ttl.tabletennis.dto;

public record OpsFeedsSummaryDto(long totalSources,
                                 long healthySources,
                                 long degradedSources,
                                 long downSources,
                                 long idleSources,
                                 long totalDlqDepth) {
}
