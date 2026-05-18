package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@IdClass(StreamWorkerHealthMinuteId.class)
@Table(name = "stream_worker_health_1m", indexes = {
        @Index(name = "idx_stream_worker_health_bucket", columnList = "minute_bucket_utc"),
        @Index(name = "idx_stream_worker_health_match_bucket", columnList = "match_id, minute_bucket_utc")
})
public class StreamWorkerHealthMinute {

    @Id
    @Column(name = "match_id", nullable = false, length = 120)
    private String matchId;

    @Id
    @Column(name = "minute_bucket_utc", nullable = false)
    private LocalDateTime minuteBucketUtc;

    @Column(name = "frames_ingested", nullable = false)
    private int framesIngested;

    @Column(name = "frames_emitted", nullable = false)
    private int framesEmitted;

    @Column(name = "p50_confidence", precision = 4, scale = 3)
    private BigDecimal p50Confidence;

    @Column(name = "p95_latency_ms")
    private Integer p95LatencyMs;

    @Column(name = "vlm_calls", nullable = false)
    private int vlmCalls;

    @Lob
    @Column(name = "state_seen_json", nullable = false, columnDefinition = "LONGTEXT")
    private String stateSeenJson;

    @PrePersist
    void prePersist() {
        if (matchId != null) {
            matchId = matchId.trim();
        }
        if (minuteBucketUtc == null) {
            minuteBucketUtc = LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        }
        if (stateSeenJson == null || stateSeenJson.isBlank()) {
            stateSeenJson = "{}";
        }
    }

    public String getMatchId() {
        return matchId;
    }

    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    public LocalDateTime getMinuteBucketUtc() {
        return minuteBucketUtc;
    }

    public void setMinuteBucketUtc(LocalDateTime minuteBucketUtc) {
        this.minuteBucketUtc = minuteBucketUtc;
    }

    public int getFramesIngested() {
        return framesIngested;
    }

    public void setFramesIngested(int framesIngested) {
        this.framesIngested = framesIngested;
    }

    public int getFramesEmitted() {
        return framesEmitted;
    }

    public void setFramesEmitted(int framesEmitted) {
        this.framesEmitted = framesEmitted;
    }

    public BigDecimal getP50Confidence() {
        return p50Confidence;
    }

    public void setP50Confidence(BigDecimal p50Confidence) {
        this.p50Confidence = p50Confidence;
    }

    public Integer getP95LatencyMs() {
        return p95LatencyMs;
    }

    public void setP95LatencyMs(Integer p95LatencyMs) {
        this.p95LatencyMs = p95LatencyMs;
    }

    public int getVlmCalls() {
        return vlmCalls;
    }

    public void setVlmCalls(int vlmCalls) {
        this.vlmCalls = vlmCalls;
    }

    public String getStateSeenJson() {
        return stateSeenJson;
    }

    public void setStateSeenJson(String stateSeenJson) {
        this.stateSeenJson = stateSeenJson;
    }
}
