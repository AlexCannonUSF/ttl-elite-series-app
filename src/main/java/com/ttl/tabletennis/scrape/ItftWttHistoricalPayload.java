package com.ttl.tabletennis.scrape;

import org.springframework.util.StringUtils;

import java.time.LocalDate;

public record ItftWttHistoricalPayload(String sourceKey,
                                       String playerName,
                                       String country,
                                       Integer rank,
                                       Double points,
                                       LocalDate asOfDate,
                                       String sourceUrl,
                                       String payloadJson) {

    public ItftWttHistoricalPayload {
        sourceKey = safeTrim(sourceKey);
        playerName = requireText(playerName, "playerName");
        country = safeTrim(country);
        sourceUrl = requireText(sourceUrl, "sourceUrl");
        payloadJson = requireText(payloadJson, "payloadJson");
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
