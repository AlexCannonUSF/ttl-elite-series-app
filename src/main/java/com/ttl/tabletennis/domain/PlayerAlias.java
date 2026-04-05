package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.NameUtils;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "player_alias", indexes = {
        @Index(name = "idx_player_alias_player_id", columnList = "player_id"),
        @Index(name = "idx_player_alias_normalized", columnList = "normalized_alias", unique = true)
})
public class PlayerAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "alias_name", nullable = false, length = 180)
    private String aliasName;

    @Column(name = "normalized_alias", nullable = false, length = 180, unique = true)
    private String normalizedAlias;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public PlayerAlias() {
    }

    public PlayerAlias(Player player, String aliasName) {
        this.player = player;
        this.aliasName = aliasName;
    }

    @PrePersist
    @PreUpdate
    void normalizeAlias() {
        normalizedAlias = NameUtils.normalizeForLookup(aliasName);
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

    public String getAliasName() {
        return aliasName;
    }

    public void setAliasName(String aliasName) {
        this.aliasName = aliasName;
    }

    public String getNormalizedAlias() {
        return normalizedAlias;
    }

    public void setNormalizedAlias(String normalizedAlias) {
        this.normalizedAlias = normalizedAlias;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
