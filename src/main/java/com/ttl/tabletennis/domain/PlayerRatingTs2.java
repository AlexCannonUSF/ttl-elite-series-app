package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "player_rating_ts2", indexes = {
        @Index(name = "idx_player_rating_ts2_player_date", columnList = "player_id, snapshot_date", unique = true),
        @Index(name = "idx_player_rating_ts2_date", columnList = "snapshot_date")
})
public class PlayerRatingTs2 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "mu", nullable = false)
    private double mu;

    @Column(name = "sigma", nullable = false)
    private double sigma;

    @Column(name = "conservative_skill", nullable = false)
    private double conservativeSkill;

    @Column(name = "matches_seen", nullable = false)
    private long matchesSeen;

    @Column(name = "wins", nullable = false)
    private long wins;

    @Column(name = "losses", nullable = false)
    private long losses;

    @Column(name = "last_match_date")
    private LocalDate lastMatchDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public void setPlayerId(Long playerId) {
        this.playerId = playerId;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public void setSnapshotDate(LocalDate snapshotDate) {
        this.snapshotDate = snapshotDate;
    }

    public double getMu() {
        return mu;
    }

    public void setMu(double mu) {
        this.mu = mu;
    }

    public double getSigma() {
        return sigma;
    }

    public void setSigma(double sigma) {
        this.sigma = sigma;
    }

    public double getConservativeSkill() {
        return conservativeSkill;
    }

    public void setConservativeSkill(double conservativeSkill) {
        this.conservativeSkill = conservativeSkill;
    }

    public long getMatchesSeen() {
        return matchesSeen;
    }

    public void setMatchesSeen(long matchesSeen) {
        this.matchesSeen = matchesSeen;
    }

    public long getWins() {
        return wins;
    }

    public void setWins(long wins) {
        this.wins = wins;
    }

    public long getLosses() {
        return losses;
    }

    public void setLosses(long losses) {
        this.losses = losses;
    }

    public LocalDate getLastMatchDate() {
        return lastMatchDate;
    }

    public void setLastMatchDate(LocalDate lastMatchDate) {
        this.lastMatchDate = lastMatchDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
