"""Tests for the walk-forward / purged k-fold layer."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.training import walk_forward as wf


def test_walk_forward_slice_layout_matches_spec_defaults():
    test_end = datetime(2026, 6, 1, 0, 0, tzinfo=timezone.utc)
    s = wf.walk_forward_slice(test_end=test_end)
    assert s.test_end == test_end
    assert s.test_start == test_end - wf.DEFAULT_TEST_WINDOW
    assert s.validation_end == s.test_start - wf.DEFAULT_PURGE_GAP
    assert s.validation_start == s.validation_end - wf.DEFAULT_VALIDATION_WINDOW
    assert s.train_end == s.validation_start - wf.DEFAULT_PURGE_GAP
    assert s.train_start == s.train_end - wf.DEFAULT_TRAIN_WINDOW
    wf.assert_no_leakage(s)


def test_walk_forward_slice_requires_timezone():
    with pytest.raises(ValueError):
        wf.walk_forward_slice(test_end=datetime(2026, 6, 1))


def test_assert_no_leakage_catches_too_small_gap():
    test_end = datetime(2026, 6, 1, tzinfo=timezone.utc)
    s = wf.walk_forward_slice(test_end=test_end, purge_gap=timedelta(0))
    with pytest.raises(ValueError):
        wf.assert_no_leakage(s)


def test_expanding_walk_forward_returns_sorted_slices():
    history_start = datetime(2025, 1, 1, tzinfo=timezone.utc)
    history_end = datetime(2026, 6, 1, tzinfo=timezone.utc)
    slices = wf.expanding_walk_forward(
        history_start=history_start,
        history_end=history_end,
    )
    assert slices
    for earlier, later in zip(slices, slices[1:]):
        assert earlier.test_end < later.test_end


def test_expanding_walk_forward_returns_empty_when_history_too_short():
    history_start = datetime(2026, 5, 1, tzinfo=timezone.utc)
    history_end = datetime(2026, 6, 1, tzinfo=timezone.utc)
    assert wf.expanding_walk_forward(
        history_start=history_start,
        history_end=history_end,
    ) == []


def test_mask_for_returns_inclusive_lower_exclusive_upper():
    ts = [
        datetime(2026, 1, 1, tzinfo=timezone.utc),
        datetime(2026, 1, 2, tzinfo=timezone.utc),
        datetime(2026, 1, 3, tzinfo=timezone.utc),
    ]
    mask = wf.mask_for(ts, datetime(2026, 1, 1, tzinfo=timezone.utc), datetime(2026, 1, 3, tzinfo=timezone.utc))
    assert mask == [True, True, False]


def test_to_utc_handles_strings_and_naive_datetimes():
    parsed = wf.to_utc("2026-05-17T03:04:05Z")
    assert parsed.tzinfo is not None
    assert parsed.year == 2026 and parsed.month == 5 and parsed.day == 17
    promoted = wf.to_utc(datetime(2026, 5, 17, 3, 4, 5))
    assert promoted.tzinfo is timezone.utc
