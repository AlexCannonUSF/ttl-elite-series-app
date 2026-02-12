package com.ttl.tabletennis.analytics;

import com.ttl.tabletennis.model.MatchOdds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RecommendationEngine {

    public static final class Rec {
        public final String player; public final MatchOdds odds;
        public final double modelProb; public final double edge;
        public Rec(String player, MatchOdds odds, double modelProb, double edge) {
            this.player = player; this.odds = odds; this.modelProb = modelProb; this.edge = edge;
        }
    }

    public List<Rec> generate(List<MatchOdds> odds, MatchPredictor predictor) {
        List<Rec> out = new ArrayList<>();
        for (MatchOdds o : odds) {
            double pA = predictor.probA(o.getPlayerA(), o.getPlayerB(), null);
            double pB = 1 - pA;
            double impA = 1.0 / o.getOddsA();
            double impB = 1.0 / o.getOddsB();
            double eA = pA - impA, eB = pB - impB;
            if (eA > 0.05) out.add(new Rec(o.getPlayerA(), o, pA, eA));
            if (eB > 0.05) out.add(new Rec(o.getPlayerB(), o, pB, eB));
        }
        out.sort(Comparator.comparingDouble(r -> -r.edge));
        return out;
    }
}