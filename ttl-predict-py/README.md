# ttl-predict-py

Python prediction service for TTLElite Series 3.0.

## Endpoints

- `GET /v1/health` — service status + blender readiness.
- `GET /metrics` — Prometheus scrape (instrumentator + `ttl_predict_blender_ready`).
- `POST /v1/blend` — Phase 04: full LightGBM blender pipeline. Validates the
  request's `featureSchemaHash` against the loaded `feature_registry.json`,
  scores via LightGBM, applies Platt + isotonic calibration, and returns the
  Prediction Engine Spec §10 + §8.4 contract (`pTop`, `pBot`, `uncertainty`).
  Returns `503` when artefacts are missing, `409` on schema-hash mismatch.
- `POST /v1/markov` — Phase 05 point-by-point Markov simulator for 11-point
  table tennis. Uses closed-form match paths for best-of-3 and best-of-5,
  with a deterministic 50k-trial Monte Carlo fallback for larger match
  lengths. Returns `pMatchTop`, exact 3-0/3-1/3-2 probabilities for
  best-of-5, expected total points, and estimated median match minutes.

Startup loads artefacts from
`models/prediction/variant-a-v3.0.0/` by default; override with
`TTL_BLENDER_ARTEFACT_DIR`. If the directory or model file is missing, the
service comes up in `MODEL_MISSING` state — health stays green, `/v1/blend`
returns 503, the rest of the surface works.

## Local run

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8090
```

## Phase 04 LightGBM blender harness

Item 8 of the Phase 04 checklist ships the walk-forward training harness for
the Variant A blender (Prediction Engine Spec §6 + §12). The harness lives at
`app/training/` and is callable as a Python module:

```bash
# Smoke test the end-to-end harness on synthetic data — produces
# build/blender-smoke/variant-a-smoke/{blender.lgb.model, model_card.md, ...}
python -m app.training.cli smoke --variant a --rows 2048

# Train against a real dataset (Parquet or CSV) — produces
# models/prediction/variant-a-v3.0.0/{blender.lgb.model, feature_registry.json,
# gate_report.json, model_card.md, test_predictions.parquet}
python -m app.training.cli train \
    --variant a \
    --data data/blender_training.parquet \
    --output models/prediction \
    --version v3.0.0

# Run the promotion gate across the latest walk-forward folds. This writes
# build/walk-forward-ci/walk_forward_ci_variant_a_v3.0.0.json and exits
# non-zero if any fold fails a §6.4 / §7.5 acceptance gate.
ttl-predict-py/scripts/walk_forward_ci.sh
```

The CLI exits non-zero if any §6.4 / §7.5 acceptance gate fails (ECE > 0.02,
max-bin deviation > 0.04, Brier skill score vs. devigged market < 0, or any
bin's empirical frequency more than 2σ off nominal). Gate values land in
`gate_report.json` so CI can surface them on regression.

### Training data schema

The harness expects rows with at least:

- Every feature in the chosen variant's catalogue (`app.training.features.VARIANT_A`
  for the no-market production model).
- `decided_at_utc` — ISO-8601 UTC timestamp; used for the walk-forward split.
- `label` — 1 if `player.top` won the match, else 0.
- `market_prob_top` — devigged consensus probability of the top side
  (Prediction Engine Spec §9.1). Used as the Brier-skill-score baseline.

Walk-forward defaults match §6.4: 365-day train window, 14-day validation,
14-day test, 4-hour purge gap between adjacent slices. Override via
`--train-days / --validation-days / --test-days / --purge-hours`.
For the shell CI wrapper, use environment variables such as
`FOLDS=6 CALIBRATION_MODE=platt-isotonic ttl-predict-py/scripts/walk_forward_ci.sh`.

### Artefact layout

```
models/prediction/variant-a-v3.0.0/
├── blender.lgb.model       # LightGBM binary (produced by the trainer)
├── feature_registry.json   # FeatureCatalogue schema + sha256 hash
├── gate_report.json        # §7.5 acceptance gate values
├── model_card.md           # human-readable card (template committed)
├── test_predictions.parquet
└── promotion_record.yaml   # populated by the promotion workflow
```

`feature_registry.json#schema_hash` is the value the Java side's
`BlenderClient` will compare against when scoring; mismatched hashes are a
hard error (Prediction Engine Spec §3.10).

## Tests

```bash
pytest ttl-predict-py
```
