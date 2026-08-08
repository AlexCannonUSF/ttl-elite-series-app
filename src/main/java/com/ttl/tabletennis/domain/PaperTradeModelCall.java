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

    @PrePersist
    void prePersist() {
        if (captureType == null || captureType.isBlank()) {
            captureType = CAPTURE_PREMATCH_CLOSE;
        }
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
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
}
