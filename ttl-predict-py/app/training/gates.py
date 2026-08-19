"""Acceptance gates for blender promotion.

Implements §6.4 + §7.5 of the Prediction Engine Spec:

- Expected calibration error (15-bin, equal-mass) ≤ 0.02 on the test slice.
- Maximum bin deviation ≤ 0.04.
- Brier skill score vs. devigged market ≥ 0.
- No bin's empirical frequency more than 2σ off the nominal.

The gate calculations live here as pure Python so they can be unit-tested
without LightGBM in the loop.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from math import sqrt
from typing import Iterable, Sequence


DEFAULT_BIN_COUNT = 15
DEFAULT_ECE_THRESHOLD = 0.02
DEFAULT_MAX_BIN_DEVIATION = 0.04
DEFAULT_BSS_THRESHOLD = 0.0
DEFAULT_FREQUENCY_SIGMA = 2.0


@dataclass(frozen=True)
class CalibrationBin:
    lower: float
    upper: float
    count: int
    mean_predicted: float
    mean_observed: float

    def deviation(self) -> float:
        return abs(self.mean_predicted - self.mean_observed)


@dataclass
class GateReport:
    ece: float
    max_bin_deviation: float
    brier_score: float
    brier_skill_score: float
    bins: list[CalibrationBin]
    passes: dict[str, bool] = field(default_factory=dict)

    def overall_pass(self) -> bool:
        return all(self.passes.values())


def equal_mass_bin_edges(probs: Sequence[float], n_bins: int) -> list[float]:
    if n_bins <= 1:
        raise ValueError("n_bins must be > 1")
    if not probs:
        return [0.0, 1.0]
    sorted_probs = sorted(probs)
    edges = [0.0]
    n = len(sorted_probs)
    for i in range(1, n_bins):
        idx = max(0, min(n - 1, (i * n) // n_bins))
        edges.append(float(sorted_probs[idx]))
    edges.append(1.0)
    # Deduplicate while preserving order; collapse pathological all-equal bins.
    dedup: list[float] = []
    for edge in edges:
        if not dedup or edge > dedup[-1]:
            dedup.append(edge)
    if len(dedup) < 2:
        dedup = [0.0, 1.0]
    return dedup


def calibration_bins(
    probs: Sequence[float],
    labels: Sequence[int],
    n_bins: int = DEFAULT_BIN_COUNT,
) -> list[CalibrationBin]:
    if len(probs) != len(labels):
        raise ValueError("probs and labels must align")
    if not probs:
        return []
    edges = equal_mass_bin_edges(probs, n_bins)
    bins: list[CalibrationBin] = []
    for lower, upper in zip(edges[:-1], edges[1:]):
        is_last = upper == edges[-1]
        selected = [
            (p, y)
            for p, y in zip(probs, labels)
            if (lower <= p < upper) or (is_last and p == upper)
        ]
        if not selected:
            continue
        count = len(selected)
        mean_predicted = sum(p for p, _ in selected) / count
        mean_observed = sum(y for _, y in selected) / count
        bins.append(
            CalibrationBin(
                lower=float(lower),
                upper=float(upper),
                count=count,
                mean_predicted=mean_predicted,
                mean_observed=mean_observed,
            )
        )
    return bins


def expected_calibration_error(bins: Iterable[CalibrationBin]) -> float:
    bins_list = list(bins)
    total = sum(b.count for b in bins_list)
    if total == 0:
        return 0.0
    return sum((b.count / total) * b.deviation() for b in bins_list)


def max_bin_deviation(bins: Iterable[CalibrationBin]) -> float:
    deviations = [b.deviation() for b in bins]
    return max(deviations) if deviations else 0.0


def brier_score(probs: Sequence[float], labels: Sequence[int]) -> float:
    if len(probs) != len(labels):
        raise ValueError("probs and labels must align")
    if not probs:
        return 0.0
    return sum((p - y) ** 2 for p, y in zip(probs, labels)) / len(probs)


def brier_skill_score(
    probs: Sequence[float],
    labels: Sequence[int],
    baseline_probs: Sequence[float],
) -> float:
    if len(baseline_probs) != len(probs):
        raise ValueError("baseline_probs must align with probs")
    model_brier = brier_score(probs, labels)
    baseline_brier = brier_score(baseline_probs, labels)
    if baseline_brier == 0.0:
        return 0.0 if model_brier == 0.0 else float("-inf")
    return 1.0 - (model_brier / baseline_brier)


def bins_within_two_sigma(
    bins: Iterable[CalibrationBin],
    sigma_threshold: float = DEFAULT_FREQUENCY_SIGMA,
) -> bool:
    """Return True iff every bin's empirical frequency is within
    ``sigma_threshold`` standard errors of its predicted frequency.
    """
    for bin_ in bins:
        if bin_.count == 0:
            continue
        p = bin_.mean_predicted
        se = sqrt(max(p * (1.0 - p), 1e-12) / bin_.count)
        if se == 0.0:
            continue
        if abs(bin_.mean_observed - p) > sigma_threshold * se:
            return False
    return True


def evaluate(
    probs: Sequence[float],
    labels: Sequence[int],
    market_probs: Sequence[float],
    *,
    n_bins: int = DEFAULT_BIN_COUNT,
    ece_threshold: float = DEFAULT_ECE_THRESHOLD,
    max_bin_threshold: float = DEFAULT_MAX_BIN_DEVIATION,
    bss_threshold: float = DEFAULT_BSS_THRESHOLD,
    sigma_threshold: float = DEFAULT_FREQUENCY_SIGMA,
) -> GateReport:
    bins = calibration_bins(probs, labels, n_bins=n_bins)
    ece = expected_calibration_error(bins)
    max_dev = max_bin_deviation(bins)
    score = brier_score(probs, labels)
    bss = brier_skill_score(probs, labels, market_probs)
    report = GateReport(
        ece=ece,
        max_bin_deviation=max_dev,
        brier_score=score,
        brier_skill_score=bss,
        bins=bins,
    )
    report.passes = {
        "ece_le_threshold": ece <= ece_threshold,
        "max_bin_le_threshold": max_dev <= max_bin_threshold,
        "bss_ge_threshold": bss >= bss_threshold,
        "bins_within_sigma": bins_within_two_sigma(bins, sigma_threshold),
    }
    return report
