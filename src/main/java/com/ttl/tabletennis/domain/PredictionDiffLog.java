package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "prediction_diff_log", indexes = {
        @Index(name = "idx_prediction_diff_log_date", columnList = "as_of_date"),
        @Index(name = "idx_prediction_diff_log_status", columnList = "shadow_status, computed_at_utc"),
        @Index(name = "idx_prediction_diff_log_pred", columnList = "prediction_id", unique = true)
})
public class PredictionDiffLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prediction_id", nullable = false, length = 64)
    private String predictionId;

    @Column(name = "player1_id", nullable = false)
    private Long player1Id;

    @Column(name = "player2_id", nullable = false)
    private Long player2Id;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "v2_model_family", length = 48)
    private String v2ModelFamily;

    @Column(name = "v2_model_version", length = 48)
    private String v2ModelVersion;

    @Column(name = "v2_p1_probability", nullable = false, precision = 7, scale = 6)
    private BigDecimal v2P1Probability;

    @Column(name = "v3_model_version", length = 48)
    private String v3ModelVersion;

    @Column(name = "v3_calibrator_version", length = 64)
    private String v3CalibratorVersion;

    @Column(name = "v3_conformal_version", length = 64)
    private String v3ConformalVersion;

    @Column(name = "v3_uncertainty_label", length = 32)
    private String v3UncertaintyLabel;

    @Column(name = "v3_p1_probability", precision = 7, scale = 6)
    private BigDecimal v3P1Probability;

    @Column(name = "abs_diff", precision = 7, scale = 6)
    private BigDecimal absDiff;

    @Column(name = "v3_variant_b_model_version", length = 48)
    private String v3VariantBModelVersion;

    @Column(name = "v3_variant_b_p1_probability", precision = 7, scale = 6)
    private BigDecimal v3VariantBP1Probability;

    @Column(name = "variant_ab_abs_diff", precision = 7, scale = 6)
    private BigDecimal variantAbAbsDiff;

    @Column(name = "shadow_status", nullable = false, length = 32)
    private String shadowStatus;

    @Column(name = "error_reason", length = 255)
    private String errorReason;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "computed_at_utc", nullable = false)
    private LocalDateTime computedAtUtc;

    public Long getId() { return id; }

    public String getPredictionId() { return predictionId; }
    public void setPredictionId(String predictionId) { this.predictionId = predictionId; }

    public Long getPlayer1Id() { return player1Id; }
    public void setPlayer1Id(Long player1Id) { this.player1Id = player1Id; }

    public Long getPlayer2Id() { return player2Id; }
    public void setPlayer2Id(Long player2Id) { this.player2Id = player2Id; }

    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }

    public String getV2ModelFamily() { return v2ModelFamily; }
    public void setV2ModelFamily(String v2ModelFamily) { this.v2ModelFamily = v2ModelFamily; }

    public String getV2ModelVersion() { return v2ModelVersion; }
    public void setV2ModelVersion(String v2ModelVersion) { this.v2ModelVersion = v2ModelVersion; }

    public BigDecimal getV2P1Probability() { return v2P1Probability; }
    public void setV2P1Probability(BigDecimal v2P1Probability) { this.v2P1Probability = v2P1Probability; }

    public String getV3ModelVersion() { return v3ModelVersion; }
    public void setV3ModelVersion(String v3ModelVersion) { this.v3ModelVersion = v3ModelVersion; }

    public String getV3CalibratorVersion() { return v3CalibratorVersion; }
    public void setV3CalibratorVersion(String v3CalibratorVersion) { this.v3CalibratorVersion = v3CalibratorVersion; }

    public String getV3ConformalVersion() { return v3ConformalVersion; }
    public void setV3ConformalVersion(String v3ConformalVersion) { this.v3ConformalVersion = v3ConformalVersion; }

    public String getV3UncertaintyLabel() { return v3UncertaintyLabel; }
    public void setV3UncertaintyLabel(String v3UncertaintyLabel) { this.v3UncertaintyLabel = v3UncertaintyLabel; }

    public BigDecimal getV3P1Probability() { return v3P1Probability; }
    public void setV3P1Probability(BigDecimal v3P1Probability) { this.v3P1Probability = v3P1Probability; }

    public BigDecimal getAbsDiff() { return absDiff; }
    public void setAbsDiff(BigDecimal absDiff) { this.absDiff = absDiff; }

    public String getV3VariantBModelVersion() { return v3VariantBModelVersion; }
    public void setV3VariantBModelVersion(String v3VariantBModelVersion) { this.v3VariantBModelVersion = v3VariantBModelVersion; }

    public BigDecimal getV3VariantBP1Probability() { return v3VariantBP1Probability; }
    public void setV3VariantBP1Probability(BigDecimal v3VariantBP1Probability) { this.v3VariantBP1Probability = v3VariantBP1Probability; }

    public BigDecimal getVariantAbAbsDiff() { return variantAbAbsDiff; }
    public void setVariantAbAbsDiff(BigDecimal variantAbAbsDiff) { this.variantAbAbsDiff = variantAbAbsDiff; }

    public String getShadowStatus() { return shadowStatus; }
    public void setShadowStatus(String shadowStatus) { this.shadowStatus = shadowStatus; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public LocalDateTime getComputedAtUtc() { return computedAtUtc; }
    public void setComputedAtUtc(LocalDateTime computedAtUtc) { this.computedAtUtc = computedAtUtc; }
}
