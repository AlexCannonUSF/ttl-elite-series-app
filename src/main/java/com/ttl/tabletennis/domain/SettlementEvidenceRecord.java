package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.CorrelationContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_evidence", indexes = {
        @Index(name = "idx_settlement_evidence_bet_asof", columnList = "bet_id, bundle_as_of"),
        @Index(name = "idx_settlement_evidence_event_asof", columnList = "tracked_event_id, bundle_as_of")
})
public class SettlementEvidenceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bet_id", nullable = false)
    private Long betId;

    @Column(name = "tracked_event_id", length = 128)
    private String trackedEventId;

    @Column(name = "bundle_as_of", nullable = false)
    private LocalDateTime bundleAsOf;

    @Column(name = "coverage_state", nullable = false, length = 16)
    private String coverageState;

    @Column(name = "ambiguity_score", nullable = false)
    private double ambiguityScore;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (bundleAsOf == null) {
            bundleAsOf = LocalDateTime.now();
        }
        if (coverageState == null || coverageState.isBlank()) {
            coverageState = "DARK";
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            payloadJson = "{}";
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationContext.currentOrCreate();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getBetId() {
        return betId;
    }

    public void setBetId(Long betId) {
        this.betId = betId;
    }

    public String getTrackedEventId() {
        return trackedEventId;
    }

    public void setTrackedEventId(String trackedEventId) {
        this.trackedEventId = trackedEventId;
    }

    public LocalDateTime getBundleAsOf() {
        return bundleAsOf;
    }

    public void setBundleAsOf(LocalDateTime bundleAsOf) {
        this.bundleAsOf = bundleAsOf;
    }

    public String getCoverageState() {
        return coverageState;
    }

    public void setCoverageState(String coverageState) {
        this.coverageState = coverageState;
    }

    public double getAmbiguityScore() {
        return ambiguityScore;
    }

    public void setAmbiguityScore(double ambiguityScore) {
        this.ambiguityScore = ambiguityScore;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
