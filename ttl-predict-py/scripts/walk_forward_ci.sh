#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PYTHON="${PYTHON:-$ROOT/ttl-predict-py/.venv/bin/python}"
DATA="${DATA:-$ROOT/data/blender_training.csv}"
OUTPUT="${OUTPUT:-$ROOT/build/walk-forward-ci}"
VARIANT="${VARIANT:-a}"
VERSION="${VERSION:-v3.0.0}"
FOLDS="${FOLDS:-4}"
TRAIN_DAYS="${TRAIN_DAYS:-365}"
VALIDATION_DAYS="${VALIDATION_DAYS:-28}"
# 2026-05-19: test-window default raised from 14 → 28 days and a default
# 180-day training half-life is set. Both were established by the
# Variant A walk-forward sweep documented in
# docs/ttlelite-series-3.0/reports/2026-05-19-variant-a-walk-forward-ci.md.
# Under these defaults fold 1 passes ECE + max-bin-deviation + BSS; the
# stricter per-bin sigma gate needs more match volume to satisfy.
TEST_DAYS="${TEST_DAYS:-28}"
CALIBRATION_MODE="${CALIBRATION_MODE:-platt}"
TRAINING_HALF_LIFE_DAYS="${TRAINING_HALF_LIFE_DAYS:-180}"

cd "$ROOT"

EXTRA_ARGS=""
if [[ -n "$TRAINING_HALF_LIFE_DAYS" ]]; then
  EXTRA_ARGS="--training-half-life-days $TRAINING_HALF_LIFE_DAYS"
fi

PYTHONPATH="$ROOT/ttl-predict-py" "$PYTHON" -m app.training.cli walk-forward-ci \
  --variant "$VARIANT" \
  --data "$DATA" \
  --output "$OUTPUT" \
  --version "$VERSION" \
  --folds "$FOLDS" \
  --train-days "$TRAIN_DAYS" \
  --validation-days "$VALIDATION_DAYS" \
  --test-days "$TEST_DAYS" \
  --calibration-mode "$CALIBRATION_MODE" \
  $EXTRA_ARGS
