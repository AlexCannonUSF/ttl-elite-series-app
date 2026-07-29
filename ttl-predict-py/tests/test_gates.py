"""Tests for the acceptance gate calculations."""

from __future__ import annotations

import random

from app.training import gates


def test_perfectly_calibrated_predictions_have_zero_ece():
    rng = random.Random(7)
    probs = [rng.random() for _ in range(2000)]
    labels = [1 if rng.random() < p else 0 for p in probs]
    bins = gates.calibration_bins(probs, labels, n_bins=15)
    assert gates.expected_calibration_error(bins) < 0.05


def test_biased_predictions_fail_ece_gate():
    # Always predict 0.7 but true rate is 0.3 → 0.4 calibration gap.
    probs = [0.7] * 500
    labels = [1 if i < 150 else 0 for i in range(500)]
    bins = gates.calibration_bins(probs, labels, n_bins=10)
    ece = gates.expected_calibration_error(bins)
    assert ece > 0.3
    assert gates.max_bin_deviation(bins) > 0.3


def test_brier_skill_score_positive_when_model_beats_baseline():
    probs = [0.95 if i % 2 == 0 else 0.05 for i in range(200)]
    labels = [1 if i % 2 == 0 else 0 for i in range(200)]
    baseline = [0.5] * 200
    bss = gates.brier_skill_score(probs, labels, baseline)
    assert bss > 0.5


def test_brier_skill_score_negative_when_model_loses_to_baseline():
    probs = [0.5 + (0.01 * (1 if i % 2 == 0 else -1)) for i in range(200)]
    labels = [1 if i % 2 == 0 else 0 for i in range(200)]
    perfect_baseline = [1.0 if i % 2 == 0 else 0.0 for i in range(200)]
    bss = gates.brier_skill_score(probs, labels, perfect_baseline)
    assert bss == float("-inf")


def test_evaluate_packs_passes_dict():
    rng = random.Random(13)
    probs = [rng.random() for _ in range(500)]
    labels = [1 if rng.random() < p else 0 for p in probs]
    market = [0.5] * 500
    report = gates.evaluate(probs, labels, market)
    assert {"ece_le_threshold", "max_bin_le_threshold", "bss_ge_threshold", "bins_within_sigma"}.issubset(report.passes)


def test_evaluate_overall_pass_requires_every_gate():
    # Near-perfectly calibrated predictions (|prob - label| = 0.01) → ECE 0.01 < 0.02.
    probs = [0.99] * 100 + [0.01] * 100
    labels = [1] * 100 + [0] * 100
    baseline = [0.5] * 200
    report = gates.evaluate(probs, labels, baseline)
    assert report.overall_pass(), report.passes
    assert report.passes["ece_le_threshold"]
    assert report.passes["bss_ge_threshold"]
    assert report.passes["bins_within_sigma"]


def test_equal_mass_bin_edges_handles_constant_probs():
    edges = gates.equal_mass_bin_edges([0.5] * 50, 10)
    assert edges[0] == 0.0 and edges[-1] == 1.0
    assert len(edges) >= 2
