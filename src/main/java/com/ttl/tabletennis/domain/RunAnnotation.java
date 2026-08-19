package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Append-only operator research note attached to a run or child object. */
@Entity
@Table(name = "run_annotation", indexes = @Index(name = "idx_run_annotation_session_time", columnList = "session_id, created_at"))
public class RunAnnotation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "target_type", nullable = false, length = 32) private String targetType;
    @Column(name = "target_id", length = 160) private String targetId;
    @Column(name = "annotation_text", nullable = false, length = 2000) private String annotationText;
    @Column(name = "tags", length = 600) private String tags;
    @Column(name = "author", nullable = false, length = 80) private String author;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); if (author == null || author.isBlank()) author = "OPERATOR"; }
    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; } public void setSessionId(Long value) { sessionId = value; }
    public String getTargetType() { return targetType; } public void setTargetType(String value) { targetType = value; }
    public String getTargetId() { return targetId; } public void setTargetId(String value) { targetId = value; }
    public String getAnnotationText() { return annotationText; } public void setAnnotationText(String value) { annotationText = value; }
    public String getTags() { return tags; } public void setTags(String value) { tags = value; }
    public String getAuthor() { return author; } public void setAuthor(String value) { author = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
