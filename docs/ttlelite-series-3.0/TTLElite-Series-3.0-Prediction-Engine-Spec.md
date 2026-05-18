# TTLElite Series 3.0 — Prediction Engine Spec
_Document v1.0 — companion to Master Plan §6.5_

## 0. Purpose
This spec defines the v3 prediction stack: what features we compute, which raters and models produce probabilities, how we calibrate and bound uncertainty, and how we translate probabilities into paper-trade decisions with correct staking, guardrails, and auditability. The end-state goal is simple: the engine must be **honest** about what it knows, **correctly staked** when it decides to act, and **auditable** end-to-end so that every paper-bet can be traced back to the exact feature vector, model version, and decision inputs that produced it.

This document replaces the 2.0 split between `PredictionModelService`, `FeatureService`, and `OddsValueEngineService` with a single composed stack behind a clean facade.

## 1. Design principles
- **Probability is a first-class contract.** Every producer returns a calibrated probability and a calibrated uncertainty, not a "score". Consumers must never re-interpret raw logit outputs.
- **Correlation-aware staking.** We never size on marginal edge alone; we size against the portfolio of open positions.
- **CLV over hit-rate.** Our primary KPI is closing-line value, not win percentage. A bet that moves to us is a good bet, even if it lost.
- **Walk-forward always.** No model ever sees future data. All fit/evaluate cycles are walk-forward with purged gaps.
- **Leakage is a bug.** Every feature has a provenance hash. Anything that could be observed after the bet was placed is rejected at feature-build time.
- **Shadow before live.** Any model change runs in shadow mode alongside production for ≥14 days before it can promote.

## 2. Top-level architecture
```
          +-----------------+
 inputs ->| FeatureBuilder  |---> feature vector (versioned)
          +-----------------+
                  |
                  v
   +----------+   +-----------+   +-------------+
   | Raters   |   | Markov    |   | GBT Blender |
   | Glicko2  |-> | point-by- |-> | (LightGBM)  |
   | TrueSkill|   | -point    |   | + isotonic  |
   +----------+   +-----------+   +-------------+
                                        |
                                        v
                                  +-------------+
                                  | Calibrator  |
                                  | (Platt +    |
                                  |  isotonic)  |
                                  +-------------+
                                        |
                                        v
                                  +--------------+
                                  | Conformal    |
                                  | predictor    |
                                  +--------------+
                                        |
                                        v
                     +------------------------------+
                     | DeviggingService             |
                     | (Shin / Power / multi)       |
                     +------------------------------+
                                        |
                                        v
                     +------------------------------+
                     | EdgeEngine (p_model - p_fair)|
                     +------------------------------+
                                        |
                                        v
                     +------------------------------+
                     | StakingPolicy (frac Kelly +  |
                     |  portfolio caps + corr caps) |
                     +------------------------------+
                                        |
                                        v
                                 PlacementService
```

## 3. Feature catalogue (v3)
Each feature has: `name`, `unit`, `source`, `max_age_ms`, `requires`, `leakage_notes`. Features live in `FeatureRegistry` (serialisable) and every feature vector is hashed with the registry version.

### 3.1 Identity and context
- `match.event_code`, `match.round`, `match.best_of`, `match.is_televised`, `match.is_major_event`, `match.table_number`, `match.day_of_week`, `match.minutes_to_start`, `match.is_backup_table` (derived).
- `player.top.id`, `player.bot.id`, `player.top.country`, `player.bot.country`.

### 3.2 Raters
- `rater.glicko.top.mu`, `rater.glicko.bot.mu`, `rater.glicko.top.phi`, `rater.glicko.bot.phi`, `rater.glicko.delta_mu`, `rater.glicko.delta_phi_sum`.
- `rater.ts2.top.mu`, `rater.ts2.bot.mu`, `rater.ts2.top.sigma`, `rater.ts2.bot.sigma`, `rater.ts2.skill_gap` (TrueSkill-2 per 4.2).
- `rater.wenglin.delta` (Weng-Lin Bayesian rating delta per 4.3).
- `rater.ensemble.delta` (weighted combination).

