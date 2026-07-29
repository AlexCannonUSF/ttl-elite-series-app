# Variant B blender — v3.0.0 sanity slot

Variant B includes the §3.6 devigged market features (Shin / power /
multiplicative / consensus). It is **not** wired into the edge engine — the
production model is Variant A. Variant B exists to satisfy Phase 05 item 5
of the implementation checklist and Prediction Engine Spec §9.3:

> Variant A vs Variant B agreement: mean absolute difference ≤ 0.04;
> larger divergences require manual review.

Train it with:

```bash
python -m app.training.cli train \
    --variant b \
    --data data/blender_training.parquet \
    --output models/prediction \
    --version v3.0.0
```

The harness writes the same artefacts as Variant A
(`blender.lgb.model`, `feature_registry.json`, `gate_report.json`,
`model_card.md`, `test_predictions.parquet`). The `feature_registry.json`
hash differs from Variant A because Variant B's catalogue includes the
§3.6 market columns; both schemas live in
`ttl-predict-py/app/training/features.py`.

When this directory contains a real model, the Python `BlenderService`
loads it as a secondary scorer and `/v1/blend` returns a `sanity` block
with `{pTop, pBot, modelVersion, calibratorVersion, conformalVersion,
featureSchemaHash, absoluteDiffPTop}`. The Java side persists those into
`prediction_diff_log` so divergences can be tracked against the
§9.3 threshold.
