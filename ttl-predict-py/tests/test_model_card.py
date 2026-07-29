"""Tests for the model card renderer."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from app.training import gates as gates_mod
from app.training import features as features_mod
from app.training.model_card import render_model_card, write_model_card
from app.training.walk_forward import walk_forward_slice


def _report() -> gates_mod.GateReport:
    probs = [0.95, 0.05, 0.92, 0.08]
    labels = [1, 0, 1, 0]
    market = [0.5, 0.5, 0.5, 0.5]
    return gates_mod.evaluate(probs, labels, market)


def test_render_model_card_includes_required_sections():
    catalogue = features_mod.VARIANT_A
    slice_ = walk_forward_slice(test_end=datetime(2026, 6, 1, tzinfo=timezone.utc))
    body = render_model_card(
        variant="a",
        catalogue=catalogue,
        slice_=slice_,
        report=_report(),
        booster_metadata={
            "model_version": "v3.0.0",
            "trained_at_utc": "2026-05-17T03:04:05+00:00",
            "best_iteration": 257,
            "train_rows": 1000,
            "validation_rows": 200,
            "test_rows": 300,
            "params": {"objective": "binary", "learning_rate": 0.03},
        },
    )
    for needle in (
        "# LightGBM Blender — Variant A",
        "## Identity",
        "## Training cohort",
        "## Hyperparameters",
        "## Acceptance gates",
        catalogue.name,
        catalogue.schema_hash(),
    ):
        assert needle in body, needle


def test_write_model_card_writes_md_and_gate_report(tmp_path: Path):
    catalogue = features_mod.VARIANT_A
    slice_ = walk_forward_slice(test_end=datetime(2026, 6, 1, tzinfo=timezone.utc))
    report = _report()

    written = write_model_card(
        directory=tmp_path / "variant-a-v3.0.0",
        variant="a",
        catalogue=catalogue,
        slice_=slice_,
        report=report,
        booster_metadata={
            "model_version": "v3.0.0",
            "trained_at_utc": "2026-05-17T03:04:05+00:00",
            "best_iteration": 257,
            "train_rows": 1000,
            "validation_rows": 200,
            "test_rows": 300,
            "params": {"objective": "binary"},
        },
    )

    assert written.exists()
    assert (written.parent / "gate_report.json").exists()
    text = written.read_text(encoding="utf-8")
    assert "LightGBM Blender" in text
