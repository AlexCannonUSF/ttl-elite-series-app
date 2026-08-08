package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.CorrelationContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_evidence", indexes = {
        @Index(name = "idx_settlement_evidence_bet_asof", columnList = "bet_id, bundle_as_of"),
        @Index(name = "idx_settlement_evidence_event_asof", columnList = "tracked_event_id, bundle_as_of"),
        @Index(name = "uq_settlement_evidence_fingerprint", columnList = "evidence_fingerprint", unique = true)
})
public class SettlementEvidenceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bet_id", nullable = false)
    private Long betId;

    @Column(name = "tracked_event_id", length = 128)
    private String trackedEventId;

    @Column(name = "bundle_as_of", nullable = false)
    private LocalDateTime bundleAsOf;

    @Column(name = "coverage_state", nullable = false, length = 16)
    private String coverageState;

    @Column(name = "ambiguity_score", nullable = false)
    private double ambiguityScore;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    @Column(name = "evidence_fingerprint", length = 64)
    private String evidenceFingerprint;

    @Column(name = "score_evidence_quality", length = 24)
    private String scoreEvidenceQuality;

    @Column(name = "score_evidence_finality", length = 32)
    private String scoreEvidenceFinality;

    @Column(name = "score_evidence_confidence")
    private Double scoreEvidenceConfidence;

    @Column(name = "score_observation_count")
    private Integer scoreObservationCount;

    @Column(name = "score_source_count")
    private Integer scoreSourceCount;

    @Column(name = "score_completion_signal_count")
    private Integer scoreCompletionSignalCount;

    @Column(name = "score_inferred_winner_id")
    private Long scoreInferredWinnerId;

    /** Final label decision. Evidence remains inspectable when false, but it
     * must not flow into model adaptation. */
    @Column(name = "learning_eligible", nullable = false, columnDefinition = "boolean default false not null")
    private boolean learningEligible;

    @Column(name = "learning_exclusion_reason", length = 64)
    private String learningExclusionReason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (bundleAsOf == null) {
            bundleAsOf = LocalDateTime.now();
        }
        if (coverageState == null || coverageState.isBlank()) {
            coverageState = "DARK";
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            payloadJson = "{}";
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = CorrelationContext.currentOrCreate();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getBetId() {
        return betId;
    }

    public void setBetId(Long betId) {
        this.betId = betId;
    }

    public String getTrackedEventId() {
        return trackedEventId;
    }

    public void setTrackedEventId(String trackedEventId) {
        this.trackedEventId = trackedEventId;
    }

    public LocalDateTime getBundleAsOf() {
        return bundleAsOf;
    }

    public void setBundleAsOf(LocalDateTime bundleAsOf) {
        this.bundleAsOf = bundleAsOf;
    }

    public String getCoverageState() {
        return coverageState;
    }

    public void setCoverageState(String coverageState) {
        this.coverageState = coverageState;
    }

    public double getAmbiguityScore() {
        return ambiguityScore;
    }

    public void setAmbiguityScore(double ambiguityScore) {
        this.ambiguityScore = ambiguityScore;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getEvidenceFingerprint() {
        return evidenceFingerprint;
    }

    public void setEvidenceFingerprint(String evidenceFingerprint) {
        this.evidenceFingerprint = evidenceFingerprint;
    }

    public String getScoreEvidenceQuality() {
        return scoreEvidenceQuality;
    }

    public void setScoreEvidenceQuality(String scoreEvidenceQuality) {
        this.scoreEvidenceQuality = scoreEvidenceQuality;
    }

    public String getScoreEvidenceFinality() {
        return scoreEvidenceFinality;
    }

    public void setScoreEvidenceFinality(String scoreEvidenceFinality) {
        this.scoreEvidenceFinality = scoreEvidenceFinality;
    }

    public Double getScoreEvidenceConfidence() {
        return scoreEvidenceConfidence;
    }

    public void setScoreEvidenceConfidence(Double scoreEvidenceConfidence) {
        this.scoreEvidenceConfidence = scoreEvidenceConfidence;
    }

    public Integer getScoreObservationCount() {
        return scoreObservationCount;
    }

    public void setScoreObservationCount(Integer scoreObservationCount) {
        this.scoreObservationCount = scoreObservationCount;
    }

    public Integer getScoreSourceCount() {
        return scoreSourceCount;
    }

    public void setScoreSourceCount(Integer scoreSourceCount) {
        this.scoreSourceCount = scoreSourceCount;
    }

    public Integer getScoreCompletionSignalCount() {
        return scoreCompletionSignalCount;
    }

    public void setScoreCompletionSignalCount(Integer scoreCompletionSignalCount) {
        this.scoreCompletionSignalCount = scoreCompletionSignalCount;
    }

    public Long getScoreInferredWinnerId() {
        return scoreInferredWinnerId;
    }

    public void setScoreInferredWinnerId(Long scoreInferredWinnerId) {
        this.scoreInferredWinnerId = scoreInferredWinnerId;
    }

    public boolean isLearningEligible() {
        return learningEligible;
    }

    public void setLearningEligible(boolean learningEligible) {
        this.learningEligible = learningEligible;
    }

    public String getLearningExclusionReason() {
        return learningExclusionReason;
    }

    public void setLearningExclusionReason(String learningExclusionReason) {
        this.learningExclusionReason = learningExclusionReason;
    }
}
