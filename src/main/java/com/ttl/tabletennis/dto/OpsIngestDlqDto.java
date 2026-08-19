package com.ttl.tabletennis.dto;

import java.util.List;

public record OpsIngestDlqDto(long totalDepth,
                              List<OpsIngestDlqSourceDto> sources) {
}
