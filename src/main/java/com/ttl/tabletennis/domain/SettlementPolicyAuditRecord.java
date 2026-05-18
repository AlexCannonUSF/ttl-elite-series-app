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
@Table(name = "settlement_policy_audit", indexes = {
        @Index(name = "idx_settlement_policy_audit_policy_time", columnList = "policy_name, reloaded_at"),
        @Index(name = "idx_settlement_policy_audit_status_time", columnList = "status, reloaded_at")
})
public class SettlementPolicyAuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_name", nullable = false, length = 96)
    private String policyName;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "triggered_by", nullable = false, length = 96)
    private String triggeredBy;

    @Column(name = "source_path", nullable = false, length = 512)
    private String sourcePath;

    @Column(name = "checksum_before", length = 96)
    private String checksumBefore;

    @Column(name = "checksum_after", length = 96)
    private String checksumAfter;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "reloaded_at", nullable = false)
    private LocalDateTime reloadedAt;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (policyName == null || policyName.isBlank()) {
            policyName = "bet-settlement";
        }
        if (status == null || status.isBlank()) {
            status = "UNKNOWN";
        }
        if (triggeredBy == null || triggeredBy.isBlank()) {
            triggeredBy = "unknown";
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            sourcePath = "unknown";
        }
        if (reloadedAt == null) {
            reloadedAt = LocalDateTime.now();
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

    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getChecksumBefore() {
        return checksumBefore;
    }

    public void setChecksumBefore(String checksumBefore) {
        this.checksumBefore = checksumBefore;
    }

    public String getChecksumAfter() {
        return checksumAfter;
    }

    public void setChecksumAfter(String checksumAfter) {
        this.checksumAfter = checksumAfter;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getReloadedAt() {
        return reloadedAt;
    }

    public void setReloadedAt(LocalDateTime reloadedAt) {
        this.reloadedAt = reloadedAt;
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
