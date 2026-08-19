# Variant B v3.0.0 — Promotion Record

_Curated companion to `model_card.md`. Not regenerated on each training run._

## What Variant B is

Variant A's feature set **plus** the §3.6 devigged-market features (Shin / Power / Multiplicative consensus + market move metrics). Trained, scored, and promoted independently from Variant A so we can test the §9.3 agreement gate (`mean |Δp_top| ≤ 0.04`) on real traffic.

The plan in `TTLElite-Series-3.0-Prediction-Engine-Spec.md` §6.4 is that Variant B serves as a **sanity check**, not the primary scorer. If A and B agree on `p_top` within tolerance, the v3 stack is internally consistent; if they diverge, the divergence is a flag-able signal.

## Current artefact (2026-05-19)

- **Trained at:** `2026-05-19` (single-pass production fit)
- **Training configuration:** `TEST_DAYS=28`, `TRAINING_HALF_LIFE_DAYS=180`, `CALIBRATION_MODE=platt`
- **Feature count:** Variant A's 84 + the §3.6 market features

## Acceptance gate snapshot

| Gate | Threshold | Observed | Pass |
| --- | ---: | ---: | :---: |
| ECE (15-bin, equal-mass) | ≤ 0.02 | 0.0212 | ✗ (borderline) |
| Max bin deviation | ≤ 0.04 | 0.0553 | ✗ |
| Brier skill score vs market | ≥ 0 | **+0.1165** | ✓ |
| Per-bin within 2σ | strict | one bin out | ✗ |

**Promotion decision:** Variant B is **not promoted** as a primary scorer; it stays in the model slot to feed the §9.3 sanity block in `BlenderService`.

## Comparison vs Variant A

Same training run config; identical 28-day test slice anchored at 2026-05-19:

| Metric | Variant A | Variant B | Δ |
| --- | ---: | ---: | ---: |
| ECE | 0.0171 | 0.0212 | +0.0041 (A is better) |
| Max bin deviation | 0.0641 | 0.0553 | −0.0088 (B is better on worst bin) |
| BSS vs market | +0.1169 | +0.1165 | essentially tied |

Adding the market features narrows the worst bin slightly but raises overall ECE — the calibration story is similar to Variant A and **does not change the headline finding**: data-volume is the constraint on the per-bin sigma gate, not feature engineering.

## What Variant B is good for right now

- Powering the `sanity` block in `/v1/blend` responses so the Java path persists `variant_ab_abs_diff` into `prediction_diff_log` for every shadow row.
- Backstopping Variant A — if a future Variant A regression breaks the agreement gate without breaking Variant A's solo gates, that's a strong signal to stop the promotion.

## Caveats

- The Variant A vs B comparison only stands when both use the same training cohort and same calibration mode. The training pipeline pins both to a single run config; don't drift them independently.
- The `--variant b` feature payload uses Variant A's feature schema hash on the request; Variant B scores opportunistically and never throws 409 from the secondary. This means `absoluteDiffPTop` is noisy until the v2→v3 feature mapper threads more features end-to-end.
