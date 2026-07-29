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
@Table(name = "player_rating_wl", indexes = {
        @Index(name = "idx_player_rating_wl_player_date", columnList = "player_id, snapshot_date", unique = true),
        @Index(name = "idx_player_rating_wl_date", columnList = "snapshot_date")
})
public class PlayerRatingWl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "rating", nullable = false)
    private double rating;

    @Column(name = "uncertainty", nullable = false)
    private double uncertainty;

    @Column(name = "conservative_rating", nullable = false)
    private double conservativeRating;

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

    public double getRating() {
        return rating;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }

    public double getUncertainty() {
        return uncertainty;
    }

    public void setUncertainty(double uncertainty) {
        this.uncertainty = uncertainty;
    }

    public double getConservativeRating() {
        return conservativeRating;
    }

    public void setConservativeRating(double conservativeRating) {
        this.conservativeRating = conservativeRating;
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
