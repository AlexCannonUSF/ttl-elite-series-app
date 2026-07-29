"""Tests for the Platt / isotonic / split-conformal calibrators."""

from __future__ import annotations

import json
import random

import pytest

from app.training import calibration as cal


def _logistic_target(rng: random.Random, n: int = 600):
    probs = []
    labels = []
    for _ in range(n):
        raw = rng.uniform(0.01, 0.99)
        # Underlying truth is logistic with shifted bias so Platt has work to do.
        biased = 0.6 * raw + 0.2
        labels.append(1 if rng.random() < biased else 0)
        probs.append(raw)
    return probs, labels


def test_platt_recovers_monotone_mapping():
    rng = random.Random(11)
    probs, labels = _logistic_target(rng)
    platt = cal.fit_platt(probs, labels, max_iter=400, learning_rate=0.1)
    calibrated = platt.apply(probs)
    # Calibrated probs should land closer to the empirical rate than raw.
    raw_brier = sum((p - y) ** 2 for p, y in zip(probs, labels)) / len(probs)
    cal_brier = sum((p - y) ** 2 for p, y in zip(calibrated, labels)) / len(calibrated)
    assert cal_brier <= raw_brier + 0.005


def test_platt_serialization_round_trip():
    platt = cal.PlattCalibrator(coef=1.23, intercept=-0.45)
    restored = cal.PlattCalibrator.from_dict(json.loads(json.dumps(platt.to_dict())))
    assert restored == platt


def test_isotonic_is_non_decreasing_and_clamped():
    probs = [0.05, 0.10, 0.20, 0.35, 0.55, 0.70, 0.85, 0.95]
    labels = [0, 0, 0, 1, 0, 1, 1, 1]
    iso = cal.fit_isotonic(probs, labels)
    last = -1.0
    for x in [0.0, 0.05, 0.2, 0.35, 0.55, 0.7, 0.95, 1.0]:
        y = iso.apply_one(x)
        assert -1e-9 <= y <= 1.0 + 1e-9
        assert y >= last - 1e-9
        last = y


def test_isotonic_serialization_round_trip():
    iso = cal.IsotonicCalibrator(
        x_breakpoints=(0.0, 0.5, 1.0),
        y_breakpoints=(0.1, 0.5, 0.9),
    )
    restored = cal.IsotonicCalibrator.from_dict(json.loads(json.dumps(iso.to_dict())))
    assert restored.x_breakpoints == iso.x_breakpoints
    assert restored.y_breakpoints == iso.y_breakpoints


def test_mondrian_group_key_round_trip():
    key = cal.MondrianGroupKey(best_of=5, is_in_play=True, is_major_event=False)
    decoded = cal.MondrianGroupKey.decode(key.encode())
    assert decoded == key


def test_split_conformal_quantile_size_matches_spec_formula():
    # n=99, alpha=0.1 → rank = ceil((100)(0.9)) = 90 → 90th sorted score.
    scores = [i / 100.0 for i in range(1, 100)]  # 0.01 .. 0.99
    q = cal._split_quantile(scores, alpha=0.1)
    assert pytest.approx(q, rel=1e-6) == 0.90


def test_fit_mondrian_uses_per_group_when_large_enough():
    rng = random.Random(3)
    probs = []
    labels = []
    keys = []
    for _ in range(200):
        probs.append(rng.uniform(0.4, 0.6))
        labels.append(1 if rng.random() < 0.5 else 0)
        keys.append("3|false|true")
    for _ in range(50):
        probs.append(rng.uniform(0.4, 0.6))
        labels.append(1 if rng.random() < 0.5 else 0)
        keys.append("5|false|false")
    fit = cal.fit_mondrian_split_conformal(
        probs=probs, labels=labels, group_keys=keys, alpha=0.1, min_group_size=30
    )
    assert "3|false|true" in fit.quantiles
    assert "5|false|false" in fit.quantiles
    assert fit.counts["3|false|true"] == 200


def test_fit_mondrian_falls_back_when_group_too_small():
    rng = random.Random(7)
    probs = [rng.uniform(0.3, 0.7) for _ in range(120)]
    labels = [1 if rng.random() < 0.5 else 0 for _ in range(120)]
    keys = ["3|false|true"] * 100 + ["5|false|false"] * 20
    fit = cal.fit_mondrian_split_conformal(
        probs=probs, labels=labels, group_keys=keys, alpha=0.1, min_group_size=30
    )
    assert fit.quantiles["5|false|false"] == fit.fallback_quantile


def test_mondrian_serialization_round_trip():
    fit = cal.MondrianSplitConformal(
        alpha=0.1,
        fallback_quantile=0.92,
        quantiles={"3|false|true": 0.91, "5|true|false": 0.95},
        counts={"3|false|true": 200, "5|true|false": 80},
    )
    restored = cal.MondrianSplitConformal.from_dict(json.loads(json.dumps(fit.to_dict())))
    assert restored.alpha == fit.alpha
    assert restored.fallback_quantile == fit.fallback_quantile
    assert restored.quantiles == fit.quantiles
    assert restored.counts == fit.counts


def test_calibration_bundle_applies_in_order():
    platt = cal.PlattCalibrator(coef=2.0, intercept=0.5)
    iso = cal.IsotonicCalibrator(
        x_breakpoints=(0.0, 0.5, 1.0),
        y_breakpoints=(0.0, 0.6, 1.0),
    )
    conformal = cal.MondrianSplitConformal(alpha=0.1, fallback_quantile=0.9)
    bundle = cal.CalibrationBundle(platt=platt, isotonic=iso, conformal=conformal)
    out = bundle.apply([0.1, 0.5, 0.9])
    assert all(0.0 <= p <= 1.0 for p in out)


def test_fit_platt_handles_empty_input():
    fit = cal.fit_platt([], [])
    assert fit.coef == 1.0 and fit.intercept == 0.0


def test_invalid_alpha_rejected():
    with pytest.raises(ValueError):
        cal.fit_mondrian_split_conformal(probs=[], labels=[], group_keys=[], alpha=0.0)
    with pytest.raises(ValueError):
        cal.fit_mondrian_split_conformal(probs=[], labels=[], group_keys=[], alpha=1.0)


def test_from_dict_rejects_wrong_type():
    with pytest.raises(ValueError):
        cal.PlattCalibrator.from_dict({"type": "isotonic"})
    with pytest.raises(ValueError):
        cal.IsotonicCalibrator.from_dict({"type": "platt"})
    with pytest.raises(ValueError):
        cal.MondrianSplitConformal.from_dict({"type": "platt"})
