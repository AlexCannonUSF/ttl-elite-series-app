package com.ttl.tabletennis.domain;

public class ParlayMatch {
    private Match match;
    private int vegasOddsPlayer1;
    private int vegasOddsPlayer2;

    public Match getMatch() { return match; }
    public void setMatch(Match match) { this.match = match; }

    public int getVegasOddsPlayer1() { return vegasOddsPlayer1; }
    public void setVegasOddsPlayer1(int vegasOddsPlayer1) { this.vegasOddsPlayer1 = vegasOddsPlayer1; }

    public int getVegasOddsPlayer2() { return vegasOddsPlayer2; }
    public void setVegasOddsPlayer2(int vegasOddsPlayer2) { this.vegasOddsPlayer2 = vegasOddsPlayer2; }
}