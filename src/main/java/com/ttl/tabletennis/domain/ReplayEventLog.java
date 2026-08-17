package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/** One deterministic event in a completed replay. */
@Entity
@Table(name = "replay_event_log", uniqueConstraints = {
        @UniqueConstraint(name = "uk_replay_event_sequence", columnNames = {"replay_id", "sequence_number"}),
        @UniqueConstraint(name = "uk_replay_event_call", columnNames = {"replay_id", "source_call_id"})
})
public class ReplayEventLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "replay_id", nullable = false) private Long replayId;
    @Column(name = "sequence_number", nullable = false) private int sequenceNumber;
    @Column(name = "source_run_id", nullable = false) private Long sourceRunId;
    @Column(name = "source_call_id", nullable = false) private Long sourceCallId;
    @Column(name = "event_time", nullable = false) private LocalDateTime eventTime;
    @Column(name = "event_type", nullable = false, length = 32) private String eventType;
    @Column(name = "event_name", length = 220) private String eventName;
    @Column(name = "capture_type", length = 24) private String captureType;
    @Column(name = "predicted_winner_name", length = 180) private String predictedWinnerName;
    @Column(name = "model_probability") private Double modelProbability;
    @Column(name = "hard_rock_american_odds") private Integer hardRockAmericanOdds;
    @Column(name = "decision_status", length = 32) private String decisionStatus;
    @Column(name = "pipeline_stage", length = 40) private String pipelineStage;
    @Column(name = "effective_outcome", length = 24) private String effectiveOutcome;
    @Column(name = "outcome_source", length = 32) private String outcomeSource;
    @Column(name = "flat_stake_profit") private Double flatStakeProfit;

    public Long getId() { return id; }
    public Long getReplayId() { return replayId; }
    public void setReplayId(Long value) { replayId = value; }
    public int getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(int value) { sequenceNumber = value; }
    public Long getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(Long value) { sourceRunId = value; }
    public Long getSourceCallId() { return sourceCallId; }
    public void setSourceCallId(Long value) { sourceCallId = value; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime value) { eventTime = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { eventType = value; }
    public String getEventName() { return eventName; }
    public void setEventName(String value) { eventName = value; }
    public String getCaptureType() { return captureType; }
    public void setCaptureType(String value) { captureType = value; }
    public String getPredictedWinnerName() { return predictedWinnerName; }
    public void setPredictedWinnerName(String value) { predictedWinnerName = value; }
    public Double getModelProbability() { return modelProbability; }
    public void setModelProbability(Double value) { modelProbability = value; }
    public Integer getHardRockAmericanOdds() { return hardRockAmericanOdds; }
    public void setHardRockAmericanOdds(Integer value) { hardRockAmericanOdds = value; }
    public String getDecisionStatus() { return decisionStatus; }
    public void setDecisionStatus(String value) { decisionStatus = value; }
    public String getPipelineStage() { return pipelineStage; }
    public void setPipelineStage(String value) { pipelineStage = value; }
    public String getEffectiveOutcome() { return effectiveOutcome; }
    public void setEffectiveOutcome(String value) { effectiveOutcome = value; }
    public String getOutcomeSource() { return outcomeSource; }
    public void setOutcomeSource(String value) { outcomeSource = value; }
    public Double getFlatStakeProfit() { return flatStakeProfit; }
    public void setFlatStakeProfit(Double value) { flatStakeProfit = value; }
}
