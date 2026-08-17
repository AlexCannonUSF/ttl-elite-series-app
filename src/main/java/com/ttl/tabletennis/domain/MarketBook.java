package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "market_book", uniqueConstraints = @UniqueConstraint(name = "uk_market_book_source", columnNames = "source_code"))
public class MarketBook {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "source_code", nullable = false, length = 32) private String sourceCode;
    @Column(name = "display_name", nullable = false, length = 100) private String displayName;
    @Column(name = "market_role", nullable = false, length = 24) private String marketRole;
    @Column(name = "authorized", nullable = false) private boolean authorized;
    @Column(name = "enabled", nullable = false) private boolean enabled;
    @Column(name = "consensus_weight", nullable = false) private double consensusWeight;
    @Column(name = "notes", length = 600) private String notes;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist void prePersist() { LocalDateTime now = LocalDateTime.now(); if (createdAt == null) createdAt = now; if (updatedAt == null) updatedAt = now; }
    public Long getId() { return id; }
    public String getSourceCode() { return sourceCode; } public void setSourceCode(String value) { sourceCode = value; }
    public String getDisplayName() { return displayName; } public void setDisplayName(String value) { displayName = value; }
    public String getMarketRole() { return marketRole; } public void setMarketRole(String value) { marketRole = value; }
    public boolean isAuthorized() { return authorized; } public void setAuthorized(boolean value) { authorized = value; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean value) { enabled = value; }
    public double getConsensusWeight() { return consensusWeight; } public void setConsensusWeight(double value) { consensusWeight = value; }
    public String getNotes() { return notes; } public void setNotes(String value) { notes = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
