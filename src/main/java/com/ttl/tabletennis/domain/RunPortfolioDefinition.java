package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Frozen policy/benchmark portfolio configuration for one run. */
@Entity
@Table(name = "run_portfolio_definition", uniqueConstraints = @UniqueConstraint(name = "uk_run_portfolio_session_key", columnNames = {"session_id", "portfolio_key"}))
public class RunPortfolioDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "portfolio_key", nullable = false, length = 64) private String portfolioKey;
    @Column(name = "display_name", nullable = false, length = 100) private String displayName;
    @Column(name = "portfolio_type", nullable = false, length = 32) private String portfolioType;
    @Column(name = "model_lane_key", length = 64) private String modelLaneKey;
    @Column(name = "policy_version", length = 100) private String policyVersion;
    @Column(name = "policy_json", length = 4000) private String policyJson;
    @Column(name = "enabled", nullable = false) private boolean enabled;
    @Column(name = "primary_portfolio", nullable = false) private boolean primaryPortfolio;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; } public void setSessionId(Long value) { sessionId = value; }
    public String getPortfolioKey() { return portfolioKey; } public void setPortfolioKey(String value) { portfolioKey = value; }
    public String getDisplayName() { return displayName; } public void setDisplayName(String value) { displayName = value; }
    public String getPortfolioType() { return portfolioType; } public void setPortfolioType(String value) { portfolioType = value; }
    public String getModelLaneKey() { return modelLaneKey; } public void setModelLaneKey(String value) { modelLaneKey = value; }
    public String getPolicyVersion() { return policyVersion; } public void setPolicyVersion(String value) { policyVersion = value; }
    public String getPolicyJson() { return policyJson; } public void setPolicyJson(String value) { policyJson = value; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public boolean isPrimaryPortfolio() { return primaryPortfolio; } public void setPrimaryPortfolio(boolean value) { primaryPortfolio = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
