package com.ttl.tabletennis.dto;

public record OpsFeedsSummaryDto(long totalSources,
                                 long activeSources,
                                 long standbySources,
                                 long disabledSources,
                                 long healthySources,
                                 long degradedSources,
                                 long downSources,
                                 long idleSources,
                                 long totalDlqDepth) {
}
