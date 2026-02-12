package com.ttl.tabletennis.Entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "matches")
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Keep as String so we can store either numeric IDs or other formats */
    @Column(name = "external_id", unique = true, length = 64)
    private String externalId;

    @Column(name = "match_date")
    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player1_id")
    private Player player1;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player2_id")
    private Player player2;

    /** Free-form result string like "3:2" or page title */
    @Column(name = "result", length = 255)
    private String result;

    // --- getters / setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public Player getPlayer1() { return player1; }
    public void setPlayer1(Player player1) { this.player1 = player1; }

    public Player getPlayer2() { return player2; }
    public void setPlayer2(Player player2) { this.player2 = player2; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    @Override public String toString() {
        return "Match{" +
                "id=" + id +
                ", externalId='" + externalId + '\'' +
                ", date=" + date +
                ", p1=" + (player1 != null ? player1.getName() : "null") +
                ", p2=" + (player2 != null ? player2.getName() : "null") +
                ", result='" + result + '\'' +
                '}';
    }
}