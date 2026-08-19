package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.scrape.SourceId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "feed_health_sample", indexes = {
        @Index(name = "idx_feed_health_sample_source_observed", columnList = "source_id, observed_at"),
        @Index(name = "idx_feed_health_sample_observed", columnList = "observed_at")
})
public class FeedHealthSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_id", nullable = false, length = 16)
    private SourceId sourceId;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "rolling_success_rate_5m")
    private Double rollingSuccessRate5m;

    @Column(name = "rolling_p50_latency_ms")
    private Double rollingP50LatencyMs;

    @Column(name = "rolling_p95_latency_ms")
    private Double rollingP95LatencyMs;

    @Column(name = "in_flight")
    private Integer inFlight;

    @Column(name = "backoff_state", length = 16)
    private String backoffState;

    @Column(name = "last_error", length = 256)
    private String lastError;

    @PrePersist
    void prePersist() {
        if (observedAt == null) {
            observedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getId() {
        return id;
    }

    public SourceId getSourceId() {
        return sourceId;
    }

    public void setSourceId(SourceId sourceId) {
        this.sourceId = sourceId;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(LocalDateTime observedAt) {
        this.observedAt = observedAt;
    }

    public Double getRollingSuccessRate5m() {
        return rollingSuccessRate5m;
    }

    public void setRollingSuccessRate5m(Double rollingSuccessRate5m) {
        this.rollingSuccessRate5m = rollingSuccessRate5m;
    }

    public Double getRollingP50LatencyMs() {
        return rollingP50LatencyMs;
    }

    public void setRollingP50LatencyMs(Double rollingP50LatencyMs) {
        this.rollingP50LatencyMs = rollingP50LatencyMs;
    }

    public Double getRollingP95LatencyMs() {
        return rollingP95LatencyMs;
    }

    public void setRollingP95LatencyMs(Double rollingP95LatencyMs) {
        this.rollingP95LatencyMs = rollingP95LatencyMs;
    }

    public Integer getInFlight() {
        return inFlight;
    }

    public void setInFlight(Integer inFlight) {
        this.inFlight = inFlight;
    }

    public String getBackoffState() {
        return backoffState;
    }

    public void setBackoffState(String backoffState) {
        this.backoffState = backoffState;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
