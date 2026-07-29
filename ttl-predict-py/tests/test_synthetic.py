"""Tests for the synthetic data generator."""

from __future__ import annotations

from datetime import datetime, timezone

from app.training import features
from app.training import synthetic


def test_generate_emits_one_row_per_request_with_label_and_timestamp():
    ds = synthetic.generate(variant="a", n_rows=24, days_span=12, seed=11)
    assert len(ds.rows) == 24
    for row in ds.rows:
        assert "decided_at_utc" in row
        assert isinstance(datetime.fromisoformat(row["decided_at_utc"]), datetime)
        assert row["label"] in (0, 1)


def test_generate_covers_full_variant_a_catalogue():
    ds = synthetic.generate(variant="a", n_rows=10, seed=11)
    catalogue = features.VARIANT_A
    for row in ds.rows:
        missing = features.validate_columns(catalogue, row.keys())
        assert missing == []


def test_generate_emits_market_column_for_variant_b():
    ds = synthetic.generate(variant="b", n_rows=8, seed=7)
    assert all("market_prob_top" in row for row in ds.rows)


def test_generate_deterministic_for_seed():
    a = synthetic.generate(variant="a", n_rows=12, seed=99)
    b = synthetic.generate(variant="a", n_rows=12, seed=99)
    assert a.rows == b.rows


def test_filter_columns_projects_subset():
    ds = synthetic.generate(variant="a", n_rows=4, seed=1)
    projected = synthetic.filter_columns(ds.rows, ["decided_at_utc", "label"])
    for row in projected:
        assert set(row.keys()) == {"decided_at_utc", "label"}


def test_labels_are_monotone_in_rater_delta():
    ds = synthetic.generate(variant="a", n_rows=2048, seed=21)
    pos = [row["rater.ensemble.delta"] for row in ds.rows if row["label"] == 1]
    neg = [row["rater.ensemble.delta"] for row in ds.rows if row["label"] == 0]
    if pos and neg:
        assert sum(pos) / len(pos) > sum(neg) / len(neg)


def test_timestamps_are_ascending():
    ds = synthetic.generate(variant="a", n_rows=16, days_span=8, seed=3)
    timestamps = [datetime.fromisoformat(row["decided_at_utc"]) for row in ds.rows]
    for earlier, later in zip(timestamps, timestamps[1:]):
        assert earlier <= later
    for ts in timestamps:
        assert ts.tzinfo == timezone.utc
