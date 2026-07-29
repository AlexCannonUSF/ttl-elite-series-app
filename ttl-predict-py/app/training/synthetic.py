"""Synthetic data generator for blender harness smoke tests.

Produces a pandas DataFrame whose columns match a chosen feature catalogue
plus the supervised target ``label`` (1 if the top player wins) and a
``decided_at_utc`` timestamp. The label is a deterministic function of a
hidden skill gap to give the smoke harness a learnable signal — useful for
testing that walk-forward + LightGBM can fit, not for benchmarking quality.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from math import exp
from typing import Sequence

from .features import FeatureCatalogue, catalogue_for


@dataclass(frozen=True)
class SyntheticDataset:
    feature_columns: list[str]
    rows: list[dict]
    catalogue_name: str

    def to_dataframe(self):  # pragma: no cover — pandas only at runtime
        import pandas as pd

        return pd.DataFrame(self.rows)


def _sigmoid(x: float) -> float:
    if x >= 0:
        z = exp(-x)
        return 1.0 / (1.0 + z)
    z = exp(x)
    return z / (1.0 + z)


def _seeded_random(seed: int):
    import random

    return random.Random(seed)


def generate(
    *,
    variant: str = "a",
    n_rows: int = 1024,
    days_span: int = 90,
    seed: int = 42,
    start_at: datetime | None = None,
) -> SyntheticDataset:
    """Generate a synthetic training table.

    The features are drawn from light random distributions; the binary
    ``label`` is sampled from ``Bernoulli(sigmoid(rater.ensemble.delta * 4))``
    so the LightGBM model has a clear, monotone signal to recover.
    """
    catalogue: FeatureCatalogue = catalogue_for(variant)
    rng = _seeded_random(seed)
    base_time = start_at or datetime(2026, 1, 1, tzinfo=timezone.utc)
    spacing = timedelta(days=days_span) / max(n_rows, 1)

    rows: list[dict] = []
    for index in range(n_rows):
        timestamp = base_time + spacing * index
        row: dict = {"decided_at_utc": timestamp.isoformat()}
        rater_delta = rng.gauss(0.0, 0.35)
        for feature in catalogue.features:
            if feature.name == "rater.ensemble.delta":
                row[feature.name] = float(rater_delta)
                continue
            if feature.categorical:
                row[feature.name] = f"{feature.name}-{rng.randint(0, 4)}"
                continue
            unit = feature.unit
            if unit == "bool":
                row[feature.name] = int(rng.random() < 0.4)
            elif unit == "rate":
                row[feature.name] = max(0.0, min(1.0, rng.gauss(0.5, 0.15)))
            elif unit == "prob":
                row[feature.name] = max(0.0, min(1.0, rng.gauss(0.5, 0.18)))
            elif unit == "decimal":
                row[feature.name] = max(1.01, rng.gauss(2.0, 0.5))
            elif unit == "rating":
                row[feature.name] = rng.gauss(1500.0, 120.0)
            elif unit == "bps":
                row[feature.name] = rng.gauss(0.0, 50.0)
            elif unit == "minutes":
                row[feature.name] = max(0.0, rng.gauss(180.0, 90.0))
            elif unit == "hours":
                row[feature.name] = rng.gauss(0.0, 3.0)
            elif unit == "seconds":
                row[feature.name] = max(0.0, rng.gauss(20.0, 10.0))
            elif unit == "millis":
                row[feature.name] = max(0.0, rng.gauss(86_400_000.0, 86_400_000.0))
            elif unit == "ticks":
                row[feature.name] = max(0.0, rng.gauss(40.0, 20.0))
            elif unit == "id":
                row[feature.name] = rng.randint(1, 20)
            elif unit == "int":
                row[feature.name] = rng.randint(0, 7)
            elif unit == "points":
                row[feature.name] = rng.gauss(0.0, 3.0)
            else:
                row[feature.name] = rng.gauss(0.0, 1.0)
        prob_top = _sigmoid(4.0 * rater_delta)
        row["label"] = int(rng.random() < prob_top)
        if variant.lower() in {"b", "variant-b", "with-market"}:
            row["market_prob_top"] = max(0.02, min(0.98, prob_top + rng.gauss(0.0, 0.05)))
        else:
            row["market_prob_top"] = max(0.02, min(0.98, prob_top + rng.gauss(0.0, 0.10)))
        rows.append(row)

    return SyntheticDataset(
        feature_columns=catalogue.names,
        rows=rows,
        catalogue_name=catalogue.name,
    )


def filter_columns(rows: Sequence[dict], columns: Sequence[str]) -> list[dict]:
    """Project ``rows`` onto a strict subset of columns."""
    keep = list(columns)
    return [{k: row[k] for k in keep if k in row} for row in rows]
