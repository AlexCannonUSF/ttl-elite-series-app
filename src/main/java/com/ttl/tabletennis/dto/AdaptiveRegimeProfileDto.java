package com.ttl.tabletennis.dto;

public record AdaptiveRegimeProfileDto(String label,
                                       int sampleSize,
                                       double reliability,
                                       double calibrationErrorPct,
                                       double roiPct,
                                       double confidenceScale,
                                       double ciBoost,
                                       boolean live,
                                       String phase,
                                       String sideType) {
}
