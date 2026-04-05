package com.ttl.tabletennis.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "matchups")
public class Matchup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch=FetchType.EAGER, optional=false)
    @JoinColumn(name = "player1_id", nullable=false)
    private Player player1;

    @ManyToOne(fetch=FetchType.EAGER, optional=false)
    @JoinColumn(name = "player2_id", nullable=false)
    private Player player2;

    @Column(name = "player1_win_probability", nullable=false)
    private double player1WinProbability;

    @Column(name = "player2_win_probability", nullable=false)
    private double player2WinProbability;

    public Matchup(Player player1, Player player2, double player1WinProbability, double player2WinProbability) {
        this.player1 = player1;
        this.player2 = player2;
        this.player1WinProbability = player1WinProbability;
        this.player2WinProbability = player2WinProbability;
    }

    @Override
    public String toString() {
        return String.format(
                "%s vs %s (Win Probabilities: %s = %.2f%%, %s = %.2f%%)",
                player1.getName(),
                player2.getName(),
                player1.getName(),
                player1WinProbability * 100,
                player2.getName(),
                player2WinProbability * 100
        );
    }
}
