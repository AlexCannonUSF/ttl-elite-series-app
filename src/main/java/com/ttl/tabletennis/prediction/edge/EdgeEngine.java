package com.ttl.tabletennis.prediction.edge;

import com.ttl.tabletennis.prediction.devig.DeviggedMarket;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link Edge} from a model probability and a devigged market,
 * applying the §9.2 shrinkage rules:
 *
 * <ul>
 *   <li>Rater disagreement OR feature completeness &lt; 0.8 → shrink edge
 *       by 30 % ({@link #DQ_KEEP}).</li>
 *   <li>Uncertainty label {@code AMBIGUOUS} or {@code ANOMALOUS} → shrink
 *       edge by 50 % ({@link #UNCERTAINTY_KEEP}).</li>
 * </ul>
 *
 * <p>Shrinkers multiply: a request that trips both yields a 0.35×
 * factor. The applied shrinker list is exposed on {@link Edge} for audit.
 *
 * <p>{@code EdgeEngine} is pure logic and safe to call from request paths;
 * threshold-based bet/no-bet logic lives in the upcoming
 * {@code StakingPolicy} (Phase 06).
 */
@Service
public class EdgeEngine {

    public static final double DQ_KEEP = 0.7;            // 30 % shrink
    public static final double UNCERTAINTY_KEEP = 0.5;   // 50 % shrink
    public static final double FEATURE_COMPLETENESS_FLOOR = 0.8;

    public static final String SHRINK_RATER_DISAGREEMENT = "dq.rater_disagreement";
    public static final String SHRINK_FEATURE_COMPLETENESS = "dq.feature_completeness_low";
    public static final String SHRINK_UNCERTAINTY_AMBIGUOUS = "uncertainty.ambiguous";
    public static final String SHRINK_UNCERTAINTY_ANOMALOUS = "uncertainty.anomalous";

    static final String LABEL_AMBIGUOUS = "AMBIGUOUS";
    static final String LABEL_ANOMALOUS = "ANOMALOUS";

    public Edge compute(double pModelTop,
                        DeviggedMarket market,
                        DataQualitySignals dq,
                        String uncertaintyLabel) {
        if (market == null) {
            throw new IllegalArgumentException("market must not be null");
        }
        if (Double.isNaN(pModelTop) || pModelTop < 0.0 || pModelTop > 1.0) {
            throw new IllegalArgumentException("pModelTop must lie in [0, 1]; was " + pModelTop);
        }
        DataQualitySignals signals = dq == null ? DataQualitySignals.clean() : dq;
        String label = uncertaintyLabel == null ? "UNKNOWN" : uncertaintyLabel.trim().toUpperCase();

        double pModelBot = 1.0 - pModelTop;
        double pFairTop = market.pConsensusTop();
        double pFairBot = market.pConsensusBot();
        double rawEdgeTop = pModelTop - pFairTop;
        double rawEdgeBot = pModelBot - pFairBot;

        List<String> applied = new ArrayList<>();
        double shrink = 1.0;

        boolean dqStrike = signals.raterDisagreement()
                || signals.featureCompleteness() < FEATURE_COMPLETENESS_FLOOR;
        if (dqStrike) {
            shrink *= DQ_KEEP;
            if (signals.raterDisagreement()) {
                applied.add(SHRINK_RATER_DISAGREEMENT);
            }
            if (signals.featureCompleteness() < FEATURE_COMPLETENESS_FLOOR) {
                applied.add(SHRINK_FEATURE_COMPLETENESS);
            }
        }

        if (LABEL_AMBIGUOUS.equals(label)) {
            shrink *= UNCERTAINTY_KEEP;
            applied.add(SHRINK_UNCERTAINTY_AMBIGUOUS);
        } else if (LABEL_ANOMALOUS.equals(label)) {
            shrink *= UNCERTAINTY_KEEP;
            applied.add(SHRINK_UNCERTAINTY_ANOMALOUS);
        }

        double edgeTop = rawEdgeTop * shrink;
        double edgeBot = rawEdgeBot * shrink;

        return new Edge(
                pModelTop,
                pModelBot,
                pFairTop,
                pFairBot,
                rawEdgeTop,
                rawEdgeBot,
                edgeTop,
                edgeBot,
                shrink,
                applied,
                label
        );
    }
}
