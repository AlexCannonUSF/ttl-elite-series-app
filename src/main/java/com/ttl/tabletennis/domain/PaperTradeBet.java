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

@Entity
@Table(name = "paper_trade_bet", indexes = {
        @Index(name = "idx_paper_bet_session_status", columnList = "session_id, status"),
        @Index(name = "idx_paper_bet_session_placed", columnList = "session_id, placed_at"),
        @Index(name = "idx_paper_bet_session_settled", columnList = "session_id, settled_at"),
        @Index(name = "idx_paper_bet_matchup", columnList = "player1_id, player2_id"),
        @Index(name = "idx_paper_bet_dedupe", columnList = "session_id, dedupe_key")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_paper_bet_session_dedupe", columnNames = {"session_id", "dedupe_key"})
})
public class PaperTradeBet {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";
    public static final String STATUS_PUSHED = "PUSHED";
    public static final String STATUS_VOIDED = "VOIDED";
    public static final String STATUS_PENDING_EVIDENCE = "PENDING_EVIDENCE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "source", nullable = false, length = 128)
    private String source;

    @Column(name = "strategy", nullable = false, length = 24)
    private String strategy;

    @Column(name = "model_version", nullable = false, length = 80)
    private String modelVersion;

    @Column(name = "event_key", nullable = false, length = 320)
    private String eventKey;

    @Column(name = "dedupe_key", nullable = false, length = 420)
    private String dedupeKey;

    @Column(name = "event_name", nullable = false, length = 220)
    private String eventName;

    @Column(name = "competition_name", nullable = false, length = 180)
    private String competitionName;

    @Column(name = "start_time_iso", length = 64)
    private String startTimeIso;

    @Column(name = "external_event_id", length = 96)
    private String externalEventId;

    @Column(name = "identity_locked", nullable = false, columnDefinition = "boolean default false not null")
    private boolean identityLocked;

    @Column(name = "identity_locked_at")
    private LocalDateTime identityLockedAt;

    @Column(name = "locked_start_time_iso", length = 64)
    private String lockedStartTimeIso;

    @Column(name = "locked_external_event_id", length = 96)
    private String lockedExternalEventId;

    @Column(name = "locked_source_feed_event_id", length = 128)
    private String lockedSourceFeedEventId;

    @Column(name = "identity_drift_count", nullable = false, columnDefinition = "integer default 0 not null")
    private int identityDriftCount;

    @Column(name = "last_identity_drift_at")
    private LocalDateTime lastIdentityDriftAt;

    @Column(name = "live_at_placement", nullable = false)
    private boolean liveAtPlacement;

    @Column(name = "placement_phase", length = 48)
    private String placementPhase;

    @Column(name = "player1_id")
    private Long player1Id;

    @Column(name = "player2_id")
    private Long player2Id;

    @Column(name = "side_player_id")
    private Long sidePlayerId;

    @Column(name = "player1_name", nullable = false, length = 180)
    private String player1Name;

    @Column(name = "player2_name", nullable = false, length = 180)
    private String player2Name;

    @Column(name = "side_name", nullable = false, length = 180)
    private String sideName;

    @Column(name = "decimal_odds", nullable = false)
    private double decimalOdds;

    @Column(name = "american_odds", nullable = false)
    private int americanOdds;

    @Column(name = "implied_probability", nullable = false)
    private double impliedProbability;

    @Column(name = "model_probability", nullable = false)
    private double modelProbability;

    @Column(name = "edge", nullable = false)
    private double edge;

    @Column(name = "confidence_low")
    private Double confidenceLow;

    @Column(name = "confidence_high")
    private Double confidenceHigh;

    @Column(name = "stake", nullable = false)
    private double stake;

    @Column(name = "potential_payout", nullable = false)
    private double potentialPayout;

    @Column(name = "profit_loss")
    private Double profitLoss;

    @Column(name = "winner_player_id")
    private Long winnerPlayerId;

    @Column(name = "result_match_id")
    private Long resultMatchId;

    @Column(name = "top_trigger", length = 180)
    private String topTrigger;

    @Column(name = "top_trigger_contribution")
    private Double topTriggerContribution;

    @Column(name = "feature_contributions", length = 2400)
    private String featureContributions;

    @Column(name = "grade", length = 8)
    private String grade;

    @Column(name = "rationale", length = 512)
    private String rationale;

    @Column(name = "last_observed_score", length = 64)
    private String lastObservedScore;

    @Column(name = "last_observed_phase", length = 48)
    private String lastObservedPhase;

