package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Membership of a run in an experiment collection. */
@Entity
@Table(name = "experiment_run_link", uniqueConstraints = @UniqueConstraint(name = "uk_experiment_run", columnNames = {"experiment_id", "session_id"}))
public class ExperimentRunLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "experiment_id", nullable = false) private Long experimentId;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "role", nullable = false, length = 32) private String role;
    @Column(name = "note", length = 1000) private String note;
    @Column(name = "linked_at", nullable = false) private LocalDateTime linkedAt;
    @PrePersist void prePersist() { if (linkedAt == null) linkedAt = LocalDateTime.now(); if (role == null) role = "CANDIDATE"; }
    public Long getId() { return id; }
    public Long getExperimentId() { return experimentId; } public void setExperimentId(Long value) { experimentId = value; }
    public Long getSessionId() { return sessionId; } public void setSessionId(Long value) { sessionId = value; }
    public String getRole() { return role; } public void setRole(String value) { role = value; }
    public String getNote() { return note; } public void setNote(String value) { note = value; }
    public LocalDateTime getLinkedAt() { return linkedAt; }
}
