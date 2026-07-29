# TTLElite Series 3.0 - Phase 05 Closeout

_Status: implementation complete; production rollout pending model artefacts and integrated facade; updated on 2026-05-18_

Phase 05 builds the prediction stack core. Where Phase 04 shipped the durable plumbing (Redis bus, MinIO raw store, VLM budget, shadow path, calibrators on disk), Phase 05 lands the algorithmic core that consumes that plumbing: ratings, the Markov simulator, the Variant B sanity blender, devigging, edge computation, conformal prediction, and the operator surfaces that make the new model honest about itself.

The implementation checklist is complete. Production authority is not yet granted: the full v3 `Prediction` facade still needs to integrate the new pieces end-to-end, and the §9.3 Variant A vs Variant B agreement gate needs live-traffic evidence before Phase 06 can promote the v3 stack from shadow.

## Shipped Capabilities

### TrueSkill-2 and Weng-Lin ratings

- Nightly TrueSkill-2 retraining lands ratings into `player_rating_ts2` for fast Java reads via `TrueSkill2Service`.
- Weng-Lin (`openskill` `PlackettLuce`) lands into `player_rating_wl` and is exposed by `WengLinService`.
- The Phase 04 model-artefact conventions apply to both rater outputs; tests assert the readers respect the spec's cold-start blend.

### Rater ensemble

- `RaterEnsemble` pins the Spec §4.4 combination: `0.45·Glicko2Delta + 0.35·TS2Delta + 0.20·WengLinDelta`.
- `MatchupFeatureVectorDto` exposes `raterEnsembleDelta` alongside the per-rater fields so v2 and v3 paths share the same feature surface.
- `PredictionFacade` forwards the v3 rater payload (including `rater.ensemble.delta`) into shadow blender calls so Phase 04's diff log gets meaningful inputs.

### Markov simulator

- `ttl-predict-py` `/v1/markov` ships the closed-form best-of-3 and best-of-5 chains for `(p_point_top_on_serve, p_point_top_on_receive)` plus a deterministic 50 000-trial Monte Carlo fallback for higher best-of values.
- The JVM `MarkovSimulator` orchestrator wraps the Python service with retry, timeout, and an offline closed-form fallback when the service is unreachable.
- Markov returns the full §5.2 envelope (`p_match_top`, `p_3_0`, `p_3_1`, `p_3_2`, `exp_total_points`, `median_match_minutes`).

### Variant B (with-market) blender

- The Python training harness already supported `--variant b`. Phase 05 ships the `models/prediction/variant-b-v3.0.0/` model-slot template and wires `BlenderService` to load both Variant A (primary, no-market) and Variant B (sanity, with-market) when both artefact directories exist.
- `/v1/blend` returns a top-level `sanity` block carrying Variant B's `pTop`, model identifiers, and `absoluteDiffPTop`. The Java `HttpBlenderClient` parses the block.
- `prediction_diff_log` gained three columns (`v3_variant_b_model_version`, `v3_variant_b_p1_probability`, `variant_ab_abs_diff`) so the §9.3 agreement gate (`mean |Δp_top| ≤ 0.04`) is a SQL query, not a fresh ETL.

### Devigging service

- `DeviggingService` (Java `@Service`) implements all three Spec §9.1 methods: multiplicative (closed form), power (bisects `k ∈ [1e-9, 50]` on `Σπ_i^k = 1`), and Shin (bisects `z ∈ [0, 1)` on the §8.2 derivation). The per-side median consensus and the solver parameters (`shinZ`, `powerK`) ride on `DeviggedMarket` for audit.
- Fixture tests cover the fair market, symmetric overround, skewed favorite/dog, heavy overround, the median identity, and reject odds ≤ 1.0.

### Edge engine

