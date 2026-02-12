package com.ttl.tabletennis.Entity;

public class PlayerStatistics {
    private long wins;
    private long losses;
    private long matches;
    private double winPct;

    public PlayerStatistics() {}

    // Your service constructs this with four args:
    public PlayerStatistics(long wins, long losses, long matches, double winPct) {
        this.wins = wins;
        this.losses = losses;
        this.matches = matches;
        this.winPct = winPct;
    }

    public long getWins() { return wins; }
    public void setWins(long wins) { this.wins = wins; }

    public long getLosses() { return losses; }
    public void setLosses(long losses) { this.losses = losses; }

    public long getMatches() { return matches; }
    public void setMatches(long matches) { this.matches = matches; }

    public double getWinPct() { return winPct; }
    public void setWinPct(double winPct) { this.winPct = winPct; }
}