    @Column(name = "last_score_source", length = 48)
    private String lastScoreSource;

    @Column(name = "last_score_confidence")
    private Double lastScoreConfidence;

    @Column(name = "last_observation_displayed", nullable = false, columnDefinition = "boolean default true not null")
    private boolean lastObservationDisplayed = true;

    @Column(name = "last_observation_resulted", nullable = false, columnDefinition = "boolean default false not null")
    private boolean lastObservationResulted;

    @Column(name = "last_match_completed", nullable = false, columnDefinition = "boolean default false not null")
    private boolean lastMatchCompleted;

    @Column(name = "last_source_feed_code", length = 64)
    private String lastSourceFeedCode;

    @Column(name = "last_source_feed_event_id", length = 128)
    private String lastSourceFeedEventId;

    @Column(name = "last_score_detail", length = 180)
    private String lastScoreDetail;

    @Column(name = "tracked_after_close", nullable = false, columnDefinition = "boolean default false not null")
    private boolean trackedAfterClose;

    @Column(name = "settlement_reason", length = 96)
    private String settlementReason;

    @Column(name = "settlement_source", length = 48)
    private String settlementSource;

    @Column(name = "last_observed_at")
    private LocalDateTime lastObservedAt;

    @Column(name = "missing_board_count", nullable = false, columnDefinition = "integer default 0 not null")
    private int missingBoardCount;

    @Column(name = "pending_evidence_until")
    private LocalDateTime pendingEvidenceUntil;

    @Column(name = "pending_evidence_next_poll_at")
    private LocalDateTime pendingEvidenceNextPollAt;

    @Column(name = "pending_evidence_reason", length = 96)
    private String pendingEvidenceReason;

    @Column(name = "pending_evidence_note", length = 256)
    private String pendingEvidenceNote;

    @Column(name = "pending_evidence_updated_at")
    private LocalDateTime pendingEvidenceUpdatedAt;

