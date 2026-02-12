package com.ttl.tabletennis.model;

public class MatchOdds {
    private String playerA, playerB;
    private double oddsA, oddsB;
    private long timestamp;

    public MatchOdds() {}

    public MatchOdds(String playerA, String playerB, double oddsA, double oddsB) {
        this.playerA = playerA; this.playerB = playerB; this.oddsA = oddsA; this.oddsB = oddsB;
        this.timestamp = System.currentTimeMillis();
    }

    public String getPlayerA() { return playerA; }
    public String getPlayerB() { return playerB; }
    public double getOddsA() { return oddsA; }
    public double getOddsB() { return oddsB; }
    public long getTimestamp() { return timestamp; }
}