- `EdgeEngine.compute(pModelTop, deviggedMarket, dq, uncertaintyLabel)` produces an `Edge` record carrying raw + shrunken edges, the final shrink factor, and the audit list of which shrinkers fired. Implements §9.2 exactly: 30% shrink on rater disagreement or feature completeness below 0.8, 50% shrink on `AMBIGUOUS` / `ANOMALOUS`. Both DQ flags being true counts as one 30% shrink with both reasons logged.
- The engine is stateless and threshold-free; the `StakingPolicy` (Phase 06) reads the produced edges and applies `policy.yaml` thresholds for bet/no-bet decisions.

### Conformal predictor

- `ConformalPredictor` (Java) ships in-JVM fit + apply for Mondrian split conformal. `fit(samples, alpha, minGroupSize, version)` returns a `MondrianSplitConformal` byte-for-byte compatible with the Python writer; `predict(model, pTop, key)` returns a `ConformalResult` carrying the explicit prediction set (`{TOP}`, `{BOT}`, `{TOP, BOT}`, or empty) alongside the §8.4 uncertainty envelope.
- The empirical-coverage test confirms that the predictor delivers the advertised `1 - α` coverage on a well-calibrated self-fit calibration set — `splitQuantile` uses the spec's `ceil((n+1)(1-α))/n`-th order statistic.

### Prediction panel page

- `/v3/matches/:id/prediction` renders four sections from one round-trip: probability with model + conformal interval bands, the conformal envelope card (label, coverage, α, q̂, group key, prediction set), SHAP top-K horizontal bars centered on zero, and the latest training calibration curve as an SVG bubble plot. The backend endpoint is `GET /api/v3/matches/prediction` with player IDs as query params.
- Match keys are canonicalised so `/v3/matches/20-10/prediction` and `/v3/matches/10-20/prediction` produce the same panel.

### ML quality dashboard

- `/v3/ml/quality` overlays the training-time calibration curve with a trailing 14-day recalculation over settled `PaperTradeLearningSample` rows. It also shows the prediction probability histogram, the daily volume sparkline, and a drift severity tile (GREEN / AMBER / RED / UNKNOWN) that uses the same `0.04` ECE / `0.05` observed-rate thresholds as the spec acceptance gates.
- The drift summary is `recent − training`; a RED severity means the model has drifted past the same gate that would block a fresh promotion.

## Endpoints, services, and tables added in Phase 05

- `GET /api/v3/matches/prediction` — single-match prediction panel (Phase 5 item 9).
- `GET /api/v3/ml/quality` — reliability + drift dashboard (Phase 5 item 10).
- `prediction_diff_log` column extension: `v3_variant_b_model_version`, `v3_variant_b_p1_probability`, `variant_ab_abs_diff` (item 5).
- Python `/v1/markov` upgraded from placeholder to closed-form best-of-3/-5 + Monte Carlo fallback (item 4).
- `models/prediction/variant-b-v3.0.0/` model slot (item 5).
- Java packages: `com.ttl.tabletennis.prediction.devig`, `com.ttl.tabletennis.prediction.edge`, `com.ttl.tabletennis.prediction.conformal`, `com.ttl.tabletennis.prediction.markov`.

## Verification Summary

Phase 5 verification ran four gates per item: focused Java tests, the full Java suite, the feature-flag lint, and `git diff --check`. Where the Python service changed, `pytest` ran with the new harness. Where the FE changed, the `web-v3` Vite build ran clean.

Representative test coverage added in Phase 05 includes: TS2 + Weng-Lin sync, rater ensemble math, Markov closed-form and MC simulators, Markov orchestrator retry semantics, Variant B sanity-block parsing on both sides, full devigging fixtures, the edge shrinkage matrix, conformal fit + predict + empirical coverage, the prediction panel composer (top-K trim, training-report degradation), and the ML quality aggregator (ECE math, equal-mass binning, drift severity classification).

## Release Gate Status

