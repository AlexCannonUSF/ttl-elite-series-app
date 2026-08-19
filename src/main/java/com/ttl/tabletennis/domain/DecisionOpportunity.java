package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/** One statistical opportunity shared by every model lane and portfolio. */
@Entity
@Table(name = "decision_opportunity",
        uniqueConstraints = @UniqueConstraint(name = "uk_decision_opportunity_session_event", columnNames = {"session_id", "event_key"}),
        indexes = {
                @Index(name = "idx_decision_opportunity_session_time", columnList = "session_id, first_observed_at"),
                @Index(name = "idx_decision_opportunity_external", columnList = "external_event_id")
        })
public class DecisionOpportunity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "event_key", nullable = false, length = 320) private String eventKey;
    @Column(name = "external_event_id", length = 160) private String externalEventId;
    @Column(name = "source_feed_event_id", length = 160) private String sourceFeedEventId;
    @Column(name = "event_name", length = 220) private String eventName;
    @Column(name = "competition_name", length = 180) private String competitionName;
    @Column(name = "player1_id") private Long player1Id;
    @Column(name = "player2_id") private Long player2Id;
    @Column(name = "start_time_iso", length = 80) private String startTimeIso;
    @Column(name = "capture_type", nullable = false, length = 24) private String captureType;
    @Column(name = "first_observed_at", nullable = false) private LocalDateTime firstObservedAt;
    @Column(name = "frozen_at") private LocalDateTime frozenAt;
    @Column(name = "match_id_high_watermark") private Long matchIdHighWatermark;

    @PrePersist void prePersist() { if (firstObservedAt == null) firstObservedAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; } public void setSessionId(Long value) { sessionId = value; }
    public String getEventKey() { return eventKey; } public void setEventKey(String value) { eventKey = value; }
    public String getExternalEventId() { return externalEventId; } public void setExternalEventId(String value) { externalEventId = value; }
    public String getSourceFeedEventId() { return sourceFeedEventId; } public void setSourceFeedEventId(String value) { sourceFeedEventId = value; }
    public String getEventName() { return eventName; } public void setEventName(String value) { eventName = value; }
    public String getCompetitionName() { return competitionName; } public void setCompetitionName(String value) { competitionName = value; }
    public Long getPlayer1Id() { return player1Id; } public void setPlayer1Id(Long value) { player1Id = value; }
    public Long getPlayer2Id() { return player2Id; } public void setPlayer2Id(Long value) { player2Id = value; }
    public String getStartTimeIso() { return startTimeIso; } public void setStartTimeIso(String value) { startTimeIso = value; }
    public String getCaptureType() { return captureType; } public void setCaptureType(String value) { captureType = value; }
    public LocalDateTime getFirstObservedAt() { return firstObservedAt; } public void setFirstObservedAt(LocalDateTime value) { firstObservedAt = value; }
    public LocalDateTime getFrozenAt() { return frozenAt; } public void setFrozenAt(LocalDateTime value) { frozenAt = value; }
    public Long getMatchIdHighWatermark() { return matchIdHighWatermark; } public void setMatchIdHighWatermark(Long value) { matchIdHighWatermark = value; }
}
