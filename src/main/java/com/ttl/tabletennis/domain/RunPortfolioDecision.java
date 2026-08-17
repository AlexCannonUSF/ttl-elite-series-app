package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Counterfactual or official policy decision for one opportunity. */
@Entity
@Table(name = "run_portfolio_decision", uniqueConstraints = @UniqueConstraint(name = "uk_portfolio_decision_opportunity_portfolio", columnNames = {"opportunity_id", "portfolio_definition_id"}))
public class RunPortfolioDecision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "opportunity_id", nullable = false) private Long opportunityId;
    @Column(name = "portfolio_definition_id", nullable = false) private Long portfolioDefinitionId;
    @Column(name = "lane_evaluation_id") private Long laneEvaluationId;
    @Column(name = "decision_status", nullable = false, length = 32) private String decisionStatus;
    @Column(name = "decision_reason", length = 180) private String decisionReason;
    @Column(name = "selected_player_id") private Long selectedPlayerId;
    @Column(name = "selected_player_name", length = 180) private String selectedPlayerName;
    @Column(name = "model_probability") private Double modelProbability;
    @Column(name = "market_probability") private Double marketProbability;
    @Column(name = "edge_value") private Double edgeValue;
    @Column(name = "american_odds") private Integer americanOdds;
    @Column(name = "virtual_stake") private Double virtualStake;
    @Column(name = "captured_at", nullable = false) private LocalDateTime capturedAt;
    @PrePersist void prePersist() { if (capturedAt == null) capturedAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getOpportunityId() { return opportunityId; } public void setOpportunityId(Long value) { opportunityId = value; }
    public Long getPortfolioDefinitionId() { return portfolioDefinitionId; } public void setPortfolioDefinitionId(Long value) { portfolioDefinitionId = value; }
    public Long getLaneEvaluationId() { return laneEvaluationId; } public void setLaneEvaluationId(Long value) { laneEvaluationId = value; }
    public String getDecisionStatus() { return decisionStatus; } public void setDecisionStatus(String value) { decisionStatus = value; }
    public String getDecisionReason() { return decisionReason; } public void setDecisionReason(String value) { decisionReason = value; }
    public Long getSelectedPlayerId() { return selectedPlayerId; } public void setSelectedPlayerId(Long value) { selectedPlayerId = value; }
    public String getSelectedPlayerName() { return selectedPlayerName; } public void setSelectedPlayerName(String value) { selectedPlayerName = value; }
    public Double getModelProbability() { return modelProbability; } public void setModelProbability(Double value) { modelProbability = value; }
    public Double getMarketProbability() { return marketProbability; } public void setMarketProbability(Double value) { marketProbability = value; }
    public Double getEdgeValue() { return edgeValue; } public void setEdgeValue(Double value) { edgeValue = value; }
    public Integer getAmericanOdds() { return americanOdds; } public void setAmericanOdds(Integer value) { americanOdds = value; }
    public Double getVirtualStake() { return virtualStake; } public void setVirtualStake(Double value) { virtualStake = value; }
    public LocalDateTime getCapturedAt() { return capturedAt; } public void setCapturedAt(LocalDateTime value) { capturedAt = value; }
}
