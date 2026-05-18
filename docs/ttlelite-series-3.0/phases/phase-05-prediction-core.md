# Phase 05 — Prediction Stack Core + Markov Simulator
_Target duration: 2 weeks · Blast radius: medium (shadow only) · Reversibility: flag-off_

## Purpose
Complete the prediction stack: ship TrueSkill-2 and Weng-Lin raters, the Markov simulator, the devigging service, the conformal predictor, and Blender Variant B with market features. All of this runs in shadow; production still uses 2.0 predictions for real bets until Phase 06.

## Entry criteria
- Phase 04 soak passed.
- Blender Variant A stable in shadow for ≥ 7 days with non-negative BSS.
- At least 90 days of usable training data.

## Deliverables
1. `TrueSkill2Service` + `WengLinService` — nightly refresh jobs; Java readers.
2. Rater ensemble delta (`rater.ensemble.delta`) used in features.
3. `MarkovSimulator` in Python with closed-form paths for best-of-3 and best-of-5 and MC fallback for larger; JVM orchestrator with Resilience4j.
4. `DeviggingService` (Shin + Power + Multiplicative) + unit tests against golden fixtures.
5. `EdgeEngine` (Prediction + devigged market → Edge).
6. `ConformalPredictor` (Mondrian split conformal) in Java using precomputed quantiles.
7. Blender Variant B trained and shadowed; comparisons in `/v3/ml/quality/ab`.
8. `/v3/match/:id` prediction panel with reliability curve, SHAP top-K, conformal intervals.
9. `/v3/ml/quality` dashboards for reliability, drift (PSI), and live/shadow A/B.

## Work breakdown
- TrueSkill-2 and Weng-Lin refresh jobs run at 03:00 UTC; they write to `player_rating_ts2` and `player_rating_wl` tables with per-night snapshots for audit.
- `MarkovSimulator` exposes `/v1/markov` returning `{p_match, p_3_0, p_3_1, p_3_2, exp_total_points, median_match_minutes}` for a given feature bundle.
- `DeviggingService` includes invariant tests asserting that devigged probabilities sum to 1 ± 1e-9 for 10,000 random fixtures.
- `ConformalPredictor` loads the latest Mondrian quantiles from the model artefact bundle on startup; no Python calls in the hot path.
- `/v3/match/:id` renders SHAP top-K contributions (from the blender artefact) beside the reliability curve; hovering a SHAP bar explains the feature in plain English via a tooltip catalogue maintained in `web-v3/src/content/features.ts`.

## Exit criteria
- Release Gate Checklist §7 fully ticked.
- 7-day shadow with Variant A BSS ≥ production baseline and Variant B non-inferior.
- Conformal coverage empirical within [0.87, 0.93] at α=0.1.
- Markov p99 latency < 80 ms.

## Risks
- **Rater disagreement spike flags everything.** Mitigation: threshold at 6 % of matchups; alert triggers a retrain, not an outage.
- **Markov simulator under-performs on deuce-heavy matches.** Mitigation: per-state MC trials with ≥ 50k samples; fall back to observed empirical P(win|state) if MC fails a self-consistency check.
- **Blender B becomes market-dependent and loses discriminative edge.** Mitigation: Variant A remains the production edge detector; B is a sanity check only.

## Rollback
- Flag `features.predict-v3=off` reverts to 2.0 predictions.
- Individual raters can be disabled by zero-weighting in the ensemble config.

## Operator runbook
- `./scripts/deploy-phase-05.sh staging`
- `./scripts/rater-refresh.sh --all` — runs TS2 + WL jobs on demand.
- `./scripts/predict-conformal-coverage.sh --days 30` — verifies coverage.
- `./scripts/ml-ab-report.sh --days 7` — generates Variant A vs. B tables.
- `./scripts/rollback-phase-05.sh staging`