### 3.3 Recent form (windowed)
For each side `s ∈ {top, bot}` and window `w ∈ {5, 10, 25}`:
- `form.s.w.win_rate`, `form.s.w.dominance` (points_for / (points_for + points_against)), `form.s.w.straight_set_rate`, `form.s.w.median_margin`, `form.s.w.gap_to_prev_match_ms`.
- `form.s.w.completeness` — share of matches in the window where we had scored evidence (vs. only market-settled).

### 3.4 Head-to-head
- `h2h.count`, `h2h.win_rate_top`, `h2h.recency_decay_wins_top` (exponential λ=0.02/day), `h2h.median_margin_top`, `h2h.same_event_h2h_count`.

### 3.5 Surface, venue, schedule effects
- `ctx.same_day_prior_matches_top`, `ctx.same_day_prior_matches_bot`, `ctx.rest_gap_minutes_top`, `ctx.rest_gap_minutes_bot`, `ctx.travel_tz_delta_top`, `ctx.venue_familiarity_top`.

### 3.6 Market microstructure
- `odds.open.top.decimal`, `odds.open.bot.decimal`, `odds.latest.top.decimal`, `odds.latest.bot.decimal`.
- `odds.move.top_bps_since_open`, `odds.move.top_bps_last_5m`, `odds.overround_bps`, `odds.overround_bps_trend_1m`.
- `odds.dev.top.shin`, `odds.dev.bot.shin` (Shin-method devigged).
- `odds.dev.top.power`, `odds.dev.bot.power` (power-method).
- `odds.dev.top.multiplicative`, `odds.dev.bot.multiplicative`.
- `odds.dev.top.consensus` — median across the three methods.
- `odds.liquidity_proxy` — rolling count of price changes in last 60 s (fallback where real volume is unavailable).

### 3.7 Live-in-progress (when applicable)
- `live.games_top`, `live.games_bot`, `live.points_top`, `live.points_bot`, `live.server`, `live.is_deuce`, `live.point_differential`, `live.current_lead_top`, `live.time_since_last_point_s`.
- `live.win_prob_markov_current` (from §5) — fed back in as a self-feature only at the in-play decision point, never at pre-live.

### 3.8 Data-quality signals
- `dq.feed_ticks_1m.top`, `dq.feed_ticks_1m.bot`, `dq.mirror_disagreement_flag`, `dq.stream_cv_present`, `dq.player_canonicalised`.
- `dq.feature_completeness` — 0..1, share of non-null features used.

### 3.9 Leakage firewalls
- No post-match-end observation can appear in a pre-match or in-play vector.
- `form.*` windows exclude the current match.
- `h2h.*` excludes the current match.
- `odds.*` at decision time `t` is snapshotted at `t - ε` to avoid accidentally using a later tick.
- `FeatureBuilder` enforces a monotonic clock check at build time; any feature that fails gets zeroed and the vector is tagged `dq.feature_completeness` down.

### 3.10 Feature vector versioning
Every vector carries `{featureRegistryVersion, featureSchemaHash, builtAtUtc}`. `featureSchemaHash = sha256(sorted JSON of {name,unit,source,max_age_ms})`. Model artefacts refuse to score a vector whose schema hash differs from the one they trained on — **hard error**, not a warning.

## 4. Raters
### 4.1 Glicko2 (retained from v2, tuned)
- Implementation: online with `tau=0.5`, `q=0.0057565`.
- Ratings are per-player; update batches run at the end of each match via a domain event.
- Initial rating `1500`, RD `350`, volatility `0.06`.
- Cold-start: if `player.matches_total < 8`, mu is blended 50/50 with `event_tier_mean` until we have 8 matches.

