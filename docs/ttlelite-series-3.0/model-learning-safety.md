# Model learning and calibration safety

Updated: 2026-07-29

## Operating rule

Live sessions are measurement windows, not online-training batches. The
application records predictions, prices, model-factor contributions, score
states, placement phase, settlement provenance, and eventual results during a
session. Production probabilities, thresholds, and stakes do not change during
that same session.

Calibration and regime adjustments remain shadow-only until a completed
out-of-sample review promotes them deliberately:

- `ttl.prediction.liveLearningApplyEnabled=false`
- `ttl.prediction.regimeLearningApplyEnabled=false`
- `ttl.paper.adaptive.applyEnabled=false`

The observed profiles and audit statistics remain available while application
is disabled, so an operator can see what a candidate calibration would have
done without letting it affect a pick.

## Eligible outcome truth

Only a binary win/loss with valid player and selected-side identities can
become calibration truth. The settlement must also meet the confidence floor:

- official result: `1.00`
- database result linked to a result match: `0.96`
- database result without a linked result: `0.82`, telemetry only
- decisive live/targeted completion: at least `0.88`, eligible only at `0.90+`
- heuristic last-score/near-finish inference: `0.45–0.70`, telemetry only

Provisional score-leader guesses never settle bets and never train the model.
They are resolved later against trusted outcomes so each score rule gets its
own accuracy and calibration record.

## Minimum evidence before promotion

The production defaults require at least 100 raw eligible decisions and 50
effective decisions for global live calibration. Trigger adaptations require
50 decisions per trigger. Effective sample size is the Kish value after
recency and settlement-confidence weighting; raw duplicates or old evidence
cannot satisfy the gate by count alone.

Any candidate must be evaluated on a later, untouched time window. Promotion
requires all of the following:

1. lower out-of-sample Brier score and log loss;
2. calibration error closer to zero, including favorite, balanced, underdog,
   prematch, and live phase segments;
3. non-negative true closing-line value with useful closing-price coverage;
4. no material degradation in the weakest trigger or price regime;
5. stable results under bootstrap/time-slice confidence intervals;
6. an explicit config promotion followed by monitoring, never an automatic
   in-session weight update.

Adaptive behavior is conservative-only: it may raise an edge threshold, add a
selection penalty, shrink probability confidence, or reduce stake. It may not
manufacture positive edge, lower a threshold after a short winning streak, or
increase stake above the policy result.

## Data and runtime safeguards

- Match event/placement time—not late settlement time—drives recency weighting.
- Near-zero-variance training features are neutralized; standardized values are
  clipped to prevent out-of-distribution logit explosions.
- Elo, TrueSkill2, Weng-Lin, rater-ensemble, consensus, form, H2H, fatigue, and
  other contributions are persisted for factor-level outcome analysis.
- Real closing lines use the same `P1`/`P2` orientation as stored snapshots.
  Rows without a close are excluded from CLV and counted as missing coverage.
- Recommendations pause when completed-match history is older than 14 days.
- Live paper placement is disabled by default until live-phase evidence has
  passed the same out-of-sample promotion process.
- A JVM sync guard coalesces repeated clicks; a database write lock and unique
  session/dedupe key protect against cross-process races.
- Session totals are a cache. Every sync reconstructs bankroll, stake, return,
  P&L, and W/L/push counts from the bet ledger.

## Audit endpoint

`GET /api/v3/ml/learning-audit?windowDays=180`

The report exposes:

- trusted-label coverage and exclusions;
- raw and effective sample size;
- Brier score, log loss, observed win rate, and calibration error;
- trigger and favorite/balanced/underdog performance;
- factor directional accuracy and contribution behavior on wins/losses;
- provisional score-rule accuracy versus stated confidence;
- true closing-line coverage and stake-weighted CLV.

Zero eligible samples is a valid and important state: it means the application
must collect better result provenance before any calibration can be promoted.
