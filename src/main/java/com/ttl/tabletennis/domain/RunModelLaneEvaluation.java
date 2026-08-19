package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One frozen lane output for one shared opportunity. */
@Entity
@Table(name = "run_model_lane_evaluation", uniqueConstraints = @UniqueConstraint(name = "uk_lane_evaluation_opportunity_lane", columnNames = {"opportunity_id", "lane_definition_id"}), indexes = @Index(name = "idx_lane_evaluation_lane_time", columnList = "lane_definition_id, captured_at"))
public class RunModelLaneEvaluation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "opportunity_id", nullable = false) private Long opportunityId;
    @Column(name = "lane_definition_id", nullable = false) private Long laneDefinitionId;
    @Column(name = "source_model_call_id") private Long sourceModelCallId;
    @Column(name = "captured_at", nullable = false) private LocalDateTime capturedAt;
    @Column(name = "player1_probability") private Double player1Probability;
    @Column(name = "player2_probability") private Double player2Probability;
    @Column(name = "predicted_winner_player_id") private Long predictedWinnerPlayerId;
    @Column(name = "predicted_winner_name", length = 180) private String predictedWinnerName;
    @Column(name = "raw_probability") private Double rawProbability;
    @Column(name = "confidence_low") private Double confidenceLow;
    @Column(name = "confidence_high") private Double confidenceHigh;
    @Column(name = "fair_american_odds") private Integer fairAmericanOdds;
    @Column(name = "selection_score") private Double selectionScore;
    @Column(name = "signal_quality") private Double signalQuality;
    @Column(name = "top_trigger", length = 180) private String topTrigger;
    @Column(name = "feature_contributions", length = 2400) private String featureContributions;
    @PrePersist void prePersist() { if (capturedAt == null) capturedAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getOpportunityId() { return opportunityId; } public void setOpportunityId(Long value) { opportunityId = value; }
    public Long getLaneDefinitionId() { return laneDefinitionId; } public void setLaneDefinitionId(Long value) { laneDefinitionId = value; }
    public Long getSourceModelCallId() { return sourceModelCallId; } public void setSourceModelCallId(Long value) { sourceModelCallId = value; }
    public LocalDateTime getCapturedAt() { return capturedAt; } public void setCapturedAt(LocalDateTime value) { capturedAt = value; }
    public Double getPlayer1Probability() { return player1Probability; } public void setPlayer1Probability(Double value) { player1Probability = value; }
    public Double getPlayer2Probability() { return player2Probability; } public void setPlayer2Probability(Double value) { player2Probability = value; }
    public Long getPredictedWinnerPlayerId() { return predictedWinnerPlayerId; } public void setPredictedWinnerPlayerId(Long value) { predictedWinnerPlayerId = value; }
    public String getPredictedWinnerName() { return predictedWinnerName; } public void setPredictedWinnerName(String value) { predictedWinnerName = value; }
    public Double getRawProbability() { return rawProbability; } public void setRawProbability(Double value) { rawProbability = value; }
    public Double getConfidenceLow() { return confidenceLow; } public void setConfidenceLow(Double value) { confidenceLow = value; }
    public Double getConfidenceHigh() { return confidenceHigh; } public void setConfidenceHigh(Double value) { confidenceHigh = value; }
    public Integer getFairAmericanOdds() { return fairAmericanOdds; } public void setFairAmericanOdds(Integer value) { fairAmericanOdds = value; }
    public Double getSelectionScore() { return selectionScore; } public void setSelectionScore(Double value) { selectionScore = value; }
    public Double getSignalQuality() { return signalQuality; } public void setSignalQuality(Double value) { signalQuality = value; }
    public String getTopTrigger() { return topTrigger; } public void setTopTrigger(String value) { topTrigger = value; }
    public String getFeatureContributions() { return featureContributions; } public void setFeatureContributions(String value) { featureContributions = value; }
}
