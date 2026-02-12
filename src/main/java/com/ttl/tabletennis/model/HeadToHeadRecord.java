package com.ttl.tabletennis.model;

public class HeadToHeadRecord {
    private final String a, b;
    private int aWins, bWins;

    public HeadToHeadRecord(String a, String b) { this.a = a; this.b = b; }

    public void addWin(String player) { if (player.equalsIgnoreCase(a)) aWins++; else if (player.equalsIgnoreCase(b)) bWins++; }

    public int getWins(String player) { return player.equalsIgnoreCase(a) ? aWins : (player.equalsIgnoreCase(b) ? bWins : 0); }
}