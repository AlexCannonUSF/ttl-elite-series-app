package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * One frozen model winner call per paper session and event.
 *
 * <p>This is intentionally separate from {@link PaperTradeBet}: the model's
 * most likely winner and its best value bet are not necessarily the same side.
 * Prematch calls follow the market until the last observed prematch snapshot;
 * a match first seen live keeps its first live snapshot so later score state
 * cannot leak into the evaluation.
 */
@Entity
@Table(name = "paper_trade_model_call",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_paper_model_call_session_event",
                columnNames = {"session_id", "event_key"}),
        indexes = {
                @Index(name = "idx_paper_model_call_session_captured", columnList = "session_id, captured_at"),
                @Index(name = "idx_paper_model_call_external", columnList = "external_event_id"),
                @Index(name = "idx_paper_model_call_feed_event", columnList = "source_feed_event_id")
        })
public class PaperTradeModelCall {

    public static final String CAPTURE_PREMATCH_CLOSE = "PREMATCH_CLOSE";
    public static final String CAPTURE_LIVE_FIRST = "LIVE_FIRST_SEEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "event_key", nullable = false, length = 320)
    private String eventKey;

    @Column(name = "event_name", length = 220)
    private String eventName;

    @Column(name = "competition_name", length = 180)
    private String competitionName;

    @Column(name = "source", length = 96)
    private String source;

    @Column(name = "strategy", length = 24)
    private String strategy;

    @Column(name = "model_version", length = 80)
    private String modelVersion;

    @Column(name = "artifact_checksum", length = 64)
    private String artifactChecksum;

    @Column(name = "feature_schema_checksum", length = 64)
    private String featureSchemaChecksum;

    @Column(name = "calibration_id", length = 100)
    private String calibrationId;

    @Column(name = "policy_id", length = 100)
    private String policyId;

    @Column(name = "code_revision", length = 80)
    private String codeRevision;

