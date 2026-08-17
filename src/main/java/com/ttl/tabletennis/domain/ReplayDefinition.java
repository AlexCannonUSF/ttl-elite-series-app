package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** Versioned replay recipe. Completed rows are immutable research receipts. */
@Entity
@Table(name = "replay_definition")
public class ReplayDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "parent_replay_id") private Long parentReplayId;
    @Column(name = "label", nullable = false, length = 160) private String label;
    @Column(name = "status", nullable = false, length = 24) private String status;
    @Column(name = "replay_mode", nullable = false, length = 40) private String replayMode;
    @Column(name = "source_run_ids", nullable = false, length = 1000) private String sourceRunIds;
    @Column(name = "window_start") private LocalDateTime windowStart;
    @Column(name = "window_end") private LocalDateTime windowEnd;
    @Column(name = "capture_rule", nullable = false, length = 80) private String captureRule;
    @Column(name = "model_lane_keys", nullable = false, length = 600) private String modelLaneKeys;
    @Column(name = "portfolio_keys", nullable = false, length = 600) private String portfolioKeys;
    @Column(name = "execution_book", nullable = false, length = 32) private String executionBook;
    @Column(name = "initial_bankroll", nullable = false) private double initialBankroll;
    @Column(name = "max_quote_age_seconds", nullable = false) private int maxQuoteAgeSeconds;
    @Column(name = "deterministic_seed", nullable = false) private long deterministicSeed;
    @Column(name = "definition_checksum", nullable = false, length = 64) private String definitionChecksum;
    @Column(name = "leakage_audit_status", nullable = false, length = 40) private String leakageAuditStatus;
    @Column(name = "reproducible", nullable = false) private boolean reproducible;
    @Column(name = "event_count", nullable = false) private int eventCount;
    @Column(name = "resolved_count", nullable = false) private int resolvedCount;
    @Column(name = "priced_resolved_count", nullable = false) private int pricedResolvedCount;
    @Column(name = "correct_count", nullable = false) private int correctCount;
    @Column(name = "flat_stake_pnl", nullable = false) private double flatStakePnl;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "started_at") private LocalDateTime startedAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "DRAFT";
        if (replayMode == null) replayMode = "HISTORICAL_AS_KNOWN";
        if (captureRule == null) captureRule = "FROZEN_ORIGINAL_CALL";
        if (modelLaneKeys == null) modelLaneKeys = "CHAMPION";
        if (portfolioKeys == null) portfolioKeys = "ALL_CALLS";
        if (executionBook == null) executionBook = "HR_MKT";
        if (leakageAuditStatus == null) leakageAuditStatus = "NOT_RUN";
    }

    public Long getId() { return id; }
    public Long getParentReplayId() { return parentReplayId; }
    public void setParentReplayId(Long value) { parentReplayId = value; }
    public String getLabel() { return label; }
    public void setLabel(String value) { label = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReplayMode() { return replayMode; }
    public void setReplayMode(String value) { replayMode = value; }
    public String getSourceRunIds() { return sourceRunIds; }
    public void setSourceRunIds(String value) { sourceRunIds = value; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime value) { windowStart = value; }
    public LocalDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalDateTime value) { windowEnd = value; }
    public String getCaptureRule() { return captureRule; }
    public void setCaptureRule(String value) { captureRule = value; }
    public String getModelLaneKeys() { return modelLaneKeys; }
    public void setModelLaneKeys(String value) { modelLaneKeys = value; }
    public String getPortfolioKeys() { return portfolioKeys; }
    public void setPortfolioKeys(String value) { portfolioKeys = value; }
    public String getExecutionBook() { return executionBook; }
    public void setExecutionBook(String value) { executionBook = value; }
    public double getInitialBankroll() { return initialBankroll; }
    public void setInitialBankroll(double value) { initialBankroll = value; }
    public int getMaxQuoteAgeSeconds() { return maxQuoteAgeSeconds; }
    public void setMaxQuoteAgeSeconds(int value) { maxQuoteAgeSeconds = value; }
    public long getDeterministicSeed() { return deterministicSeed; }
    public void setDeterministicSeed(long value) { deterministicSeed = value; }
    public String getDefinitionChecksum() { return definitionChecksum; }
    public void setDefinitionChecksum(String value) { definitionChecksum = value; }
    public String getLeakageAuditStatus() { return leakageAuditStatus; }
    public void setLeakageAuditStatus(String value) { leakageAuditStatus = value; }
    public boolean isReproducible() { return reproducible; }
    public void setReproducible(boolean value) { reproducible = value; }
    public int getEventCount() { return eventCount; }
    public void setEventCount(int value) { eventCount = value; }
    public int getResolvedCount() { return resolvedCount; }
    public void setResolvedCount(int value) { resolvedCount = value; }
    public int getPricedResolvedCount() { return pricedResolvedCount; }
    public void setPricedResolvedCount(int value) { pricedResolvedCount = value; }
    public int getCorrectCount() { return correctCount; }
    public void setCorrectCount(int value) { correctCount = value; }
    public double getFlatStakePnl() { return flatStakePnl; }
    public void setFlatStakePnl(double value) { flatStakePnl = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime value) { startedAt = value; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime value) { completedAt = value; }
}
