# LightGBM Blender — Variant B

Auto-generated model card. Regenerated on every nightly walk-forward refit (Prediction Engine Spec §6.4).

## Identity

- **Model version:** `v3.0.0`
- **Feature registry:** `v3.0.0-variant-b`
- **Feature schema hash:** `bcc59e05798d2478cd762acd35d251c8551d286ecd53570daf84c85eafc58756`
- **Trained at UTC:** `2026-05-19T22:45:08+00:00`
- **Best iteration:** `163`

## Training cohort

| Slice | Start (UTC) | End (UTC) | Rows |
| --- | --- | --- | --- |
| Train | `2025-04-07T04:00:00+00:00` | `2026-04-07T04:00:00+00:00` | `84271` |
| Validation | `2026-04-07T08:00:00+00:00` | `2026-04-21T08:00:00+00:00` | `3339` |
| Test | `2026-04-21T12:00:00+00:00` | `2026-05-19T12:00:00+00:00` | `5751` |

Purge gap between slices: `14400s`.

## Hyperparameters (§6.3)

```yaml
bagging_fraction: 0.75
bagging_freq: 5
deterministic: True
feature_fraction: 0.75
learning_rate: 0.03
max_depth: -1
metric: binary_logloss
min_data_in_leaf: 200
num_leaves: 63
objective: binary
verbose: -1
num_boost_round: 1500
early_stopping_rounds: 50
```

## Acceptance gates (§6.4 + §7.5)

| Metric | Value | Pass |
| --- | --- | --- |
| ECE (15-bin, equal-mass) | `0.0212` | `False` |
| Max bin deviation | `0.0553` | `False` |
| Brier score | `0.2299` | — |
| Brier skill score vs. market | `0.1165` | `True` |
| Bins within 2σ | — | `False` |

**Overall pass:** `False`

## Caveats

- Variant A intentionally excludes §3.6 market features so the model competes against the market rather than copying it; do not enable the `with-market` variant for edge detection.
- Any material regression on the gates above blocks promotion. The promotion record (`promotion_record.yaml`) is committed alongside the artefact when promotion is approved.
- A model whose feature schema hash differs from the live `FeatureBuilder` will refuse to score — §3.10 hard-error contract.
