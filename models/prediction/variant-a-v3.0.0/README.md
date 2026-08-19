# Variant A blender — v3.0.0 slot

This directory is the on-disk slot for the production LightGBM Variant A
blender. Nightly walk-forward training (per the Phase 04 deliverable) writes:

- `blender.lgb.model` — LightGBM binary booster.
- `feature_registry.json` — feature catalogue + schema hash that the model
  was trained on. Java's `BlenderClient` refuses any vector whose hash
  differs.
- `gate_report.json` — §7.5 acceptance gate evaluations.
- `test_predictions.parquet` — held-out test slice predictions for audit.
- `model_card.md` — human-readable summary (committed template lives here).
- `promotion_record.yaml` — populated by the promotion workflow when a
  freshly trained model is approved.

The training harness lives at `ttl-predict-py/app/training/`. Run it with::

    python -m app.training.cli train \
        --variant a \
        --data data/blender_training.parquet \
        --output models/prediction \
        --version v3.0.0