    @Column(name = "capture_type", nullable = false, length = 24)
    private String captureType;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "start_time_iso", length = 80)
    private String startTimeIso;

    @Column(name = "external_event_id", length = 160)
    private String externalEventId;

    @Column(name = "source_feed_event_id", length = 160)
    private String sourceFeedEventId;

    @Column(name = "match_id_high_watermark")
    private Long matchIdHighWatermark;

    @Column(name = "player1_id")
    private Long player1Id;

    @Column(name = "player1_name", length = 180)
    private String player1Name;

    @Column(name = "player2_id")
    private Long player2Id;

    @Column(name = "player2_name", length = 180)
    private String player2Name;

    @Column(name = "predicted_winner_player_id")
    private Long predictedWinnerPlayerId;

    @Column(name = "predicted_winner_name", length = 180)
    private String predictedWinnerName;

    @Column(name = "model_probability")
    private Double modelProbability;

    @Column(name = "raw_model_probability")
    private Double rawModelProbability;

    @Column(name = "model_fair_american_odds")
    private Integer modelFairAmericanOdds;

    @Column(name = "hard_rock_american_odds")
    private Integer hardRockAmericanOdds;

    @Column(name = "opponent_hard_rock_american_odds")
    private Integer opponentHardRockAmericanOdds;

    @Column(name = "hard_rock_no_vig_probability")
    private Double hardRockNoVigProbability;

    @Column(name = "recommended_at_capture", nullable = false)
    private boolean recommendedAtCapture;

    @Column(name = "has_paper_pick", nullable = false)
    private boolean hasPaperPick;

    @Column(name = "decision_status", length = 24)
    private String decisionStatus;

    @Column(name = "decision_reason", length = 160)
    private String decisionReason;

    /** Frozen predictor telemetry captured with the model call. */
    @Column(name = "top_trigger", length = 180)
    private String topTrigger;

    @Column(name = "feature_contributions", length = 2400)
    private String featureContributions;

    @Column(name = "overall_reliability")
    private Double overallReliability;

    @Column(name = "rating_agreement")
    private Double ratingAgreement;

    @Column(name = "trigger_reliability")
    private Double triggerReliability;

    @Column(name = "baseline_stability")
    private Double baselineStability;

    @Column(name = "suggested_edge")
    private Double suggestedEdge;

    @Column(name = "selection_score", nullable = false)
    private Double selectionScore;

    @Column(name = "signal_quality", nullable = false)
    private Double signalQuality;

    @Column(name = "confidence_width")
    private Double confidenceWidth;

    @Column(name = "confidence_low")
    private Double confidenceLow;

    @Column(name = "confidence_high")
    private Double confidenceHigh;

    @Column(name = "model_market_no_vig_gap")
    private Double modelMarketNoVigGap;

    @Column(name = "gate_results", length = 1200)
    private String gateResults;

    @PrePersist
    void prePersist() {
        if (captureType == null || captureType.isBlank()) {
            captureType = CAPTURE_PREMATCH_CLOSE;
        }
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
        }
        if (selectionScore == null || !Double.isFinite(selectionScore)) {
            selectionScore = 0.0;
        }
        if (signalQuality == null || !Double.isFinite(signalQuality)) {
            signalQuality = 0.0;
        }
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }
    public String getCompetitionName() { return competitionName; }
    public void setCompetitionName(String competitionName) { this.competitionName = competitionName; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }
    public String getArtifactChecksum() { return artifactChecksum; }
    public void setArtifactChecksum(String artifactChecksum) { this.artifactChecksum = artifactChecksum; }
    public String getFeatureSchemaChecksum() { return featureSchemaChecksum; }
    public void setFeatureSchemaChecksum(String featureSchemaChecksum) { this.featureSchemaChecksum = featureSchemaChecksum; }
    public String getCalibrationId() { return calibrationId; }
    public void setCalibrationId(String calibrationId) { this.calibrationId = calibrationId; }
    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }
    public String getCodeRevision() { return codeRevision; }
    public void setCodeRevision(String codeRevision) { this.codeRevision = codeRevision; }
    public String getCaptureType() { return captureType; }
    public void setCaptureType(String captureType) { this.captureType = captureType; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime capturedAt) { this.capturedAt = capturedAt; }
    public String getStartTimeIso() { return startTimeIso; }
    public void setStartTimeIso(String startTimeIso) { this.startTimeIso = startTimeIso; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    public String getSourceFeedEventId() { return sourceFeedEventId; }
    public void setSourceFeedEventId(String sourceFeedEventId) { this.sourceFeedEventId = sourceFeedEventId; }
    public Long getMatchIdHighWatermark() { return matchIdHighWatermark; }
    public void setMatchIdHighWatermark(Long matchIdHighWatermark) { this.matchIdHighWatermark = matchIdHighWatermark; }
    public Long getPlayer1Id() { return player1Id; }
    public void setPlayer1Id(Long player1Id) { this.player1Id = player1Id; }
    public String getPlayer1Name() { return player1Name; }
    public void setPlayer1Name(String player1Name) { this.player1Name = player1Name; }
    public Long getPlayer2Id() { return player2Id; }
    public void setPlayer2Id(Long player2Id) { this.player2Id = player2Id; }
    public String getPlayer2Name() { return player2Name; }
    public void setPlayer2Name(String player2Name) { this.player2Name = player2Name; }
    public Long getPredictedWinnerPlayerId() { return predictedWinnerPlayerId; }
    public void setPredictedWinnerPlayerId(Long predictedWinnerPlayerId) { this.predictedWinnerPlayerId = predictedWinnerPlayerId; }
    public String getPredictedWinnerName() { return predictedWinnerName; }
    public void setPredictedWinnerName(String predictedWinnerName) { this.predictedWinnerName = predictedWinnerName; }
    public Double getModelProbability() { return modelProbability; }
    public void setModelProbability(Double modelProbability) { this.modelProbability = modelProbability; }
    public Double getRawModelProbability() { return rawModelProbability; }
    public void setRawModelProbability(Double rawModelProbability) { this.rawModelProbability = rawModelProbability; }
    public Integer getModelFairAmericanOdds() { return modelFairAmericanOdds; }
    public void setModelFairAmericanOdds(Integer modelFairAmericanOdds) { this.modelFairAmericanOdds = modelFairAmericanOdds; }
    public Integer getHardRockAmericanOdds() { return hardRockAmericanOdds; }
    public void setHardRockAmericanOdds(Integer hardRockAmericanOdds) { this.hardRockAmericanOdds = hardRockAmericanOdds; }
    public Integer getOpponentHardRockAmericanOdds() { return opponentHardRockAmericanOdds; }
    public void setOpponentHardRockAmericanOdds(Integer opponentHardRockAmericanOdds) { this.opponentHardRockAmericanOdds = opponentHardRockAmericanOdds; }
    public Double getHardRockNoVigProbability() { return hardRockNoVigProbability; }
    public void setHardRockNoVigProbability(Double hardRockNoVigProbability) { this.hardRockNoVigProbability = hardRockNoVigProbability; }
    public boolean isRecommendedAtCapture() { return recommendedAtCapture; }
    public void setRecommendedAtCapture(boolean recommendedAtCapture) { this.recommendedAtCapture = recommendedAtCapture; }
    public boolean isHasPaperPick() { return hasPaperPick; }
    public void setHasPaperPick(boolean hasPaperPick) { this.hasPaperPick = hasPaperPick; }
    public String getDecisionStatus() { return decisionStatus; }
    public void setDecisionStatus(String decisionStatus) { this.decisionStatus = decisionStatus; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public String getTopTrigger() { return topTrigger; }
    public void setTopTrigger(String topTrigger) { this.topTrigger = topTrigger; }
    public String getFeatureContributions() { return featureContributions; }
    public void setFeatureContributions(String featureContributions) { this.featureContributions = featureContributions; }
    public Double getOverallReliability() { return overallReliability; }
    public void setOverallReliability(Double overallReliability) { this.overallReliability = overallReliability; }
    public Double getRatingAgreement() { return ratingAgreement; }
    public void setRatingAgreement(Double ratingAgreement) { this.ratingAgreement = ratingAgreement; }
    public Double getTriggerReliability() { return triggerReliability; }
    public void setTriggerReliability(Double triggerReliability) { this.triggerReliability = triggerReliability; }
    public Double getBaselineStability() { return baselineStability; }
    public void setBaselineStability(Double baselineStability) { this.baselineStability = baselineStability; }
    public Double getSuggestedEdge() { return suggestedEdge; }
    public void setSuggestedEdge(Double suggestedEdge) { this.suggestedEdge = suggestedEdge; }
    public Double getSelectionScore() { return selectionScore; }
    public void setSelectionScore(Double selectionScore) { this.selectionScore = selectionScore; }
    public Double getSignalQuality() { return signalQuality; }
    public void setSignalQuality(Double signalQuality) { this.signalQuality = signalQuality; }
    public Double getConfidenceWidth() { return confidenceWidth; }
    public void setConfidenceWidth(Double confidenceWidth) { this.confidenceWidth = confidenceWidth; }
    public Double getConfidenceLow() { return confidenceLow; }
    public void setConfidenceLow(Double confidenceLow) { this.confidenceLow = confidenceLow; }
    public Double getConfidenceHigh() { return confidenceHigh; }
    public void setConfidenceHigh(Double confidenceHigh) { this.confidenceHigh = confidenceHigh; }
    public Double getModelMarketNoVigGap() { return modelMarketNoVigGap; }
    public void setModelMarketNoVigGap(Double modelMarketNoVigGap) { this.modelMarketNoVigGap = modelMarketNoVigGap; }
    public String getGateResults() { return gateResults; }
    public void setGateResults(String gateResults) { this.gateResults = gateResults; }
}
