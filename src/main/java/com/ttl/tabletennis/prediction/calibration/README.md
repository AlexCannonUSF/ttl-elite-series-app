# Prediction calibration (Phase 04 item 9)

Java loaders + scorers for the three calibration artefacts emitted by the
Python blender harness (`ttl-predict-py/app/training/calibration.py`).

| Artefact | Class | Spec |
| --- | --- | --- |
| `platt.json` | `PlattCalibrator` | §7.2 |
| `isotonic.json` | `IsotonicCalibrator` | §7.3 |
| `conformal.json` | `MondrianSplitConformal` | §8 |

`CalibrationBundle.loadFromDirectory(...)` reads all three from a
`models/prediction/variant-x-vY.Y.Z/` directory and exposes:

- `calibrate(double)` → applies Platt then isotonic in order.
- `uncertainty(p_top, MondrianGroupKey)` → returns the
  `{coverage, label, intervalLow, intervalHigh, method, alpha}` block per
  Prediction Engine Spec §8.4. Labels are `CONFIDENT_TOP`, `CONFIDENT_BOT`,
  `AMBIGUOUS`, or `ANOMALOUS`.

The Python writers and Java readers share JSON shape exactly so a model
artefact built by `python -m app.training.cli train ...` loads here without
transformation. Versions are pinned on each artefact so the future
`PredictionFacade` (Phase 04 item 11) can refuse a mismatched bundle.
