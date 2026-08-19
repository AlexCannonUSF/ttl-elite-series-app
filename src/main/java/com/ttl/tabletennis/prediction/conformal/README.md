# Conformal predictor (Phase 05 item 8)

Java-side Mondrian split-conformal predictor per Prediction Engine Spec §8.

`ConformalPredictor` is a stateless `@Service` with two surfaces:

| Method | When to use |
| --- | --- |
| `fit(samples, alpha, minGroupSize, version)` | Java-side calibration refits. Mirrors the Python writer so the resulting `MondrianSplitConformal` is byte-for-byte compatible with the loader in `prediction.calibration`. |
| `predict(model, pTop, key)` | Score a single calibrated p_top. Returns a `ConformalResult` with the explicit prediction set (`{TOP}`, `{BOT}`, `{TOP, BOT}`, or empty), the §8.4 uncertainty envelope, and the per-group quantile used. |
| `splitQuantile(scores, alpha)` | Raw quantile helper exposed for training pipelines; uses `ceil((n+1)(1-α))/n`-th order statistic. |

## Inputs

- `ConformalSample(pTop, topWon, groupKey)` — one calibration row. The
  non-conformity score is `1 - p_hat(true_label)` (Spec §8.2).
- `MondrianGroupKey(bestOf, isInPlay, isMajorEvent)` — §8.3 conditioning.
  `null` routes the sample into the pooled bucket only.

## Output

`ConformalResult` carries:

```
pTop, label ∈ {CONFIDENT_TOP, CONFIDENT_BOT, AMBIGUOUS, ANOMALOUS},
predictionSet ⊆ {TOP, BOT}, quantile, alpha, coverage,
intervalLow, intervalHigh, groupKey, method
```

The `predictionSet` is what callers should consult when deciding whether
to back a side; `label` is the convenience flag for downstream logging.

## Relationship to Phase 04 calibration

The Phase 04 `MondrianSplitConformal` class is the JSON-loaded artefact
shipped from `ttl-predict-py`. `ConformalPredictor.fit(...)` returns one
of these objects so any Java-fit model can be plugged into the same
artefact contract — useful when the JVM holds the entire calibration
set in memory (small Phase 05 deployments) and wants to skip the
Python round-trip.