### 4.2 TrueSkill-2 (new)
- Python-side model retrained nightly on the last 24 months. Why 2: exposes skill, consistency (σ), and skill growth, which Glicko2 doesn't separate.
- Configuration: `μ0=25, σ0=8.333, β=4.166, τ=0.0833, drawProb=0.0` (draws impossible in TT).
- We expose a Java shim `TrueSkill2Service` that reads ratings from a materialised table refreshed nightly — no online updates inside the request path.

### 4.3 Weng-Lin Bayesian (new)
- Implementation via the `openskill` Python package; we use the `PlackettLuce` model for flexibility when we later add doubles.
- Serves as a robustness check against TrueSkill-2; if the two disagree by >15 % on expected win probability, we flag the matchup as `dq.rater_disagreement=true` and shrink the edge (§9.2).

### 4.4 Rater ensemble
`rater.ensemble.delta = 0.45·Glicko2Delta + 0.35·TS2Delta + 0.20·WengLinDelta`. Weights are model-selection-tuned (§8) and pinned per release; any change is a versioned migration.

## 5. Point-by-point Markov chain
A full point-by-point Markov chain for 11-point TT simulates the distribution of `p(win_match | p_point_top, p_point_bot, server_start, games_state)`. Value:
- Converts a single per-point win probability `p` into match win, cover, total-points, and game-count distributions.
- Handles deuce correctly (tennis-style advantage until lead of 2).
- Handles server alternation and is best-of aware.

### 5.1 Point win probability estimation
`p_point_top` is estimated per matchup as a sigmoid of a linear blend of rater delta, surface features, and a server-effect term:
```
logit(p_point_top_on_serve)   = a0 + a1·rater.ensemble.delta + a2·form.top.5.dominance - a3·form.bot.5.dominance + a4·serve_bonus
logit(p_point_top_on_receive) = logit(p_point_top_on_serve) - 2·serve_bonus
```
Coefficients `a_i` are learned by maximum likelihood on point-level data from Stream-CV plus labelled TT-Series match logs; refit weekly.

### 5.2 Simulation
- Analytic closed form for best-of-3 and best-of-5 is preferred where tractable; otherwise 50k Monte Carlo trials per matchup (vectorised NumPy/JAX path in the Python microservice).
- Outputs: `p_match_top`, `p_3_0`, `p_3_1`, `p_3_2`, `exp_total_points`, `median_match_minutes`.
- In-play: conditional simulation from the current `(games, points, server)` state.

### 5.3 Consistency check
`p_match_top` from the Markov simulation is compared to the LightGBM blender's output (§6). If they disagree by > 0.05 on `|Δ|`, we log `prediction.disagreement` and downgrade the edge by 30 %.

## 6. LightGBM blender
### 6.1 Why a blender
Raters and the Markov model encode very different structural assumptions. A gradient-boosting blender learns a supervised combination of their outputs plus raw features, avoiding the brittleness of hand-tuned weights.

### 6.2 Inputs
The blender sees the full feature vector (§3) **plus** the Markov outputs (§5.2) and the rater deltas. Strictly no market features are fed at the "pre-market" variant (so we can compare against market as an adversary). The "post-market" variant does see devigged market probabilities as inputs.

### 6.3 Configuration
- LightGBM classifier, binary log-loss.
- Depth-aware: `max_depth=-1`, `num_leaves=63`, `min_data_in_leaf=200`, `feature_fraction=0.75`, `bagging_fraction=0.75`, `bagging_freq=5`, `learning_rate=0.03`, `num_iterations=1500` with early stopping on walk-forward validation loss.
- Categorical encoding via native LightGBM categorical indices; target encoding is forbidden (leakage risk in walk-forward folds).
- Two variants trained and both retained:
  - **Variant A — No-market**: only §3.1–§3.8 features.
  - **Variant B — With-market**: additionally §3.6 devigged odds.
- In production, we use Variant A for edge detection and Variant B as a sanity check (§9.3).

### 6.4 Retraining
- Nightly walk-forward refit on rolling 12-month training + 2-week validation + 2-week test slice.
- Purged k-fold with 4-hour gap to remove adjacency leakage.
- Model card (§10) regenerated automatically; any material metric regression blocks promotion.