    @Column(name = "placed_at", nullable = false)
    private LocalDateTime placedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @PrePersist
    void prePersist() {
        if (status == null || status.isBlank()) {
            status = STATUS_OPEN;
        }
        if (source == null || source.isBlank()) {
            source = "UNKNOWN";
        }
        if (strategy == null || strategy.isBlank()) {
            strategy = "CONSERVATIVE";
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            modelVersion = "ENSEMBLE";
        }
        if (eventKey == null || eventKey.isBlank()) {
            eventKey = "unknown-event";
        }
        if (dedupeKey == null || dedupeKey.isBlank()) {
            dedupeKey = eventKey;
        }
        if (eventName == null || eventName.isBlank()) {
            eventName = "Unknown Event";
        }
        if (competitionName == null || competitionName.isBlank()) {
            competitionName = "Table Tennis";
        }
        if (player1Name == null || player1Name.isBlank()) {
            player1Name = "Player 1";
        }
        if (player2Name == null || player2Name.isBlank()) {
            player2Name = "Player 2";
        }
        if (sideName == null || sideName.isBlank()) {
            sideName = player1Name;
        }
        if (placedAt == null) {
            placedAt = LocalDateTime.now();
        }
        if (missingBoardCount < 0) {
            missingBoardCount = 0;
        }
        if (identityDriftCount < 0) {
            identityDriftCount = 0;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
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

    public String getExternalEventId() {
        return externalEventId;
    }

    public void setExternalEventId(String externalEventId) {
        this.externalEventId = externalEventId;
    }

    public boolean isIdentityLocked() {
        return identityLocked;
    }

    public void setIdentityLocked(boolean identityLocked) {
        this.identityLocked = identityLocked;
    }

    public LocalDateTime getIdentityLockedAt() {
        return identityLockedAt;
    }

    public void setIdentityLockedAt(LocalDateTime identityLockedAt) {
        this.identityLockedAt = identityLockedAt;
    }

    public String getLockedStartTimeIso() {
        return lockedStartTimeIso;
    }

    public void setLockedStartTimeIso(String lockedStartTimeIso) {
        this.lockedStartTimeIso = lockedStartTimeIso;
    }

    public String getLockedExternalEventId() {
        return lockedExternalEventId;
    }

    public void setLockedExternalEventId(String lockedExternalEventId) {
        this.lockedExternalEventId = lockedExternalEventId;
    }

    public String getLockedSourceFeedEventId() {
        return lockedSourceFeedEventId;
    }

    public void setLockedSourceFeedEventId(String lockedSourceFeedEventId) {
        this.lockedSourceFeedEventId = lockedSourceFeedEventId;
    }

    public int getIdentityDriftCount() {
        return identityDriftCount;
    }

    public void setIdentityDriftCount(int identityDriftCount) {
        this.identityDriftCount = identityDriftCount;
    }

    public LocalDateTime getLastIdentityDriftAt() {
        return lastIdentityDriftAt;
    }

    public void setLastIdentityDriftAt(LocalDateTime lastIdentityDriftAt) {
        this.lastIdentityDriftAt = lastIdentityDriftAt;
    }

    public boolean isLiveAtPlacement() {
        return liveAtPlacement;
    }

    public void setLiveAtPlacement(boolean liveAtPlacement) {
        this.liveAtPlacement = liveAtPlacement;
    }

    public String getPlacementPhase() {
        return placementPhase;
    }

    public void setPlacementPhase(String placementPhase) {
        this.placementPhase = placementPhase;
    }

    public Long getPlayer1Id() {
        return player1Id;
    }

    public void setPlayer1Id(Long player1Id) {
        this.player1Id = player1Id;
    }

    public Long getPlayer2Id() {
        return player2Id;
    }

    public void setPlayer2Id(Long player2Id) {
        this.player2Id = player2Id;
    }

    public Long getSidePlayerId() {
        return sidePlayerId;
    }

    public void setSidePlayerId(Long sidePlayerId) {
        this.sidePlayerId = sidePlayerId;
    }

    public String getPlayer1Name() {
        return player1Name;
    }

    public void setPlayer1Name(String player1Name) {
        this.player1Name = player1Name;
    }

    public String getPlayer2Name() {
        return player2Name;
    }

    public void setPlayer2Name(String player2Name) {
        this.player2Name = player2Name;
    }

    public String getSideName() {
        return sideName;
    }

    public void setSideName(String sideName) {
        this.sideName = sideName;
    }

    public double getDecimalOdds() {
        return decimalOdds;
    }

    public void setDecimalOdds(double decimalOdds) {
        this.decimalOdds = decimalOdds;
    }

    public int getAmericanOdds() {
        return americanOdds;
    }

    public void setAmericanOdds(int americanOdds) {
        this.americanOdds = americanOdds;
    }

    public double getImpliedProbability() {
        return impliedProbability;
    }

    public void setImpliedProbability(double impliedProbability) {
        this.impliedProbability = impliedProbability;
    }

    public double getModelProbability() {
        return modelProbability;
    }

    public void setModelProbability(double modelProbability) {
        this.modelProbability = modelProbability;
    }

    public double getEdge() {
        return edge;
    }

    public void setEdge(double edge) {
        this.edge = edge;
    }

    public Double getConfidenceLow() {
        return confidenceLow;
    }

    public void setConfidenceLow(Double confidenceLow) {
        this.confidenceLow = confidenceLow;
    }

    public Double getConfidenceHigh() {
        return confidenceHigh;
    }

    public void setConfidenceHigh(Double confidenceHigh) {
        this.confidenceHigh = confidenceHigh;
    }

    public double getStake() {
        return stake;
    }

    public void setStake(double stake) {
        this.stake = stake;
    }

    public double getPotentialPayout() {
        return potentialPayout;
    }

    public void setPotentialPayout(double potentialPayout) {
        this.potentialPayout = potentialPayout;
    }

    public Double getProfitLoss() {
        return profitLoss;
    }

    public void setProfitLoss(Double profitLoss) {
        this.profitLoss = profitLoss;
    }

    public Long getWinnerPlayerId() {
        return winnerPlayerId;
    }

    public void setWinnerPlayerId(Long winnerPlayerId) {
        this.winnerPlayerId = winnerPlayerId;
    }

    public Long getResultMatchId() {
        return resultMatchId;
    }

    public void setResultMatchId(Long resultMatchId) {
        this.resultMatchId = resultMatchId;
    }

    public String getTopTrigger() {
        return topTrigger;
    }

    public void setTopTrigger(String topTrigger) {
        this.topTrigger = topTrigger;
    }

    public Double getTopTriggerContribution() {
        return topTriggerContribution;
    }

    public void setTopTriggerContribution(Double topTriggerContribution) {
        this.topTriggerContribution = topTriggerContribution;
    }

    public String getFeatureContributions() {
        return featureContributions;
    }

    public void setFeatureContributions(String featureContributions) {
        this.featureContributions = featureContributions;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getLastObservedScore() {
        return lastObservedScore;
    }

    public void setLastObservedScore(String lastObservedScore) {
        this.lastObservedScore = lastObservedScore;
    }

    public String getLastObservedPhase() {
        return lastObservedPhase;
    }

    public void setLastObservedPhase(String lastObservedPhase) {
        this.lastObservedPhase = lastObservedPhase;
    }

    public String getLastScoreSource() {
        return lastScoreSource;
    }

    public void setLastScoreSource(String lastScoreSource) {
        this.lastScoreSource = lastScoreSource;
    }

    public Double getLastScoreConfidence() {
        return lastScoreConfidence;
    }

    public void setLastScoreConfidence(Double lastScoreConfidence) {
        this.lastScoreConfidence = lastScoreConfidence;
    }

    public boolean isLastObservationDisplayed() {
        return lastObservationDisplayed;
    }

    public void setLastObservationDisplayed(boolean lastObservationDisplayed) {
        this.lastObservationDisplayed = lastObservationDisplayed;
    }

    public boolean isLastObservationResulted() {
        return lastObservationResulted;
    }

    public void setLastObservationResulted(boolean lastObservationResulted) {
        this.lastObservationResulted = lastObservationResulted;
    }

    public boolean isLastMatchCompleted() {
        return lastMatchCompleted;
    }

    public void setLastMatchCompleted(boolean lastMatchCompleted) {
        this.lastMatchCompleted = lastMatchCompleted;
    }

    public String getLastSourceFeedCode() {
        return lastSourceFeedCode;
    }

    public void setLastSourceFeedCode(String lastSourceFeedCode) {
        this.lastSourceFeedCode = lastSourceFeedCode;
    }

    public String getLastSourceFeedEventId() {
        return lastSourceFeedEventId;
    }

    public void setLastSourceFeedEventId(String lastSourceFeedEventId) {
        this.lastSourceFeedEventId = lastSourceFeedEventId;
    }

    public String getLastScoreDetail() {
        return lastScoreDetail;
    }

    public void setLastScoreDetail(String lastScoreDetail) {
        this.lastScoreDetail = lastScoreDetail;
    }

    public boolean isTrackedAfterClose() {
        return trackedAfterClose;
    }

    public void setTrackedAfterClose(boolean trackedAfterClose) {
        this.trackedAfterClose = trackedAfterClose;
    }

    public String getSettlementReason() {
        return settlementReason;
    }

    public void setSettlementReason(String settlementReason) {
        this.settlementReason = settlementReason;
    }

    public String getSettlementSource() {
        return settlementSource;
    }

    public void setSettlementSource(String settlementSource) {
        this.settlementSource = settlementSource;
    }

    public LocalDateTime getLastObservedAt() {
        return lastObservedAt;
    }

    public void setLastObservedAt(LocalDateTime lastObservedAt) {
        this.lastObservedAt = lastObservedAt;
    }

    public int getMissingBoardCount() {
        return missingBoardCount;
    }

    public void setMissingBoardCount(int missingBoardCount) {
        this.missingBoardCount = missingBoardCount;
    }

    public LocalDateTime getPendingEvidenceUntil() {
        return pendingEvidenceUntil;
    }

    public void setPendingEvidenceUntil(LocalDateTime pendingEvidenceUntil) {
        this.pendingEvidenceUntil = pendingEvidenceUntil;
    }

    public LocalDateTime getPendingEvidenceNextPollAt() {
        return pendingEvidenceNextPollAt;
    }

    public void setPendingEvidenceNextPollAt(LocalDateTime pendingEvidenceNextPollAt) {
        this.pendingEvidenceNextPollAt = pendingEvidenceNextPollAt;
    }

    public String getPendingEvidenceReason() {
        return pendingEvidenceReason;
    }

    public void setPendingEvidenceReason(String pendingEvidenceReason) {
        this.pendingEvidenceReason = pendingEvidenceReason;
    }

    public String getPendingEvidenceNote() {
        return pendingEvidenceNote;
    }

    public void setPendingEvidenceNote(String pendingEvidenceNote) {
        this.pendingEvidenceNote = pendingEvidenceNote;
    }

    public LocalDateTime getPendingEvidenceUpdatedAt() {
        return pendingEvidenceUpdatedAt;
    }

    public void setPendingEvidenceUpdatedAt(LocalDateTime pendingEvidenceUpdatedAt) {
        this.pendingEvidenceUpdatedAt = pendingEvidenceUpdatedAt;
    }

    public LocalDateTime getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(LocalDateTime placedAt) {
        this.placedAt = placedAt;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(LocalDateTime settledAt) {
        this.settledAt = settledAt;
    }
}
