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
@Table(name = "paper_trade_learning_sample", indexes = {
        @Index(name = "idx_paper_learning_settled", columnList = "settled_at"),
        @Index(name = "idx_paper_learning_status_settled", columnList = "status, settled_at"),
        @Index(name = "idx_paper_learning_trigger_settled", columnList = "top_trigger, settled_at"),
        @Index(name = "idx_paper_learning_session", columnList = "session_id")
})
public class PaperTradeLearningSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bet_id", nullable = false, unique = true)
    private Long betId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "strategy", nullable = false, length = 24)
    private String strategy;

    @Column(name = "model_version", nullable = false, length = 80)
    private String modelVersion;

    @Column(name = "top_trigger", length = 180)
    private String topTrigger;

    @Column(name = "live_at_placement", nullable = false)
    private boolean liveAtPlacement;

    @Column(name = "model_probability", nullable = false)
    private double modelProbability;

    @Column(name = "implied_probability", nullable = false)
    private double impliedProbability;

    @Column(name = "edge", nullable = false)
    private double edge;

    @Column(name = "stake", nullable = false)
    private double stake;

    @Column(name = "profit_loss", nullable = false)
    private double profitLoss;

    @Column(name = "confidence_width", nullable = false)
    private double confidenceWidth;

    @Column(name = "last_observed_phase", length = 64)
    private String lastObservedPhase;

    @Column(name = "placement_phase", length = 64)
    private String placementPhase;

    /**
     * Match/placement clock used for recency weighting. Settlement time is
     * deliberately not used because a late official result must not turn an
     * old prediction into a brand-new observation.
     */
    @Column(name = "event_occurred_at")
    private LocalDateTime eventOccurredAt;

    @Column(name = "settlement_source", length = 48)
    private String settlementSource;

    @Column(name = "settlement_reason", length = 120)
    private String settlementReason;

    @Column(name = "settlement_confidence", nullable = false, columnDefinition = "double precision default 0 not null")
    private double settlementConfidence;

    @Column(name = "calibration_eligible", nullable = false, columnDefinition = "boolean default false not null")
    private boolean calibrationEligible;

    /**
     * Canonical Phase 4 gate. A row may be retained for settlement telemetry
     * while this flag prevents it from affecting any learning consumer.
     */
    @Column(name = "learning_eligible", nullable = false, columnDefinition = "boolean default false not null")
    private boolean learningEligible;

    @Column(name = "learning_exclusion_reason", length = 64)
    private String learningExclusionReason;

    @Column(name = "price_regime", length = 24)
    private String priceRegime;

    @Column(name = "side_orientation", length = 4)
    private String sideOrientation;

    @Column(name = "feature_contributions", length = 2400)
    private String featureContributions;

    @Column(name = "placed_at")
    private LocalDateTime placedAt;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /**
     * Closing-line capture — Phase 06 / finish-checklist §5.
     *
     * <p>{@code closingDecimalOdds} is the market price for the bet's
     * side at the moment the market closed (or was suspended) for this
     * tracked event. {@code closingObservedAt} is the timestamp of the
     * snapshot we picked. Both are nullable because (a) we backfilled
     * the historical samples after settlement, so older rows may not
     * have a closing snapshot in {@code odds_snapshot}, and (b) the
     * source feed may not have observed a CLOSED state for some events.
     * Missing closing prices remain explicitly uncovered. They must never be
     * replaced with PnL because profitability is not closing-line value.
     */
    @Column(name = "closing_decimal_odds")
    private Double closingDecimalOdds;

    @Column(name = "closing_observed_at")
    private LocalDateTime closingObservedAt;

    @Column(name = "closing_source", length = 16)
    private String closingSource;

    @Column(name = "closing_market_state", length = 24)
    private String closingMarketState;

    @PrePersist
    void prePersist() {
        if (status == null || status.isBlank()) {
            status = "UNKNOWN";
        }
        if (source == null || source.isBlank()) {
            source = "UNKNOWN";
        }
        if (strategy == null || strategy.isBlank()) {
            strategy = "CONSERVATIVE";
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            modelVersion = "ENSEMBLE";
        }
        if (settledAt == null) {
            settledAt = LocalDateTime.now();
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

    public Long getBetId() {
        return betId;
    }

    public void setBetId(Long betId) {
        this.betId = betId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getTopTrigger() {
        return topTrigger;
    }

    public void setTopTrigger(String topTrigger) {
        this.topTrigger = topTrigger;
    }

    public boolean isLiveAtPlacement() {
        return liveAtPlacement;
    }

    public void setLiveAtPlacement(boolean liveAtPlacement) {
        this.liveAtPlacement = liveAtPlacement;
    }

    public double getModelProbability() {
        return modelProbability;
    }

    public void setModelProbability(double modelProbability) {
        this.modelProbability = modelProbability;
    }

    public double getImpliedProbability() {
        return impliedProbability;
    }

    public void setImpliedProbability(double impliedProbability) {
        this.impliedProbability = impliedProbability;
    }

    public double getEdge() {
        return edge;
    }

    public void setEdge(double edge) {
        this.edge = edge;
    }

    public double getStake() {
        return stake;
    }

    public void setStake(double stake) {
        this.stake = stake;
    }

    public double getProfitLoss() {
        return profitLoss;
    }

    public void setProfitLoss(double profitLoss) {
        this.profitLoss = profitLoss;
    }

    public double getConfidenceWidth() {
        return confidenceWidth;
    }

    public void setConfidenceWidth(double confidenceWidth) {
        this.confidenceWidth = confidenceWidth;
    }

    public String getLastObservedPhase() {
        return lastObservedPhase;
    }

    public void setLastObservedPhase(String lastObservedPhase) {
        this.lastObservedPhase = lastObservedPhase;
    }

    public String getPlacementPhase() {
        return placementPhase;
    }

    public void setPlacementPhase(String placementPhase) {
        this.placementPhase = placementPhase;
    }

    public LocalDateTime getEventOccurredAt() {
        return eventOccurredAt;
    }

    public void setEventOccurredAt(LocalDateTime eventOccurredAt) {
        this.eventOccurredAt = eventOccurredAt;
    }

    public String getSettlementSource() {
        return settlementSource;
    }

    public void setSettlementSource(String settlementSource) {
        this.settlementSource = settlementSource;
    }

    public String getSettlementReason() {
        return settlementReason;
    }

    public void setSettlementReason(String settlementReason) {
        this.settlementReason = settlementReason;
    }

    public double getSettlementConfidence() {
        return settlementConfidence;
    }

    public void setSettlementConfidence(double settlementConfidence) {
        this.settlementConfidence = settlementConfidence;
    }

    public boolean isCalibrationEligible() {
        return calibrationEligible;
    }

    public void setCalibrationEligible(boolean calibrationEligible) {
        this.calibrationEligible = calibrationEligible;
        this.learningEligible = calibrationEligible;
    }

    public boolean isLearningEligible() {
        return learningEligible;
    }

    public void setLearningEligible(boolean learningEligible) {
        this.learningEligible = learningEligible;
        // Keep the legacy column synchronized during the compatibility window.
        this.calibrationEligible = learningEligible;
    }

    public String getLearningExclusionReason() {
        return learningExclusionReason;
    }

    public void setLearningExclusionReason(String learningExclusionReason) {
        this.learningExclusionReason = learningExclusionReason;
    }

    public String getPriceRegime() {
        return priceRegime;
    }

    public void setPriceRegime(String priceRegime) {
        this.priceRegime = priceRegime;
    }

    public String getSideOrientation() {
        return sideOrientation;
    }

    public void setSideOrientation(String sideOrientation) {
        this.sideOrientation = sideOrientation;
    }

    public String getFeatureContributions() {
        return featureContributions;
    }

    public void setFeatureContributions(String featureContributions) {
        this.featureContributions = featureContributions;
    }

    public LocalDateTime getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(LocalDateTime placedAt) {
        this.placedAt = placedAt;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
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

    public Double getClosingDecimalOdds() {
        return closingDecimalOdds;
    }

    public void setClosingDecimalOdds(Double closingDecimalOdds) {
        this.closingDecimalOdds = closingDecimalOdds;
    }

    public LocalDateTime getClosingObservedAt() {
        return closingObservedAt;
    }

    public void setClosingObservedAt(LocalDateTime closingObservedAt) {
        this.closingObservedAt = closingObservedAt;
    }

    public String getClosingSource() {
        return closingSource;
    }

    public void setClosingSource(String closingSource) {
        this.closingSource = closingSource;
    }

    public String getClosingMarketState() {
        return closingMarketState;
    }

    public void setClosingMarketState(String closingMarketState) {
        this.closingMarketState = closingMarketState;
    }
}