## 7. Calibration
### 7.1 Why two-stage
Raw LightGBM probabilities are often over-confident at the extremes and miscalibrated in the middle. A two-stage pipeline handles both.

### 7.2 Stage 1 — Platt scaling
Fit a logistic regression on logit(model_prob) → label, computed on the validation slice (purged).

### 7.3 Stage 2 — Isotonic regression
Fit an isotonic regression on the Platt outputs → label over a held-out calibration slice. Monotone, non-parametric, handles residual bumps.

### 7.4 Temperature scaling (optional regime override)
Per regime (`event_tier`, `best_of`, `is_in_play`) we can scale logits by a single learned temperature when subgroup-level miscalibration is detected by reliability diagrams.

### 7.5 Acceptance gates (pre-promotion)
- ECE (expected calibration error, 15-bin, equal-mass) ≤ 0.02 on the test slice.
- Maximum bin deviation ≤ 0.04.
- Brier skill score vs. devigged market ≥ 0 (we at least match the market's calibration).
- Reliability diagram stored as an artefact; no bin's empirical frequency is more than 2σ off the nominal.

## 8. Uncertainty — Split & Mondrian conformal predictor
### 8.1 Why conformal
A single probability hides the fact that some matchups are simply less predictable. Conformal prediction converts a miscalibrated probability into **valid coverage** for a chosen confidence level, subject to exchangeability.

### 8.2 Split conformal
- Non-conformity score `s(x, y) = 1 - p_hat(y | x)`.
- Calibration set is the purged validation slice (after calibrator).
- For target miscoverage `α=0.1` we compute `q̂ = ceil((n+1)(1-α))/n` quantile of calibration scores.
- At prediction time, prediction set is `{y : s(x, y) ≤ q̂}`. If `{top, bot}` both in set → `uncertainty.label=AMBIGUOUS`; if exactly one → `CONFIDENT`; if none → `ANOMALOUS` (rare — typically means features are outside the training manifold).

### 8.3 Mondrian groups
To avoid averaging coverage across heterogeneous subpopulations, we condition on `(best_of, is_in_play, is_major_event)` so that the calibration quantile is local to the subgroup.

### 8.4 Output contract
`uncertainty = {coverage: 0.9, label, intervalLow, intervalHigh, method: "mondrian-split", alpha}` lives on the `Prediction` object alongside `p_top`.

## 9. Edge, devigging, and staking
### 9.1 Devigging
For every 2-way market we compute three devigged probabilities:
- **Shin's method**: iteratively solves for `z` such that the Shin-adjusted fair prob matches the observed odds. Works well when overround is mostly insider information.
- **Power method**: `p_dev = p_raw^k`, `k` chosen so sum equals 1. Works well for market with uniform overround.
- **Multiplicative**: `p_dev = p_raw / sum(p_raw)`. Baseline.

`p_fair = median(p_shin, p_power, p_mult)`. Store all three on the tick for audit.

### 9.2 Edge
`edge_top = p_model_top - p_fair_top`, `edge_bot = p_model_bot - p_fair_bot`.
Under `dq.rater_disagreement == true` or `dq.feature_completeness < 0.8`, shrink edge by 30 %.
Under `uncertainty.label == "AMBIGUOUS"` or `ANOMALOUS`, shrink by 50 %.

### 9.3 Minimum edge threshold
Default minimum `edge ≥ 0.025` (2.5 %) at decimal odds 1.8–2.2; scaled by `max(1, odds_decimal - 1)` so that higher-odds selections need more edge before qualifying. These thresholds are recorded in `policy.yaml` (§11.2) and are version-controlled.

### 9.4 Fractional Kelly staking
```
kelly_fraction = (b·p - q) / b
              where b = decimal_odds - 1, p = p_model, q = 1 - p
stake_units = max(0, min(kelly_cap, fractionalKellyFactor · kelly_fraction)) · bankroll_units
```
- `fractionalKellyFactor = 0.25` (quarter-Kelly) by default.
- `kelly_cap` per bet = 1.5 units (1 unit = 1 % of paper bankroll).

### 9.5 Portfolio and correlation caps
- Max total exposure: 5 units open at any moment.
- Max per-event exposure: 2 units.
- Max per-player exposure (top or bot): 1.5 units — if the same player appears across 3 matches in the same day, we treat them as correlated and cap aggregate exposure there.
- Correlated-opposite check: do not stake both sides of the same match, obviously — the EdgeEngine returns at most one side. In Phase 08 we may allow simultaneous live+pre-live on the same match with opposite directions only if the models disagree and the correlation penalty is applied.
- Same-session drawdown stop: if rolling 50-bet session ROI < −8 %, staking factor halves until either 20 more paper-bets accumulate or operator overrides.

### 9.6 "No bet" is a first-class answer
If after all shrinkage the stake is `< 0.1` units, the Decision is `NO_BET` with a reason code — we explicitly record "why no bet" for audit.

## 10. Decision contract
```java
public record Prediction(
    String predictionId,             // uuidv7
    String matchId,
    String featureVectorHash,        // sha256 of canonical JSON
    String modelVersion,             // e.g., "v3.0.2"
    String calibratorVersion,        // "platt+iso-v3.0.2"
    String conformalVersion,         // "mondrian-split-v3.0.2"
    Probability pTop,                // {value, interval, ece, method}
    Probability pBot,
    DeviggedMarket market,           // {pShin, pPower, pMult, consensus}
    Edge edge,                       // {edgeTop, edgeBot, shrinkFactors}
    Uncertainty uncertainty,
    ModelCardRef card,               // git SHA + training cohort hash
    Instant computedAtUtc,
    Instant decisionAtUtc,
    Map<String, Double> featureContributions  // SHAP top-K for audit
) {}

public sealed interface Decision {
    record Bet(String side, BigDecimal oddsDecimal, BigDecimal stakeUnits,
               BigDecimal kellyRaw, BigDecimal kellyUsed,
               List<String> reasons, Prediction prediction) implements Decision {}
    record NoBet(List<String> reasons, Prediction prediction) implements Decision {}
}
```
Every paper-trade placed carries its `Prediction.predictionId`. We can reconstruct the exact decision from immutable artefacts.

## 11. Configuration
### 11.1 Model artefacts
Stored in the registry under `models/prediction/{version}/` with:
- `blender.lgb.model` (LightGBM binary).
- `platt.json`, `isotonic.json`, `conformal.json`.
- `feature_registry.json` with schema hash.
- `model_card.md` — training cohort, metrics, caveats.
- `promotion_record.yaml` — who promoted, when, A/B evidence link.

### 11.2 `policy.yaml`
```yaml
prediction:
  minimumEdge:
    default: 0.025
    ladder:
      "1.50-1.79": 0.035
      "1.80-2.20": 0.025
      "2.21-2.80": 0.035
      ">2.80":     0.060
  shrink:
    raterDisagreement: 0.30
    uncertaintyAmbiguous: 0.50
    uncertaintyAnomalous: 1.00
    featureCompletenessBelow:
      threshold: 0.80
      factor:    0.30
  staking:
    fractionalKelly: 0.25
    kellyCapUnits: 1.5
    perMatchCapUnits: 2.0
    perPlayerDailyCapUnits: 1.5
    maxOpenExposureUnits: 5.0
    sessionDrawdownStop:
      lookbackBets: 50
      triggerRoi:  -0.08
      factor:      0.50
```
The file is reloaded at runtime via `@RefreshScope`-equivalent. Every reload emits an audit entry including the diff and who triggered it.

## 12. Walk-forward evaluation protocol
1. Partition data by `matchStartUtc` into chronological folds with 2-week windows.
2. For each fold index `i`:
   - Train on folds `[i-26, i-2]` (12 months).
   - Validate on `[i-1]` (2 weeks).
   - Calibrate on the validation slice (Platt + isotonic + conformal).
   - Test on fold `i` (2 weeks).
   - Purge: exclude any training match within 4 hours of any validation/test match.
3. Aggregate metrics weighted by fold volume.
4. Report: log-loss, Brier score, ECE, MCE, Brier skill score vs. market, ROC-AUC, PR-AUC, top-decile precision, distribution of edge, CLV.
5. **Regime-split report**: per `(event_tier, best_of, is_in_play)` so we detect subpopulation regressions.

## 13. Model governance and promotion
### 13.1 Promotion gates
- All acceptance gates in §7.5 pass on the test fold.
- CLV on the simulated decision stream is ≥ CLV of the production model on the same horizon with p < 0.05 (paired bootstrap, 1000 resamples).
- ROI on the simulated decision stream is within ±2 % of the production model (we don't promote a strictly-more-aggressive model just for ROI).
- Variant A vs Variant B agreement: mean absolute difference ≤ 0.04; larger divergences require manual review.

### 13.2 Shadow mode
- 14-day minimum shadow window in which the candidate model produces predictions but no bets. Comparison dashboards show Brier, CLV, edge distributions overlaid with production.
- Any red alert (e.g., calibration drift, disagreement spike) resets the shadow window.

### 13.3 Rollback
Every promotion writes a rollback script. Rollback is a single config flip plus a 60-second warmup; no database migration is required because artefacts are append-only.

### 13.4 Model card (required)
- Training cohort (time range, row counts, player counts).
- Features used (with schema hash).
- Known failure modes (e.g., amateur minor-league matches).
- Subgroup metrics.
- Intended use + not-intended-use statement.

## 14. Services and class decomposition
The existing `PredictionModelService` is split:
- `RatingsService` — Glicko2 updates and reads.
- `TrueSkill2Service` — TrueSkill-2 read side.
- `WengLinService` — Weng-Lin read side.
- `FeatureBuilder` — builds & hashes vectors; enforces leakage firewalls.
- `MarkovSimulator` — Java orchestrator calling the Python microservice (§15).
- `BlenderClient` — gRPC/REST client to the Python LightGBM service.
- `CalibratorClient` — Platt + isotonic application (pure Java implementation, no Python call in the hot path).
- `ConformalPredictor` — Java implementation using precomputed Mondrian quantiles.
- `DeviggingService` — Shin, power, multiplicative.
- `EdgeEngine` — combines `Prediction` + devigged market → `Edge`.
- `StakingPolicy` — fractional Kelly with caps.
- `PredictionFacade` — single entry point used by `PlacementService`.

Each service is individually unit-tested. `PredictionFacade` is the only class `PlacementService` imports.

## 15. Python microservice (`ttl-predict-py`)
- FastAPI app exposing:
  - `POST /v1/blend` → blender scoring.
  - `POST /v1/markov` → Markov sim.
  - `POST /v1/retrain` → scheduled job trigger.
  - `GET /v1/health`, `GET /v1/metrics`.
- Stateless except for loaded model artefacts. Artefacts are loaded from a local model-cache dir populated by CI.
- Concurrency: `uvicorn` + `gunicorn` workers, NumPy/LightGBM CPU-bound. Prometheus instrumentation via `prometheus_fastapi_instrumentator`.
- JVM side calls this service via Resilience4j CircuitBreaker with a 150 ms p99 budget per call (batched where possible).

## 16. Observability
### 16.1 Prometheus metrics
- `prediction_feature_build_ms` — histogram.
- `prediction_score_ms{stage}` — histogram (raters, markov, blend, calib, conformal, deviggin, edge, stake).
- `prediction_edge_distribution` — histogram.
- `prediction_ece{regime}` — gauge (daily eval).
- `prediction_drift_psi{feature}` — gauge (PSI ≥ 0.2 → alert).
- `prediction_clv_1d{bucket}` — gauge (decision-time vs. closing price).

### 16.2 Alerts
- `FeatureCompletenessLow` — p50 of `dq.feature_completeness` < 0.7 for 30 min.
- `ECEBreach` — ECE > 0.03 on any Mondrian subgroup for 1 day.
- `EdgeDistributionAnomaly` — KS-test p < 0.01 vs. prior 7-day distribution.
- `PredictionLatencyP99High` — `prediction_score_ms{stage="total"}` p99 > 250 ms.
- `CLVNegative7Day` — 7-day CLV < 0.

### 16.3 Dashboards
- **Reliability**: current reliability diagram, ECE, MCE, sub-regime table.
- **Edge & staking**: edge distribution, Kelly fraction used, bets per hour, exposure utilisation.
- **Feature drift**: PSI heatmap, feature completeness over time.
- **Model lineage**: side-by-side comparison of live model vs. shadow model.

## 17. Testing strategy
### 17.1 Unit
- Each rater, devigger, Markov transition, and calibrator has a unit test with golden numeric fixtures.
- FeatureBuilder property-based tests ensure leakage firewalls (generate random timelines, assert no feature's `observedAt` > `decisionAt`).

### 17.2 Integration
- `PredictionFacadeIT` replays a full day of 2.0 predictions and verifies the 3.0 facade produces the same decision-structure shape on an identical cohort (shadow-mode contract).

### 17.3 Walk-forward
- CI job `predict-walkforward-nightly` runs on the last 60 days; promotion is forbidden unless all §13.1 gates pass.

### 17.4 Shadow
- Production deploys shadow-mode inherently; shadow dashboards are part of the acceptance definition for every release.

### 17.5 Adversarial tests
- Back-test 2.0 models on 3.0 features — we expect strict improvement; if not, we've regressed somewhere and block promotion.
- "Market-beating" test — compare Brier to devigged market. BSS ≥ 0 required.
- "Paper-bankroll ruin" test — 10k bootstrap runs through the simulated decision stream must have P(bankroll < 50 % starting) ≤ 5 %.

## 18. Migration plan (from 2.0)
- **Phase 00**: `PredictionFacade` wraps existing `PredictionModelService` → no behaviour change.
- **Phase 01**: add `TrueSkill2Service`, `WengLinService`, `FeatureBuilder` v3 (still feeding the 2.0 blender in parallel — shadow logs).
- **Phase 02**: switch to LightGBM blender Variant A, calibrator and conformal — behind a feature flag, 5 % traffic shadow.
- **Phase 03**: enable `DeviggingService` with Shin+Power+Mult fusion; new policy.yaml live.
- **Phase 04**: StakingPolicy v3 with portfolio/correlation caps; legacy `fractionalKellyFactor` knob deprecated.
- **Phase 05**: Markov simulator live; blender variant B activated as sanity check.
- **Phase 06**: 50 % shadow → 100 % shadow → promote if gates green.
- **Phase 07**: retire 2.0 `PredictionModelService` code.
- **Phase 08**: begin collecting proxy-liquidity features from §3.6 for the v3.1 blender.

## 19. Risks and mitigations
- **Overfitting**: walk-forward + purge + early stopping + model card + reliability gates.
- **Leakage**: feature firewalls + registry schema hash check + unit tests that generate leakage scenarios and assert rejection.
- **Concept drift**: nightly retrain, PSI alerts, Brier skill score vs. market.
- **Market-dependent feedback loops**: Variant A (no market) is the production edge model; Variant B is only a sanity check — we never feed our own predictions back as features except as in-play state.
- **Kelly ruin**: fractional Kelly + caps + drawdown stop + bootstrap ruin test.
- **Silent regressions**: dashboards + shadow mode + forced promotion gates.

## 20. Definition of done
- v3.0.0 goes out with Variant A in production, shadow of Variant B, calibration passing, CLV positive over the last 7 days of shadow, no feature completeness warnings on majority of matches, and a signed model card committed to the repo.

---
*End of Prediction Engine Spec v1.0.*
