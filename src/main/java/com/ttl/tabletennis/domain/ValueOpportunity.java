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

@Entity
@Table(name = "value_opportunity", indexes = {
        @Index(name = "idx_value_opp_created", columnList = "created_at"),
        @Index(name = "idx_value_opp_strategy", columnList = "strategy"),
        @Index(name = "idx_value_opp_matchup", columnList = "player1_id, player2_id")
})
public class ValueOpportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "strategy", nullable = false, length = 24)
    private String strategy;

    @Column(name = "model_version", nullable = false, length = 60)
    private String modelVersion;

    @Column(name = "player1_id", nullable = false)
    private Long player1Id;

    @Column(name = "player2_id", nullable = false)
    private Long player2Id;

    @Column(name = "player_side_id", nullable = false)
    private Long playerSideId;

    @Column(name = "player_side_name", nullable = false, length = 180)
    private String playerSideName;

    @Column(name = "model_probability", nullable = false)
    private double modelProbability;

    @Column(name = "confidence_low", nullable = false)
    private double confidenceLow;

    @Column(name = "confidence_high", nullable = false)
    private double confidenceHigh;

    @Column(name = "implied_probability", nullable = false)
    private double impliedProbability;

    @Column(name = "edge", nullable = false)
    private double edge;

    @Column(name = "threshold", nullable = false)
    private double threshold;

    @Column(name = "american_odds", nullable = false)
    private int americanOdds;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (strategy == null || strategy.isBlank()) {
            strategy = "CONSERVATIVE";
        }
        if (source == null || source.isBlank()) {
            source = "UNKNOWN";
        }
    }

    public Long getId() {
        return id;
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

    public Long getPlayerSideId() {
        return playerSideId;
    }

    public void setPlayerSideId(Long playerSideId) {
        this.playerSideId = playerSideId;
    }

    public String getPlayerSideName() {
        return playerSideName;
    }

    public void setPlayerSideName(String playerSideName) {
        this.playerSideName = playerSideName;
    }

    public double getModelProbability() {
        return modelProbability;
    }

    public void setModelProbability(double modelProbability) {
        this.modelProbability = modelProbability;
    }

    public double getConfidenceLow() {
        return confidenceLow;
    }

    public void setConfidenceLow(double confidenceLow) {
        this.confidenceLow = confidenceLow;
    }

    public double getConfidenceHigh() {
        return confidenceHigh;
    }

    public void setConfidenceHigh(double confidenceHigh) {
        this.confidenceHigh = confidenceHigh;
    }

    public double getImpliedProbability() {
        return impliedProbability;
    }

    public void setImpliedProbability(double impliedProbability) {
        this.impliedProbability = impliedProbability;
    }

    public double getEdge() {
        return edge;
    }

    public void setEdge(double edge) {
        this.edge = edge;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public int getAmericanOdds() {
        return americanOdds;
    }

    public void setAmericanOdds(int americanOdds) {
        this.americanOdds = americanOdds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
