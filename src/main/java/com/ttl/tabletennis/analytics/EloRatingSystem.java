package com.ttl.tabletennis.analytics;

import java.util.HashMap;
import java.util.Map;

public class EloRatingSystem {
    private final Map<String, Double> ratings = new HashMap<>();
    private final double K;

    public EloRatingSystem(double kFactor) { this.K = kFactor; }

    public double get(String playerKey) { return ratings.getOrDefault(playerKey, 1500.0); }

    public void set(String playerKey, double value) { ratings.put(playerKey, value); }

    public void update(String winnerKey, String loserKey) {
        double Ra = get(winnerKey), Rb = get(loserKey);
        double Ea = 1.0 / (1.0 + Math.pow(10.0, (Rb - Ra) / 400.0));
        double Eb = 1.0 - Ea;
        set(winnerKey, Ra + K * (1 - Ea));
        set(loserKey , Rb + K * (0 - Eb));
    }
}