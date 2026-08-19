package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Named hypothesis grouping for intentionally selected historical runs. */
@Entity
@Table(name = "experiment_collection")
public class ExperimentCollection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "name", nullable = false, length = 140) private String name;
    @Column(name = "description", length = 2000) private String description;
    @Column(name = "hypothesis", length = 2000) private String hypothesis;
    @Column(name = "status", nullable = false, length = 32) private String status;
    @Column(name = "created_by", nullable = false, length = 80) private String createdBy;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); if (createdAt == null) createdAt = now; if (updatedAt == null) updatedAt = now; if (status == null) status = "OPEN"; if (createdBy == null) createdBy = "OPERATOR"; }
    public Long getId() { return id; }
    public String getName() { return name; } public void setName(String value) { name = value; }
    public String getDescription() { return description; } public void setDescription(String value) { description = value; }
    public String getHypothesis() { return hypothesis; } public void setHypothesis(String value) { hypothesis = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public String getCreatedBy() { return createdBy; } public void setCreatedBy(String value) { createdBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
