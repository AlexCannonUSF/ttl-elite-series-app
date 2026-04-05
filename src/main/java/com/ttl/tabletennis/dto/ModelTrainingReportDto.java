package com.ttl.tabletennis.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ModelTrainingReportDto(String jobId,
                                     LocalDate trainingFrom,
                                     LocalDate trainingTo,
                                     int samples,
                                     int features,
                                     String championFamily,
                                     String championVersion,
                                     LocalDateTime trainedAt,
                                     List<CandidateMetricDto> candidates,
                                     List<CalibrationBinDto> calibrationCurve,
                                     List<RegimeMetricDto> validationRegimes,
                                     List<RegimeMetricDto> operationalRegimes) {

    public record CandidateMetricDto(String family,
                                     String version,
                                     double accuracy,
                                     double logLoss,
                                     double brierScore,
                                     String calibrationMethod,
                                     boolean active) {
    }

    public record CalibrationBinDto(double lowerBound,
                                    double upperBound,
                                    int count,
                                    double meanPredicted,
                                    double observedRate) {
    }

    public record RegimeMetricDto(String label,
                                  int count,
                                  double meanPredicted,
                                  double observedRate,
                                  double accuracy,
                                  double brierScore,
                                  Double roiPct) {
    }
}
