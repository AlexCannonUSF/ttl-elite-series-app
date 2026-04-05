package com.ttl.tabletennis.dto;

import java.time.LocalDate;
import java.util.List;

public record Glicko2TauTuningDto(LocalDate fromDate,
                                  LocalDate toDate,
                                  double bestTau,
                                  List<CandidateScoreDto> candidates) {

    public record CandidateScoreDto(double tau,
                                    double averageLogLoss,
                                    double averageBrierScore,
                                    long predictions) {
    }
}
