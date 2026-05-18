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
@Table(name = "odds_snapshot", indexes = {
        @Index(name = "idx_odds_snapshot_event_time", columnList = "tracked_event_id, observed_at"),
        @Index(name = "idx_odds_snapshot_match_time", columnList = "match_key, observed_at"),
        @Index(name = "idx_odds_snapshot_source_observed", columnList = "source_id, observed_at")
})
public class OddsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracked_event_id", nullable = false, length = 64)
    private String trackedEventId;

    @Column(name = "booker_event_id", length = 128)
    private String bookerEventId;

    @Column(name = "match_key", length = 128)
    private String matchKey;

    @Column(name = "side", nullable = false, length = 4)
    private String side;

    @Column(name = "price_decimal", nullable = false)
    private double priceDecimal;

    @Column(name = "implied_prob", nullable = false)
    private double impliedProb;

    @Column(name = "market_state", nullable = false, length = 24)
    private String marketState;

    @Column(name = "source_id", nullable = false, length = 16)
    private String sourceId;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "raw_payload_ref", length = 128)
    private String rawPayloadRef;

    @PrePersist
    void prePersist() {
        if (observedAt == null) {
            observedAt = LocalDateTime.now();
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationContext.currentOrCreate();
        }
    }

    public Long getId() {
        return id;
    }

    public String getTrackedEventId() {
        return trackedEventId;
    }

    public void setTrackedEventId(String trackedEventId) {
        this.trackedEventId = trackedEventId;
    }

    public String getBookerEventId() {
        return bookerEventId;
    }

    public void setBookerEventId(String bookerEventId) {
        this.bookerEventId = bookerEventId;
    }

    public String getMatchKey() {
        return matchKey;
    }

    public void setMatchKey(String matchKey) {
        this.matchKey = matchKey;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public double getPriceDecimal() {
        return priceDecimal;
    }

    public void setPriceDecimal(double priceDecimal) {
        this.priceDecimal = priceDecimal;
    }

    public double getImpliedProb() {
        return impliedProb;
    }

    public void setImpliedProb(double impliedProb) {
        this.impliedProb = impliedProb;
    }

    public String getMarketState() {
        return marketState;
    }

    public void setMarketState(String marketState) {
        this.marketState = marketState;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(LocalDateTime observedAt) {
        this.observedAt = observedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getRawPayloadRef() {
        return rawPayloadRef;
    }

    public void setRawPayloadRef(String rawPayloadRef) {
        this.rawPayloadRef = rawPayloadRef;
    }
}
