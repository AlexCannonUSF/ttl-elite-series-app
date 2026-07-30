package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.CorrelationContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_diff_log", indexes = {
        @Index(name = "idx_settlement_diff_bet_decided", columnList = "bet_id, decided_at"),
        @Index(name = "idx_settlement_diff_decided", columnList = "decided_at"),
        @Index(name = "idx_settlement_diff_kind", columnList = "diff_kind"),
        @Index(name = "uq_settlement_diff_fingerprint", columnList = "diff_fingerprint", unique = true)
})
public class SettlementDiffLog {

    public static final String DIFF_KIND_AGREE = "AGREE";
    public static final String DIFF_KIND_OUTCOME_DIFF = "OUTCOME_DIFF";
    public static final String DIFF_KIND_CONFIDENCE_DIFF = "CONFIDENCE_DIFF";
    public static final String DIFF_KIND_CONTRADICTION = "CONTRADICTION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bet_id", nullable = false)
    private Long betId;

    @Column(name = "old_reason", length = 64)
    private String oldReason;

    @Column(name = "new_reason", length = 64)
    private String newReason;

    @Column(name = "diff_kind", length = 32)
    private String diffKind;

    @Column(name = "old_winner")
    private Long oldWinner;

    @Column(name = "new_winner")
    private Long newWinner;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    @Column(name = "diff_fingerprint", length = 64)
    private String diffFingerprint;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (decidedAt == null) {
            decidedAt = LocalDateTime.now();
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

    public String getOldReason() {
        return oldReason;
    }

    public void setOldReason(String oldReason) {
        this.oldReason = oldReason;
    }

    public String getNewReason() {
        return newReason;
    }

    public void setNewReason(String newReason) {
        this.newReason = newReason;
    }

    public String getDiffKind() {
        return diffKind;
    }

    public void setDiffKind(String diffKind) {
        this.diffKind = diffKind;
    }

    public Long getOldWinner() {
        return oldWinner;
    }

    public void setOldWinner(Long oldWinner) {
        this.oldWinner = oldWinner;
    }

    public Long getNewWinner() {
        return newWinner;
    }

    public void setNewWinner(Long newWinner) {
        this.newWinner = newWinner;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getDiffFingerprint() {
        return diffFingerprint;
    }

    public void setDiffFingerprint(String diffFingerprint) {
        this.diffFingerprint = diffFingerprint;
    }
}
