package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "paper_trade_session", indexes = {
        @Index(name = "idx_paper_session_status", columnList = "status"),
        @Index(name = "idx_paper_session_updated", columnList = "updated_at")
})
public class PaperTradeSession {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "row_version", nullable = false, columnDefinition = "bigint default 0 not null")
    private long rowVersion;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "label", nullable = false, length = 80)
    private String label;

    @Column(name = "requested_model_version", length = 100)
    private String requestedModelVersion;

    @Column(name = "effective_model_version", length = 100)
    private String effectiveModelVersion;

    @Column(name = "effective_model_family", length = 40)
    private String effectiveModelFamily;

    @Column(name = "policy_version", length = 100)
    private String policyVersion;

    @Column(name = "code_revision", length = 80)
    private String codeRevision;

    @Column(name = "starting_bankroll", nullable = false)
    private double startingBankroll;

    @Column(name = "current_bankroll", nullable = false)
    private double currentBankroll;

    @Column(name = "peak_bankroll", nullable = false)
    private double peakBankroll;

    @Column(name = "realized_pnl", nullable = false)
    private double realizedPnl;

    @Column(name = "total_staked", nullable = false)
    private double totalStaked;

    @Column(name = "total_returned", nullable = false)
    private double totalReturned;

    @Column(name = "total_bets", nullable = false)
    private int totalBets;

    @Column(name = "wins", nullable = false)
    private int wins;

    @Column(name = "losses", nullable = false)
    private int losses;

    @Column(name = "pushes", nullable = false)
    private int pushes;

    @Column(name = "simulation_rows_scanned", nullable = false, columnDefinition = "bigint default 0 not null")
    private long simulationRowsScanned;

    @Column(name = "simulation_bets_placed", nullable = false, columnDefinition = "bigint default 0 not null")
    private long simulationBetsPlaced;

    @Column(name = "simulation_bets_settled", nullable = false, columnDefinition = "bigint default 0 not null")
    private long simulationBetsSettled;

    @Column(name = "simulation_bets_voided", nullable = false, columnDefinition = "bigint default 0 not null")
    private long simulationBetsVoided;

    @Column(name = "adaptive_sample_size", nullable = false, columnDefinition = "integer default 0 not null")
    private int adaptiveSampleSize;

    @Column(name = "adaptive_edge_shift", nullable = false, columnDefinition = "double precision default 0 not null")
    private double adaptiveEdgeShift;

    @Column(name = "adaptive_selection_shift", nullable = false, columnDefinition = "double precision default 0 not null")
    private double adaptiveSelectionScoreShift;

    @Column(name = "adaptive_stake_multiplier", nullable = false, columnDefinition = "double precision default 1 not null")
    private double adaptiveStakeMultiplier;

    @Column(name = "adaptive_calibration_error", nullable = false, columnDefinition = "double precision default 0 not null")
    private double adaptiveCalibrationError;

    @Column(name = "adaptive_roi_signal", nullable = false, columnDefinition = "double precision default 0 not null")
    private double adaptiveRoiSignal;

    @Column(name = "adaptive_updated_at")
    private LocalDateTime adaptiveUpdatedAt;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    void prePersist() {
        if (status == null || status.isBlank()) {
            status = STATUS_ACTIVE;
        }
        if (label == null || label.isBlank()) {
            label = "Default Session";
        }
        if (peakBankroll <= 0.0) {
            peakBankroll = Math.max(startingBankroll, currentBankroll);
        }
        if (adaptiveStakeMultiplier <= 0.0) {
            adaptiveStakeMultiplier = 1.0;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getRequestedModelVersion() {
        return requestedModelVersion;
    }

    public void setRequestedModelVersion(String requestedModelVersion) {
        this.requestedModelVersion = requestedModelVersion;
    }

    public String getEffectiveModelVersion() {
        return effectiveModelVersion;
    }

    public void setEffectiveModelVersion(String effectiveModelVersion) {
        this.effectiveModelVersion = effectiveModelVersion;
    }

    public String getEffectiveModelFamily() {
        return effectiveModelFamily;
    }

    public void setEffectiveModelFamily(String effectiveModelFamily) {
        this.effectiveModelFamily = effectiveModelFamily;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getCodeRevision() {
        return codeRevision;
    }

    public void setCodeRevision(String codeRevision) {
        this.codeRevision = codeRevision;
    }

    public double getStartingBankroll() {
        return startingBankroll;
    }

    public void setStartingBankroll(double startingBankroll) {
        this.startingBankroll = startingBankroll;
    }

    public double getCurrentBankroll() {
        return currentBankroll;
    }

    public void setCurrentBankroll(double currentBankroll) {
        this.currentBankroll = currentBankroll;
    }

    public double getPeakBankroll() {
        return peakBankroll;
    }

    public void setPeakBankroll(double peakBankroll) {
        this.peakBankroll = peakBankroll;
    }

    public double getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(double realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public double getTotalStaked() {
        return totalStaked;
    }

    public void setTotalStaked(double totalStaked) {
        this.totalStaked = totalStaked;
    }

    public double getTotalReturned() {
        return totalReturned;
    }

    public void setTotalReturned(double totalReturned) {
        this.totalReturned = totalReturned;
    }

    public int getTotalBets() {
        return totalBets;
    }

    public void setTotalBets(int totalBets) {
        this.totalBets = totalBets;
    }

    public int getWins() {
        return wins;
    }

    public void setWins(int wins) {
        this.wins = wins;
    }

    public int getLosses() {
        return losses;
    }

    public void setLosses(int losses) {
        this.losses = losses;
    }

    public int getPushes() {
        return pushes;
    }

    public void setPushes(int pushes) {
        this.pushes = pushes;
    }

    public long getSimulationRowsScanned() {
        return simulationRowsScanned;
    }

    public void setSimulationRowsScanned(long simulationRowsScanned) {
        this.simulationRowsScanned = simulationRowsScanned;
    }

    public long getSimulationBetsPlaced() {
        return simulationBetsPlaced;
    }

    public void setSimulationBetsPlaced(long simulationBetsPlaced) {
        this.simulationBetsPlaced = simulationBetsPlaced;
    }

    public long getSimulationBetsSettled() {
        return simulationBetsSettled;
    }

    public void setSimulationBetsSettled(long simulationBetsSettled) {
        this.simulationBetsSettled = simulationBetsSettled;
    }

    public long getSimulationBetsVoided() {
        return simulationBetsVoided;
    }

    public void setSimulationBetsVoided(long simulationBetsVoided) {
        this.simulationBetsVoided = simulationBetsVoided;
    }

    public int getAdaptiveSampleSize() {
        return adaptiveSampleSize;
    }

    public void setAdaptiveSampleSize(int adaptiveSampleSize) {
        this.adaptiveSampleSize = adaptiveSampleSize;
    }

    public double getAdaptiveEdgeShift() {
        return adaptiveEdgeShift;
    }

    public void setAdaptiveEdgeShift(double adaptiveEdgeShift) {
        this.adaptiveEdgeShift = adaptiveEdgeShift;
    }

    public double getAdaptiveSelectionScoreShift() {
        return adaptiveSelectionScoreShift;
    }

    public void setAdaptiveSelectionScoreShift(double adaptiveSelectionScoreShift) {
        this.adaptiveSelectionScoreShift = adaptiveSelectionScoreShift;
    }

    public double getAdaptiveStakeMultiplier() {
        return adaptiveStakeMultiplier;
    }

    public void setAdaptiveStakeMultiplier(double adaptiveStakeMultiplier) {
        this.adaptiveStakeMultiplier = adaptiveStakeMultiplier;
    }

    public double getAdaptiveCalibrationError() {
        return adaptiveCalibrationError;
    }

    public void setAdaptiveCalibrationError(double adaptiveCalibrationError) {
        this.adaptiveCalibrationError = adaptiveCalibrationError;
    }

    public double getAdaptiveRoiSignal() {
        return adaptiveRoiSignal;
    }

    public void setAdaptiveRoiSignal(double adaptiveRoiSignal) {
        this.adaptiveRoiSignal = adaptiveRoiSignal;
    }

    public LocalDateTime getAdaptiveUpdatedAt() {
        return adaptiveUpdatedAt;
    }

    public void setAdaptiveUpdatedAt(LocalDateTime adaptiveUpdatedAt) {
        this.adaptiveUpdatedAt = adaptiveUpdatedAt;
    }

    public LocalDateTime getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(LocalDateTime lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }
}
