package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Immutable identity for one synchronized model lane within a run. */
@Entity
@Table(name = "run_model_lane_definition", uniqueConstraints = @UniqueConstraint(name = "uk_run_model_lane_session_key", columnNames = {"session_id", "lane_key"}))
public class RunModelLaneDefinition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "lane_key", nullable = false, length = 64) private String laneKey;
    @Column(name = "display_name", nullable = false, length = 100) private String displayName;
    @Column(name = "lane_role", nullable = false, length = 24) private String laneRole;
    @Column(name = "ordinal_position", nullable = false) private int ordinalPosition;
    @Column(name = "model_family", length = 64) private String modelFamily;
    @Column(name = "model_version", length = 100) private String modelVersion;
    @Column(name = "artifact_checksum", length = 64) private String artifactChecksum;
    @Column(name = "feature_schema_checksum", length = 64) private String featureSchemaChecksum;
    @Column(name = "calibration_id", length = 100) private String calibrationId;
    @Column(name = "enabled", nullable = false) private boolean enabled;
    @Column(name = "primary_lane", nullable = false) private boolean primaryLane;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; } public void setSessionId(Long value) { sessionId = value; }
    public String getLaneKey() { return laneKey; } public void setLaneKey(String value) { laneKey = value; }
    public String getDisplayName() { return displayName; } public void setDisplayName(String value) { displayName = value; }
    public String getLaneRole() { return laneRole; } public void setLaneRole(String value) { laneRole = value; }
    public int getOrdinalPosition() { return ordinalPosition; } public void setOrdinalPosition(int value) { ordinalPosition = value; }
    public String getModelFamily() { return modelFamily; } public void setModelFamily(String value) { modelFamily = value; }
    public String getModelVersion() { return modelVersion; } public void setModelVersion(String value) { modelVersion = value; }
    public String getArtifactChecksum() { return artifactChecksum; } public void setArtifactChecksum(String value) { artifactChecksum = value; }
    public String getFeatureSchemaChecksum() { return featureSchemaChecksum; } public void setFeatureSchemaChecksum(String value) { featureSchemaChecksum = value; }
    public String getCalibrationId() { return calibrationId; } public void setCalibrationId(String value) { calibrationId = value; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public boolean isPrimaryLane() { return primaryLane; } public void setPrimaryLane(boolean value) { primaryLane = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
