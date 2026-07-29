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
@Table(name = "paper_trade_decision_sample", indexes = {
        @Index(name = "idx_paper_decision_session_created", columnList = "session_id, created_at"),
        @Index(name = "idx_paper_decision_status_created", columnList = "decision_status, created_at"),
        @Index(name = "idx_paper_decision_reason_created", columnList = "decision_reason, created_at"),
        @Index(name = "idx_paper_decision_trigger_created", columnList = "top_trigger, created_at")
})
public class PaperTradeDecisionSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "source", length = 96)
    private String source;

    @Column(name = "strategy", nullable = false, length = 24)
    private String strategy;

    @Column(name = "model_version", nullable = false, length = 80)
    private String modelVersion;

    @Column(name = "event_key", length = 320)
    private String eventKey;

    @Column(name = "dedupe_key", length = 420)
    private String dedupeKey;

    @Column(name = "event_name", length = 220)
    private String eventName;

    @Column(name = "competition_name", length = 180)
    private String competitionName;

    @Column(name = "live_flag", nullable = false)
    private boolean live;

    @Column(name = "player1_id")
    private Long player1Id;

    @Column(name = "player1_name", length = 180)
    private String player1Name;

    @Column(name = "player2_id")
    private Long player2Id;

    @Column(name = "player2_name", length = 180)
    private String player2Name;

    @Column(name = "side_player_id")
    private Long sidePlayerId;

    @Column(name = "side_name", length = 180)
    private String sideName;

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

    @Column(name = "recommended", nullable = false)
    private boolean recommended;

    @Column(name = "fallback_pick", nullable = false)
    private boolean fallbackPick;

    @Column(name = "suggested_edge", nullable = false)
    private double suggestedEdge;

    @Column(name = "model_probability")
    private Double modelProbability;

    @Column(name = "implied_probability")
    private Double impliedProbability;

    @Column(name = "selection_score")
    private Double selectionScore;

    @Column(name = "signal_quality")
    private Double signalQuality;

    @Column(name = "confidence_width")
    private Double confidenceWidth;

    @Column(name = "american_odds")
    private Integer americanOdds;

    @Column(name = "proposed_stake")
    private Double proposedStake;

    @Column(name = "capped_stake")
    private Double cappedStake;

    @Column(name = "decision_status", nullable = false, length = 24)
    private String decisionStatus;

    @Column(name = "decision_reason", nullable = false, length = 120)
    private String decisionReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (strategy == null || strategy.isBlank()) {
            strategy = "CONSERVATIVE";
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            modelVersion = "ENSEMBLE";
        }
        if (decisionStatus == null || decisionStatus.isBlank()) {
            decisionStatus = "SKIPPED";
        }
        if (decisionReason == null || decisionReason.isBlank()) {
            decisionReason = "UNKNOWN";
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationContext.currentOrCreate();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getCompetitionName() {
        return competitionName;
    }

    public void setCompetitionName(String competitionName) {
        this.competitionName = competitionName;
    }

    public boolean isLive() {
        return live;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public Long getPlayer1Id() {
        return player1Id;
    }

    public void setPlayer1Id(Long player1Id) {
        this.player1Id = player1Id;
    }

    public String getPlayer1Name() {
        return player1Name;
    }

    public void setPlayer1Name(String player1Name) {
        this.player1Name = player1Name;
    }

    public Long getPlayer2Id() {
        return player2Id;
    }

    public void setPlayer2Id(Long player2Id) {
        this.player2Id = player2Id;
    }

    public String getPlayer2Name() {
        return player2Name;
    }

    public void setPlayer2Name(String player2Name) {
        this.player2Name = player2Name;
    }

    public Long getSidePlayerId() {
        return sidePlayerId;
    }

    public void setSidePlayerId(Long sidePlayerId) {
        this.sidePlayerId = sidePlayerId;
    }

    public String getSideName() {
        return sideName;
    }

    public void setSideName(String sideName) {
        this.sideName = sideName;
    }

    public String getTopTrigger() {
        return topTrigger;
    }

    public void setTopTrigger(String topTrigger) {
        this.topTrigger = topTrigger;
    }

    public String getFeatureContributions() {
        return featureContributions;
    }

    public void setFeatureContributions(String featureContributions) {
        this.featureContributions = featureContributions;
    }

    public Double getOverallReliability() {
        return overallReliability;
    }

    public void setOverallReliability(Double overallReliability) {
        this.overallReliability = overallReliability;
    }

    public Double getRatingAgreement() {
        return ratingAgreement;
    }

    public void setRatingAgreement(Double ratingAgreement) {
        this.ratingAgreement = ratingAgreement;
    }

    public Double getTriggerReliability() {
        return triggerReliability;
    }

    public void setTriggerReliability(Double triggerReliability) {
        this.triggerReliability = triggerReliability;
    }

    public Double getBaselineStability() {
        return baselineStability;
    }

    public void setBaselineStability(Double baselineStability) {
        this.baselineStability = baselineStability;
    }

    public boolean isRecommended() {
        return recommended;
    }

    public void setRecommended(boolean recommended) {
        this.recommended = recommended;
    }

    public boolean isFallbackPick() {
        return fallbackPick;
    }

    public void setFallbackPick(boolean fallbackPick) {
        this.fallbackPick = fallbackPick;
    }

    public double getSuggestedEdge() {
        return suggestedEdge;
    }

    public void setSuggestedEdge(double suggestedEdge) {
        this.suggestedEdge = suggestedEdge;
    }

    public Double getModelProbability() {
        return modelProbability;
    }

    public void setModelProbability(Double modelProbability) {
        this.modelProbability = modelProbability;
    }

    public Double getImpliedProbability() {
        return impliedProbability;
    }

    public void setImpliedProbability(Double impliedProbability) {
        this.impliedProbability = impliedProbability;
    }

    public Double getSelectionScore() {
        return selectionScore;
    }

    public void setSelectionScore(Double selectionScore) {
        this.selectionScore = selectionScore;
    }

    public Double getSignalQuality() {
        return signalQuality;
    }

    public void setSignalQuality(Double signalQuality) {
        this.signalQuality = signalQuality;
    }

    public Double getConfidenceWidth() {
        return confidenceWidth;
    }

    public void setConfidenceWidth(Double confidenceWidth) {
        this.confidenceWidth = confidenceWidth;
    }

    public Integer getAmericanOdds() {
        return americanOdds;
    }

    public void setAmericanOdds(Integer americanOdds) {
        this.americanOdds = americanOdds;
    }

    public Double getProposedStake() {
        return proposedStake;
    }

    public void setProposedStake(Double proposedStake) {
        this.proposedStake = proposedStake;
    }

    public Double getCappedStake() {
        return cappedStake;
    }

    public void setCappedStake(Double cappedStake) {
        this.cappedStake = cappedStake;
    }

    public String getDecisionStatus() {
        return decisionStatus;
    }

    public void setDecisionStatus(String decisionStatus) {
        this.decisionStatus = decisionStatus;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
