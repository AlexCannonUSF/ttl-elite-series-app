package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "stream_worker_config", indexes = {
        @Index(name = "idx_stream_worker_config_state", columnList = "last_state"),
        @Index(name = "idx_stream_worker_config_updated", columnList = "updated_at_utc")
})
public class StreamWorkerConfig {

    @Id
    @Column(name = "match_id", nullable = false, length = 120)
    private String matchId;

    @Lob
    @Column(name = "stream_url", nullable = false, columnDefinition = "LONGTEXT")
    private String streamUrl;

    @Column(name = "platform", nullable = false, length = 24)
    private String platform;

    @Column(name = "roi_template_id", nullable = false, length = 64)
    private String roiTemplateId;

    @Column(name = "started_at_utc")
    private LocalDateTime startedAtUtc;

    @Column(name = "stopped_at_utc")
    private LocalDateTime stoppedAtUtc;

    @Column(name = "last_state", length = 32)
    private String lastState;

    @Lob
    @Column(name = "last_error", columnDefinition = "LONGTEXT")
    private String lastError;

    @Column(name = "updated_at_utc", nullable = false)
    private LocalDateTime updatedAtUtc;

    @PrePersist
    @PreUpdate
    void prePersistOrUpdate() {
        if (matchId != null) {
            matchId = matchId.trim();
        }
        if (streamUrl == null || streamUrl.isBlank()) {
            streamUrl = "unknown";
        }
        if (platform == null || platform.isBlank()) {
            platform = "UNKNOWN";
        }
        if (roiTemplateId == null || roiTemplateId.isBlank()) {
            roiTemplateId = "unknown";
        }
        if (lastState == null || lastState.isBlank()) {
            lastState = "UNKNOWN";
        }
        updatedAtUtc = LocalDateTime.now(ZoneOffset.UTC);
    }

    public String getMatchId() {
        return matchId;
    }

    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public void setStreamUrl(String streamUrl) {
        this.streamUrl = streamUrl;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getRoiTemplateId() {
        return roiTemplateId;
    }

    public void setRoiTemplateId(String roiTemplateId) {
        this.roiTemplateId = roiTemplateId;
    }

    public LocalDateTime getStartedAtUtc() {
        return startedAtUtc;
    }

    public void setStartedAtUtc(LocalDateTime startedAtUtc) {
        this.startedAtUtc = startedAtUtc;
    }

    public LocalDateTime getStoppedAtUtc() {
        return stoppedAtUtc;
    }

    public void setStoppedAtUtc(LocalDateTime stoppedAtUtc) {
        this.stoppedAtUtc = stoppedAtUtc;
    }

    public String getLastState() {
        return lastState;
    }

    public void setLastState(String lastState) {
        this.lastState = lastState;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getUpdatedAtUtc() {
        return updatedAtUtc;
    }
}
