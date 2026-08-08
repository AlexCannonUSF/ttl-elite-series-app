package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Append-only viewer grading for an unresolved all-match model call.
 *
 * <p>This is deliberately separate from canonical settlement and model
 * training truth. A viewer can follow the model immediately without allowing
 * an unverified result to settle a paper bet or enter calibration data.
 */
@Entity
@Table(name = "model_call_viewer_review", indexes = {
        @Index(name = "idx_model_call_review_call_time", columnList = "call_id, created_at"),
        @Index(name = "idx_model_call_review_session_time", columnList = "session_id, created_at")
})
public class ModelCallViewerReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id", nullable = false)
    private Long callId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "event_key", nullable = false, length = 320)
    private String eventKey;

    @Column(name = "winner_player_id", nullable = false)
    private Long winnerPlayerId;

    @Column(name = "winner_name", nullable = false, length = 180)
    private String winnerName;

    @Column(name = "score", length = 80)
    private String score;

    @Column(name = "reviewer", nullable = false, length = 80)
    private String reviewer;

    @Column(name = "note", length = 400)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (reviewer == null || reviewer.isBlank()) reviewer = "USER";
    }

    public Long getId() { return id; }
    public Long getCallId() { return callId; }
    public void setCallId(Long callId) { this.callId = callId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public Long getWinnerPlayerId() { return winnerPlayerId; }
    public void setWinnerPlayerId(Long winnerPlayerId) { this.winnerPlayerId = winnerPlayerId; }
    public String getWinnerName() { return winnerName; }
    public void setWinnerName(String winnerName) { this.winnerName = winnerName; }
    public String getScore() { return score; }
    public void setScore(String score) { this.score = score; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String reviewer) { this.reviewer = reviewer; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
