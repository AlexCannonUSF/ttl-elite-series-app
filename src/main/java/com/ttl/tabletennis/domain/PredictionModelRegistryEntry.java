package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "prediction_model_registry", indexes = {
        @Index(name = "idx_model_registry_family_active", columnList = "model_family, active"),
        @Index(name = "idx_model_registry_created_at", columnList = "created_at"),
        @Index(name = "idx_model_registry_version", columnList = "model_version", unique = true)
})
public class PredictionModelRegistryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_version", nullable = false, length = 80, unique = true)
    private String modelVersion;

    @Column(name = "model_family", nullable = false, length = 40)
    private String modelFamily;

    @Column(name = "training_from")
    private LocalDate trainingFrom;

    @Column(name = "training_to")
    private LocalDate trainingTo;

    @Column(name = "validation_from")
    private LocalDate validationFrom;

    @Column(name = "validation_to")
    private LocalDate validationTo;

    @Column(name = "accuracy")
    private Double accuracy;

    @Column(name = "log_loss")
    private Double logLoss;

    @Column(name = "brier_score")
    private Double brierScore;

    @Column(name = "calibration_method", length = 32)
    private String calibrationMethod;

    @Column(name = "regularization_lambda")
    private Double regularizationLambda;

    @Column(name = "folds")
    private Integer folds;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "payload_json", length = 16000)
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getModelFamily() {
        return modelFamily;
    }

    public void setModelFamily(String modelFamily) {
        this.modelFamily = modelFamily;
    }

    public LocalDate getTrainingFrom() {
        return trainingFrom;
    }

    public void setTrainingFrom(LocalDate trainingFrom) {
        this.trainingFrom = trainingFrom;
    }

    public LocalDate getTrainingTo() {
        return trainingTo;
    }

    public void setTrainingTo(LocalDate trainingTo) {
        this.trainingTo = trainingTo;
    }

    public LocalDate getValidationFrom() {
        return validationFrom;
    }

    public void setValidationFrom(LocalDate validationFrom) {
        this.validationFrom = validationFrom;
    }

    public LocalDate getValidationTo() {
        return validationTo;
    }

    public void setValidationTo(LocalDate validationTo) {
        this.validationTo = validationTo;
    }

    public Double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(Double accuracy) {
        this.accuracy = accuracy;
    }

    public Double getLogLoss() {
        return logLoss;
    }

    public void setLogLoss(Double logLoss) {
        this.logLoss = logLoss;
    }

    public Double getBrierScore() {
        return brierScore;
    }

    public void setBrierScore(Double brierScore) {
        this.brierScore = brierScore;
    }

    public String getCalibrationMethod() {
        return calibrationMethod;
    }

    public void setCalibrationMethod(String calibrationMethod) {
        this.calibrationMethod = calibrationMethod;
    }

    public Double getRegularizationLambda() {
        return regularizationLambda;
    }

    public void setRegularizationLambda(Double regularizationLambda) {
        this.regularizationLambda = regularizationLambda;
    }

    public Integer getFolds() {
        return folds;
    }

    public void setFolds(Integer folds) {
        this.folds = folds;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