| Gate | Requirement | Status |
| --- | --- | --- |
| P05-G1 | TrueSkill-2 and Weng-Lin nightly jobs produce stable ratings | Implemented; pending two-week scheduler soak |
| P05-G2 | Variant A vs Variant B mean `|Δp_top| ≤ 0.04` on real traffic | Plumbing complete; pending real-traffic evidence |
| P05-G3 | `ConformalPredictor` empirical coverage at advertised `1 - α` | Verified on self-fit calibration set; pending production evidence |
| P05-G4 | `/v3/ml/quality` shows GREEN drift over 7 days | Page ships and computes severity; pending live dataset |
| P05-G5 | `EdgeEngine` shrinkage is the only attenuation path between model and stake | Implemented; depends on Phase 06 `StakingPolicy` adoption |

## Residual Limits

- The Python `BlenderService` runs Variant B as a secondary scorer **only when** `models/prediction/variant-b-v3.0.0/` contains a real model; the committed file is a template that gets overwritten on the first nightly refit.
- The Variant B feature payload uses Variant A's feature schema hash on the request; Variant B scores opportunistically and never throws 409 from the secondary. This means `absoluteDiffPTop` is noisy until the v2→v3 feature mapper threads more features end-to-end.
- The Java `ConformalPredictor.fit(...)` is intended for small in-JVM calibration sets; production calibration still flows through the Python harness.
- `EdgeEngine` is threshold-free. Bet / no-bet logic is `StakingPolicy`'s responsibility (Phase 06).
- The `/v3/ml/quality` reliability "recent" snapshot reads `PaperTradeLearningSample` where `status == 'WON'`. The Phase 04 prediction shadow path still uses `prediction_diff_log` as the v3 audit surface; the two are intentionally separate sources until the full v3 facade lands.
- `PredictionPanelService` falls back to a service-default Mondrian model with `q̂ = 0.85` when no production calibration bundle is wired; the conformal interval is plausible but not yet calibrated against production traffic.

## Handoff To Phase 06

Phase 06 can now lift staking + settlement onto the new prediction stack:

- Consume `Edge` outputs directly. The `StakingPolicy` v3 should read `Edge.edgeTop`, `Edge.shrinkFactor`, and `Edge.appliedShrinkers`; the policy YAML decides the minimum-edge gate per Spec §9.3.
- Use `ConformalResult.label` and `ConformalResult.predictionSet` to gate decisions when the matchup is `AMBIGUOUS` or `ANOMALOUS`.
- Treat `/v3/ml/quality` drift severity as a kill-switch input. RED severity should pause new bet creation until operators clear it.
- Drive Settlement promotion via the v3 facade once the model lifts past shadow. Phase 04's `prediction_diff_log` + Phase 05's `variant_ab_abs_diff` give the audit trail for that promotion.
- Keep `DeviggingService` as the single source of `p_fair`. Do not let `StakingPolicy` re-derive the fair line independently.

## Post-Mortem Summary

### What went well

- Every piece needed to gate a bet — fair line, model edge, uncertainty label, drift signal — now exists as a small `@Service` with focused tests.
- The v3 prediction stack stands as one coherent path from raters through to operator visibility, with `/v3/ml/quality` providing a live drift signal we can act on.
- Variant A vs Variant B agreement-gate plumbing landed alongside the model itself, so the §9.3 sanity block has a place to attach without retrofitting.

### What surprised us

- Same pattern as Phase 04: implementation completeness has run ahead of production evidence. Code is done; the model still has to earn promotion through observed behaviour.
- Mondrian conformal calibration turned out to be the cheapest part — the empirical-coverage test was tight on a self-fit calibration set with no extra engineering required.

### One improvement to bake in

- Run the v3 shadow under real traffic before promoting staking + settlement authority. The Phase 05 attenuation paths (edge shrink, conformal ambiguity, drift severity) should stay front and center until the §9.3 agreement gate has accumulated meaningful samples and `/v3/ml/quality` consistently reads GREEN.
