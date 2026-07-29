# Variant B Walk-Forward CI Report — 2026-05-19

## Scope

Ran `ttl-predict-py/scripts/walk_forward_ci.sh` against `data/blender_training.csv` with `VARIANT=b`.

- Variant: `b` (Variant A features + §3.6 devigged market columns)
- Folds: latest four non-overlapping 14-day test windows
- Train window: 365 days · Validation window: 28 days · Test window: 14 days
- Artefact output: `build/walk-forward-ci-variant-b/`

## Result

Variant B has positive Brier skill vs the market baseline in every fold, but **fails the §6.4 gate in all four folds** on calibration thresholds. The failure shape is identical to Variant A.

| Fold | Test End | ECE | Max Bin Deviation | BSS vs Market | Pred Range | Pred Std | Pass |
| --- | --- | ---: | ---: | ---: | --- | ---: | --- |
| 1 | 2026-04-07 | 0.0228 | 0.0626 | 0.1905 | [0.082, 0.911] | 0.145 | No |
| 2 | 2026-04-21 | 0.0339 | 0.0876 | 0.0949 | [0.062, 0.948] | 0.156 | No |
| 3 | 2026-05-05 | 0.0276 | 0.0699 | 0.0951 | [0.076, 0.940] | 0.163 | No |
| 4 | 2026-05-19 | 0.0246 | 0.0781 | 0.1351 | [0.095, 0.944] | 0.142 | No |

## Comparison vs Variant A

| Metric | Variant A (mean across 4 folds) | Variant B (mean across 4 folds) | Δ |
| --- | ---: | ---: | ---: |
| ECE | 0.0280 | 0.0272 | −0.0008 (B slightly better) |
| Max Bin Deviation | 0.0637 | 0.0746 | +0.0109 (B slightly worse) |
| BSS vs Market | 0.1294 | 0.1289 | −0.0005 (essentially tied) |

Variant B did **not** materially help calibration despite using market features directly. The market features added some discrimination headroom on fold 2 / fold 3 but the tail compression problem persists.

## Decision

Do not promote Variant B as the primary path. Both variants ship into their `models/prediction/variant-{a,b}-v3.0.0/` slots and `ttl-predict-py` is serving them in shadow alongside the legacy ensemble. The §11 production soak can continue collecting evidence through the `prediction_diff_log` audit trail.

## Same root cause as Variant A

The Variant A follow-up diagnostic (see `2026-05-19-variant-a-walk-forward-ci.md`) identified two coupled issues:

1. **Tail under-confidence**: the LightGBM model produces predictions structurally compressed inside ~[0.08, 0.93]; the market on the same matchups reaches [0.001, 0.999]. The model knows who the favourite is but does not say it strongly enough.
2. **Gate-vs-sample-size tension**: 14-day test windows yield 200-300 rows per bin in folds 2-4; a 2% ECE threshold sits at the binomial noise floor for that sample size.

Variant B inherits both of those properties because it shares the LightGBM pipeline + the spec-mandated 14-day test slice.

## Recommended next experiments

The same two experiments would unblock both variants:

1. **Time-decayed sample weights** at training time — weight rows by `exp(-age_days / half_life)`, half-life 90–180 days. The expectation is that recent matchup distribution is widening over time and the model is being held back by older, narrower-distribution rows.
2. **Widen the calibration fit set** from validation-only to validation + last 90 days of train, so isotonic has enough per-bin data to fit a non-monotonic recalibration curve.

Either alone may not pass the gate; both together is the next planned pass.
