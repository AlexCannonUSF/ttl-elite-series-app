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
@Table(name = "settlement_audit", indexes = {
        @Index(name = "idx_settlement_audit_bet_decided", columnList = "bet_id, decided_at"),
        @Index(name = "idx_settlement_audit_event_decided", columnList = "tracked_event_id, decided_at"),
        @Index(name = "idx_settlement_audit_decision", columnList = "decision, decided_at")
})
public class SettlementAuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bet_id", nullable = false)
    private Long betId;

    @Column(name = "tracked_event_id", length = 128)
    private String trackedEventId;

    @Column(name = "decision", nullable = false, length = 24)
    private String decision;

    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "evidence_id")
    private Long evidenceId;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (decidedAt == null) {
            decidedAt = LocalDateTime.now();
        }
        if (decision == null || decision.isBlank()) {
            decision = "UNKNOWN";
        }
        if (reason == null || reason.isBlank()) {
            reason = "UNKNOWN";
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

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Long getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(Long evidenceId) {
        this.evidenceId = evidenceId;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
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
