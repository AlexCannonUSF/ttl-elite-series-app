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
@Table(name = "contradiction", indexes = {
        @Index(name = "idx_contradiction_bet_observed", columnList = "bet_id, observed_at"),
        @Index(name = "idx_contradiction_evidence", columnList = "evidence_id"),
        @Index(name = "idx_contradiction_resolved", columnList = "resolved, observed_at")
})
public class SettlementContradictionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evidence_id")
    private Long evidenceId;

    @Column(name = "bet_id", nullable = false)
    private Long betId;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "severity", nullable = false)
    private double severity;

    @Column(name = "resolved", nullable = false, columnDefinition = "boolean default false not null")
    private boolean resolved;

    @Lob
    @Column(name = "resolution_note")
    private String resolutionNote;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (observedAt == null) {
            observedAt = LocalDateTime.now();
        }
        if (kind == null || kind.isBlank()) {
            kind = "UNKNOWN";
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

    public Long getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(Long evidenceId) {
        this.evidenceId = evidenceId;
    }

    public Long getBetId() {
        return betId;
    }

    public void setBetId(Long betId) {
        this.betId = betId;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(LocalDateTime observedAt) {
        this.observedAt = observedAt;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public double getSeverity() {
        return severity;
    }

    public void setSeverity(double severity) {
        this.severity = severity;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
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
