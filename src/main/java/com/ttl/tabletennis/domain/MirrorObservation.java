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
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "mirror_observation", indexes = {
        @Index(name = "idx_mirror_observation_event_time", columnList = "tracked_event_id, observed_at"),
        @Index(name = "idx_mirror_observation_source_time", columnList = "source_id, observed_at")
})
public class MirrorObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracked_event_id", nullable = false, length = 64)
    private String trackedEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_id", nullable = false, length = 16)
    private SourceId sourceId;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "phase", length = 16)
    private String phase;

    @Column(name = "games_p1")
    private Integer gamesP1;

    @Column(name = "games_p2")
    private Integer gamesP2;

    @Column(name = "points_p1")
    private Integer pointsP1;

    @Column(name = "points_p2")
    private Integer pointsP2;

    @Column(name = "server", length = 4)
    private String server;

    @Column(name = "completion_signal")
    private Boolean completionSignal;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Column(name = "raw_payload_ref", length = 128)
    private String rawPayloadRef;

    @PrePersist
    void prePersist() {
        if (observedAt == null) {
            observedAt = LocalDateTime.now(ZoneOffset.UTC);
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

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public Integer getGamesP1() {
        return gamesP1;
    }

    public void setGamesP1(Integer gamesP1) {
        this.gamesP1 = gamesP1;
    }

    public Integer getGamesP2() {
        return gamesP2;
    }

    public void setGamesP2(Integer gamesP2) {
        this.gamesP2 = gamesP2;
    }

    public Integer getPointsP1() {
        return pointsP1;
    }

    public void setPointsP1(Integer pointsP1) {
        this.pointsP1 = pointsP1;
    }

    public Integer getPointsP2() {
        return pointsP2;
    }

    public void setPointsP2(Integer pointsP2) {
        this.pointsP2 = pointsP2;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public Boolean getCompletionSignal() {
        return completionSignal;
    }

    public void setCompletionSignal(Boolean completionSignal) {
        this.completionSignal = completionSignal;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getRawPayloadRef() {
        return rawPayloadRef;
    }

    public void setRawPayloadRef(String rawPayloadRef) {
        this.rawPayloadRef = rawPayloadRef;
    }
}
