package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.CorrelationContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "tracked_match_observation", indexes = {
        @Index(name = "idx_tracked_obs_event_time", columnList = "event_key, observed_at"),
        @Index(name = "idx_tracked_obs_bet_time", columnList = "bet_id, observed_at"),
        @Index(name = "idx_tracked_obs_session_time", columnList = "session_id, observed_at"),
        @Index(name = "idx_tracked_obs_session_event_time", columnList = "session_id, event_key, observed_at, id")
})
public class TrackedMatchObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "bet_id")
    private Long betId;

    @Column(name = "event_key", nullable = false, length = 320)
    private String eventKey;

    @Column(name = "dedupe_key", length = 420)
    private String dedupeKey;

    @Column(name = "external_event_id", length = 96)
    private String externalEventId;

    @Column(name = "source", nullable = false, length = 128)
    private String source;

    @Column(name = "source_kind", nullable = false, length = 48)
    private String sourceKind;

    @Column(name = "source_confidence", nullable = false)
    private double sourceConfidence;

    @Column(name = "displayed", nullable = false, columnDefinition = "boolean default true not null")
    private boolean displayed = true;

    @Column(name = "resulted", nullable = false, columnDefinition = "boolean default false not null")
    private boolean resulted;

    @Column(name = "match_completed", nullable = false, columnDefinition = "boolean default false not null")
    private boolean matchCompleted;

    @Column(name = "source_feed_code", length = 64)
    private String sourceFeedCode;

    @Column(name = "source_feed_event_id", length = 128)
    private String sourceFeedEventId;

    @Column(name = "live", nullable = false)
    private boolean live;

    @Column(name = "tracked_after_close", nullable = false)
    private boolean trackedAfterClose;

    @Column(name = "event_name", length = 220)
    private String eventName;

    @Column(name = "competition_name", length = 180)
    private String competitionName;

    @Column(name = "start_time_iso", length = 64)
    private String startTimeIso;

    @Column(name = "player1_id")
    private Long player1Id;

    @Column(name = "player1_name", length = 180)
    private String player1Name;

    @Column(name = "player2_id")
    private Long player2Id;

    @Column(name = "player2_name", length = 180)
    private String player2Name;

    @Column(name = "live_score", length = 64)
    private String liveScore;

    @Column(name = "match_phase", length = 48)
    private String matchPhase;

    @Column(name = "score_detail", length = 180)
    private String scoreDetail;

    @Column(name = "provisional_winner_player_id")
    private Long provisionalWinnerPlayerId;

    @Column(name = "provisional_outcome_method", length = 48)
    private String provisionalOutcomeMethod;

    @Column(name = "provisional_outcome_confidence")
    private Double provisionalOutcomeConfidence;

    @Column(name = "resolved_winner_player_id")
    private Long resolvedWinnerPlayerId;

    @Column(name = "provisional_correct")
    private Boolean provisionalCorrect;

    @Column(name = "provisional_resolved_at")
    private LocalDateTime provisionalResolvedAt;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (eventKey == null || eventKey.isBlank()) {
            eventKey = "unknown-event";
        }
        if (source == null || source.isBlank()) {
            source = "UNKNOWN";
        }
        if (sourceKind == null || sourceKind.isBlank()) {
            sourceKind = "MARKET_BOARD";
        }
        if (observedAt == null) {
            observedAt = LocalDateTime.now();
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationContext.currentOrCreate();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getBetId() {
        return betId;
    }

    public void setBetId(Long betId) {
        this.betId = betId;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public void setExternalEventId(String externalEventId) {
        this.externalEventId = externalEventId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public void setSourceKind(String sourceKind) {
        this.sourceKind = sourceKind;
    }

    public double getSourceConfidence() {
        return sourceConfidence;
    }

    public void setSourceConfidence(double sourceConfidence) {
        this.sourceConfidence = sourceConfidence;
    }

    public boolean isDisplayed() {
        return displayed;
    }

    public void setDisplayed(boolean displayed) {
        this.displayed = displayed;
    }

    public boolean isResulted() {
        return resulted;
    }

    public void setResulted(boolean resulted) {
        this.resulted = resulted;
    }

    public boolean isMatchCompleted() {
        return matchCompleted;
    }

    public void setMatchCompleted(boolean matchCompleted) {
        this.matchCompleted = matchCompleted;
    }

    public String getSourceFeedCode() {
        return sourceFeedCode;
    }

    public void setSourceFeedCode(String sourceFeedCode) {
        this.sourceFeedCode = sourceFeedCode;
    }

    public String getSourceFeedEventId() {
        return sourceFeedEventId;
    }

    public void setSourceFeedEventId(String sourceFeedEventId) {
        this.sourceFeedEventId = sourceFeedEventId;
    }

    public boolean isLive() {
        return live;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public boolean isTrackedAfterClose() {
        return trackedAfterClose;
    }

    public void setTrackedAfterClose(boolean trackedAfterClose) {
        this.trackedAfterClose = trackedAfterClose;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getCompetitionName() {
        return competitionName;
    }

    public void setCompetitionName(String competitionName) {
        this.competitionName = competitionName;
    }

    public String getStartTimeIso() {
        return startTimeIso;
    }

    public void setStartTimeIso(String startTimeIso) {
        this.startTimeIso = startTimeIso;
    }

    public Long getPlayer1Id() {
        return player1Id;
    }

    public void setPlayer1Id(Long player1Id) {
        this.player1Id = player1Id;
    }

    public String getPlayer1Name() {
        return player1Name;
    }

    public void setPlayer1Name(String player1Name) {
        this.player1Name = player1Name;
    }

    public Long getPlayer2Id() {
        return player2Id;
    }

    public void setPlayer2Id(Long player2Id) {
        this.player2Id = player2Id;
    }

    public String getPlayer2Name() {
        return player2Name;
    }

    public void setPlayer2Name(String player2Name) {
        this.player2Name = player2Name;
    }

    public String getLiveScore() {
        return liveScore;
    }

    public void setLiveScore(String liveScore) {
        this.liveScore = liveScore;
    }

    public String getMatchPhase() {
        return matchPhase;
    }

    public void setMatchPhase(String matchPhase) {
        this.matchPhase = matchPhase;
    }

    public String getScoreDetail() {
        return scoreDetail;
    }

    public void setScoreDetail(String scoreDetail) {
        this.scoreDetail = scoreDetail;
    }

    public Long getProvisionalWinnerPlayerId() {
        return provisionalWinnerPlayerId;
    }

    public void setProvisionalWinnerPlayerId(Long provisionalWinnerPlayerId) {
        this.provisionalWinnerPlayerId = provisionalWinnerPlayerId;
    }

    public String getProvisionalOutcomeMethod() {
        return provisionalOutcomeMethod;
    }

    public void setProvisionalOutcomeMethod(String provisionalOutcomeMethod) {
        this.provisionalOutcomeMethod = provisionalOutcomeMethod;
    }

    public Double getProvisionalOutcomeConfidence() {
        return provisionalOutcomeConfidence;
    }

    public void setProvisionalOutcomeConfidence(Double provisionalOutcomeConfidence) {
        this.provisionalOutcomeConfidence = provisionalOutcomeConfidence;
    }

    public Long getResolvedWinnerPlayerId() {
        return resolvedWinnerPlayerId;
    }

    public void setResolvedWinnerPlayerId(Long resolvedWinnerPlayerId) {
        this.resolvedWinnerPlayerId = resolvedWinnerPlayerId;
    }

    public Boolean getProvisionalCorrect() {
        return provisionalCorrect;
    }

    public void setProvisionalCorrect(Boolean provisionalCorrect) {
        this.provisionalCorrect = provisionalCorrect;
    }

    public LocalDateTime getProvisionalResolvedAt() {
        return provisionalResolvedAt;
    }

    public void setProvisionalResolvedAt(LocalDateTime provisionalResolvedAt) {
        this.provisionalResolvedAt = provisionalResolvedAt;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(LocalDateTime observedAt) {
        this.observedAt = observedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
