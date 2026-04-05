package com.ttl.tabletennis.dto;

import java.time.LocalDateTime;

public record OddsRefreshResultDto(String source,
                                   int quotesFetched,
                                   int quotesResolved,
                                   int opportunitiesCreated,
                                   String strategy,
                                   String modelVersion,
                                   LocalDateTime refreshedAt) {
}
