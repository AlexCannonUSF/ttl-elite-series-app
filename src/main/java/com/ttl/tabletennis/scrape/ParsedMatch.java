package com.ttl.tabletennis.scrape;

import java.util.Objects;

/** Simple DTO representing one parsed head-to-head result line. */
public class ParsedMatch {

    public enum Type { NORMAL, WALKOVER }

    private final String player1;
    private final String player2;
    private final int score1;
    private final int score2;
    private final String rawLine; // normalized raw text we matched on
    private final Type type;

    public ParsedMatch(String player1, String player2, int score1, int score2, String rawLine, Type type) {
        this.player1 = player1;
        this.player2 = player2;
        this.score1 = score1;
        this.score2 = score2;
        this.rawLine = rawLine;
        this.type = type;
    }

    public String getPlayer1() { return player1; }
    public String getPlayer2() { return player2; }
    public int getScore1() { return score1; }
    public int getScore2() { return score2; }
    public String getRawLine() { return rawLine; }
    public Type getType() { return type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParsedMatch that)) return false;
        return Objects.equals(rawLine, that.rawLine);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawLine);
    }

    @Override
    public String toString() {
        return "ParsedMatch{" +
                "player1='" + player1 + '\'' +
                ", player2='" + player2 + '\'' +
                ", score1=" + score1 +
                ", score2=" + score2 +
                ", type=" + type +
                ", rawLine='" + rawLine + '\'' +
                '}';
    }
}