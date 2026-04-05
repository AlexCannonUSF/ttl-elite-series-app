package com.ttl.tabletennis.domain;

import com.ttl.tabletennis.util.MatchResultParser;
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

import java.time.LocalDate;

@Entity
@Table(name = "matches", indexes = {
        @Index(name = "idx_matches_player1", columnList = "player1_id"),
        @Index(name = "idx_matches_player2", columnList = "player2_id"),
        @Index(name = "idx_matches_date", columnList = "match_date"),
        @Index(name = "idx_matches_external_id", columnList = "external_id"),
        @Index(name = "idx_matches_source_feed_event_id", columnList = "source_feed_event_id"),
        @Index(name = "idx_matches_h2h_date", columnList = "player1_id, player2_id, match_date")
})
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", unique = true, length = 64)
    private String externalId;

    @Column(name = "source_feed_code", length = 64)
    private String sourceFeedCode;

    @Column(name = "source_feed_event_id", length = 128)
    private String sourceFeedEventId;

    @Column(name = "match_date", nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player1_id")
    private Player player1;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player2_id")
    private Player player2;

    @Column(name = "result", length = 255)
    private String result;

    @Column(name = "player1_sets_won")
    private Integer player1SetsWon;

    @Column(name = "player2_sets_won")
    private Integer player2SetsWon;

    @Column(name = "winner_player_id")
    private Long winnerPlayerId;

    @Column(name = "is_complete", nullable = false)
    private boolean complete;

    @PrePersist
    @PreUpdate
    void applyDefaultsAndValidate() {
        if (date == null) {
            date = LocalDate.now();
        }
        if (!MatchResultParser.isAcceptedResultFormat(result)) {
            throw new IllegalArgumentException("Invalid match result format: " + result);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
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

    public Player getPlayer1() {
        return player1;
    }

    public void setPlayer1(Player player1) {
        this.player1 = player1;
    }

    public Player getPlayer2() {
        return player2;
    }

    public void setPlayer2(Player player2) {
        this.player2 = player2;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Integer getPlayer1SetsWon() {
        return player1SetsWon;
    }

    public void setPlayer1SetsWon(Integer player1SetsWon) {
        this.player1SetsWon = player1SetsWon;
    }

    public Integer getPlayer2SetsWon() {
        return player2SetsWon;
    }

    public void setPlayer2SetsWon(Integer player2SetsWon) {
        this.player2SetsWon = player2SetsWon;
    }

    public Long getWinnerPlayerId() {
        return winnerPlayerId;
    }

    public void setWinnerPlayerId(Long winnerPlayerId) {
        this.winnerPlayerId = winnerPlayerId;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    @Override
    public String toString() {
        return "Match{" +
                "id=" + id +
                ", externalId='" + externalId + '\'' +
                ", sourceFeedCode='" + sourceFeedCode + '\'' +
                ", sourceFeedEventId='" + sourceFeedEventId + '\'' +
                ", date=" + date +
                ", p1=" + (player1 != null ? player1.getName() : "null") +
                ", p2=" + (player2 != null ? player2.getName() : "null") +
                ", result='" + result + '\'' +
                ", p1Sets=" + player1SetsWon +
                ", p2Sets=" + player2SetsWon +
                ", winnerPlayerId=" + winnerPlayerId +
                ", complete=" + complete +
                '}';
    }
}
