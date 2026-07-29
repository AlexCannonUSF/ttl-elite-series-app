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
     * The {@link com.ttl.tabletennis.prediction.staking.StakingClvWatcher}
     * gauge falls back to the PnL/stake proxy when either is null.
     */
    @Column(name = "closing_decimal_odds")
    private Double closingDecimalOdds;

    @Column(name = "closing_observed_at")
    private LocalDateTime closingObservedAt;

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
}
