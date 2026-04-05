package com.ttl.tabletennis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "rating_snapshot", indexes = {
        @Index(name = "idx_rating_snapshot_player_date", columnList = "player_id, snapshot_date"),
        @Index(name = "idx_rating_snapshot_player_system_date", columnList = "player_id, rating_system, snapshot_date", unique = true)
})
public class RatingSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "rating", nullable = false)
    private double rating;

    @Column(name = "rating_deviation")
    private Double ratingDeviation;

    @Column(name = "volatility")
    private Double volatility;

    @Column(name = "rating_system", nullable = false, length = 32)
    private String ratingSystem;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void applyDefaults() {
        if (snapshotDate == null) {
            snapshotDate = LocalDate.now();
        }
        if (ratingSystem == null || ratingSystem.isBlank()) {
            ratingSystem = "ELO";
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
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

    public Double getRatingDeviation() {
        return ratingDeviation;
    }

    public void setRatingDeviation(Double ratingDeviation) {
        this.ratingDeviation = ratingDeviation;
    }

    public Double getVolatility() {
        return volatility;
    }

    public void setVolatility(Double volatility) {
        this.volatility = volatility;
    }

    public String getRatingSystem() {
        return ratingSystem;
    }

    public void setRatingSystem(String ratingSystem) {
        this.ratingSystem = ratingSystem;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
