package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "stream_route", indexes = {
        @Index(name = "idx_stream_route_event_table", columnList = "event_code, table_number", unique = true),
        @Index(name = "idx_stream_route_platform", columnList = "platform"),
        @Index(name = "idx_stream_route_updated", columnList = "updated_at_utc")
})
public class StreamRouteRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "route_id")
    private Long routeId;

    @Column(name = "event_code", nullable = false, length = 32)
    private String eventCode;

    @Column(name = "table_number", nullable = false, length = 16)
    private String tableNumber;

    @Column(name = "platform", nullable = false, length = 24)
    private String platform;

    @Lob
    @Column(name = "channel_or_base", nullable = false, columnDefinition = "LONGTEXT")
    private String channelOrBase;

    @Column(name = "roi_template_id", nullable = false, length = 64)
    private String roiTemplateId;

    @Column(name = "updated_at_utc", nullable = false)
    private LocalDateTime updatedAtUtc;

    @PrePersist
    @PreUpdate
    void prePersistOrUpdate() {
        if (eventCode != null) {
            eventCode = eventCode.trim();
        }
        if (tableNumber == null || tableNumber.isBlank()) {
            tableNumber = "*";
        } else {
            tableNumber = tableNumber.trim();
        }
        if (platform == null || platform.isBlank()) {
            platform = "UNKNOWN";
        }
        if (channelOrBase == null || channelOrBase.isBlank()) {
            channelOrBase = "unknown";
        }
        if (roiTemplateId == null || roiTemplateId.isBlank()) {
            roiTemplateId = "unknown";
        }
        updatedAtUtc = LocalDateTime.now(ZoneOffset.UTC);
    }

    public Long getRouteId() {
        return routeId;
    }

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public String getTableNumber() {
        return tableNumber;
    }

    public void setTableNumber(String tableNumber) {
        this.tableNumber = tableNumber;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getChannelOrBase() {
        return channelOrBase;
    }

    public void setChannelOrBase(String channelOrBase) {
        this.channelOrBase = channelOrBase;
    }

    public String getRoiTemplateId() {
        return roiTemplateId;
    }

    public void setRoiTemplateId(String roiTemplateId) {
        this.roiTemplateId = roiTemplateId;
    }

    public LocalDateTime getUpdatedAtUtc() {
        return updatedAtUtc;
    }
}
