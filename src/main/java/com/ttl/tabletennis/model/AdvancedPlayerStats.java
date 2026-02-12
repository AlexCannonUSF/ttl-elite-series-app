package com.ttl.tabletennis.model;
public class AdvancedPlayerStats {
    public final int n,wins,losses,streak; public final boolean streakWin; public final double setDiffAvg;
    public AdvancedPlayerStats(int n,int wins,int losses,double setDiffAvg,int streak,boolean streakWin){this.n=n;this.wins=wins;this.losses=losses;this.setDiffAvg=setDiffAvg;this.streak=streak;this.streakWin=streakWin;}
}