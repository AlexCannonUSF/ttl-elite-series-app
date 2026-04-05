package com.ttl.tabletennis.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ModelRegistryEntryDto(Long id,
                                    String modelVersion,
                                    String modelFamily,
                                    LocalDate trainingFrom,
                                    LocalDate trainingTo,
                                    LocalDate validationFrom,
                                    LocalDate validationTo,
                                    Double accuracy,
                                    Double logLoss,
                                    Double brierScore,
                                    String calibrationMethod,
                                    Double regularizationLambda,
                                    Integer folds,
                                    boolean active,
                                    String notes,
                                    LocalDateTime createdAt) {
}
