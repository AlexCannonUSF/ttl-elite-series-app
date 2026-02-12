package com.ttl.tabletennis.analytics;

import com.ttl.tabletennis.model.HeadToHeadRecord;

import java.util.HashMap;
import java.util.Map;

/** Logistic model over (EloDiff, H2H, FormIndexDiff). */
public class MatchPredictor {
    private final EloRatingSystem elo;
    private final Map<String, Double> formIndex = new HashMap<>();
    // Coefs calibrated conservatively for table tennis; tweak later if you fit on data:
    private double b0 = 0.0, bElo = 1.0 / 400.0, bH2H = 0.1, bForm = 0.005;

    public MatchPredictor(EloRatingSystem elo) { this.elo = elo; }

    public double probA(String a, String b, HeadToHeadRecord h2h) {
        double eloDiff = elo.get(a) - elo.get(b);
        double h2hDiff = (h2h == null) ? 0.0 : (h2h.getWins(a) - h2h.getWins(b));
        double formDiff = formIndex.getOrDefault(a, 0.0) - formIndex.getOrDefault(b, 0.0);
        double z = b0 + bElo * eloDiff + bH2H * h2hDiff + bForm * formDiff;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** result: +1=win, 0=loss; exponential smoothing */
    public void updateForm(String playerKey, double result) {
        double prev = formIndex.getOrDefault(playerKey, 0.0);
        double alpha = 0.25;
        formIndex.put(playerKey, alpha * result + (1 - alpha) * prev);
    }
}