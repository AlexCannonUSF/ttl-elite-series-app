"""Walk-forward + purged k-fold splits for blender training.

Implements the Prediction Engine Spec §6.4 protocol:

- Rolling 12-month training window, 2-week validation, 2-week test.
- Purged folds with a 4-hour gap (configurable) between contiguous slices to
  remove adjacency leakage from intra-match correlations.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Iterable, List, Sequence


DEFAULT_TRAIN_WINDOW = timedelta(days=365)
DEFAULT_VALIDATION_WINDOW = timedelta(days=14)
DEFAULT_TEST_WINDOW = timedelta(days=14)
DEFAULT_PURGE_GAP = timedelta(hours=4)


@dataclass(frozen=True)
class WalkForwardSlice:
    """A single (train, validation, test) split over a contiguous timeline."""

    train_start: datetime
    train_end: datetime
    validation_start: datetime
    validation_end: datetime
    test_start: datetime
    test_end: datetime
    purge_gap: timedelta

    def contains_train(self, ts: datetime) -> bool:
        return self.train_start <= ts < self.train_end

    def contains_validation(self, ts: datetime) -> bool:
        return self.validation_start <= ts < self.validation_end

    def contains_test(self, ts: datetime) -> bool:
        return self.test_start <= ts < self.test_end

    def as_dict(self) -> dict:
        return {
            "train_start": self.train_start.isoformat(),
            "train_end": self.train_end.isoformat(),
            "validation_start": self.validation_start.isoformat(),
            "validation_end": self.validation_end.isoformat(),
            "test_start": self.test_start.isoformat(),
            "test_end": self.test_end.isoformat(),
            "purge_gap_seconds": int(self.purge_gap.total_seconds()),
        }


def walk_forward_slice(
    *,
    test_end: datetime,
    train_window: timedelta = DEFAULT_TRAIN_WINDOW,
    validation_window: timedelta = DEFAULT_VALIDATION_WINDOW,
    test_window: timedelta = DEFAULT_TEST_WINDOW,
    purge_gap: timedelta = DEFAULT_PURGE_GAP,
) -> WalkForwardSlice:
    """Build the canonical training/validation/test slice ending at ``test_end``.

    Time ordering (most recent on the right):

    [ train ][ gap ][ validation ][ gap ][ test ]
    """
    if test_end.tzinfo is None:
        raise ValueError("test_end must be timezone-aware")
    test_start = test_end - test_window
    validation_end = test_start - purge_gap
    validation_start = validation_end - validation_window
    train_end = validation_start - purge_gap
    train_start = train_end - train_window
    return WalkForwardSlice(
        train_start=train_start,
        train_end=train_end,
        validation_start=validation_start,
        validation_end=validation_end,
        test_start=test_start,
        test_end=test_end,
        purge_gap=purge_gap,
    )


def expanding_walk_forward(
    *,
    history_start: datetime,
    history_end: datetime,
    train_window: timedelta = DEFAULT_TRAIN_WINDOW,
    validation_window: timedelta = DEFAULT_VALIDATION_WINDOW,
    test_window: timedelta = DEFAULT_TEST_WINDOW,
    purge_gap: timedelta = DEFAULT_PURGE_GAP,
    step: timedelta | None = None,
) -> List[WalkForwardSlice]:
    """Produce a list of slices stepping forward through history.

    ``step`` defaults to ``test_window`` so successive test windows are
    non-overlapping. The earliest viable slice starts where the full
    train window fits into ``[history_start, history_end]``.
    """
    if history_start.tzinfo is None or history_end.tzinfo is None:
        raise ValueError("history_start and history_end must be timezone-aware")
    if history_end <= history_start:
        raise ValueError("history_end must be after history_start")
    effective_step = step or test_window
    if effective_step.total_seconds() <= 0:
        raise ValueError("step must be positive")

    minimum_test_end = (
        history_start
        + train_window
        + purge_gap
        + validation_window
        + purge_gap
        + test_window
    )
    if minimum_test_end > history_end:
        return []

    slices: list[WalkForwardSlice] = []
    test_end = history_end
    while test_end >= minimum_test_end:
        slices.append(
            walk_forward_slice(
                test_end=test_end,
                train_window=train_window,
                validation_window=validation_window,
                test_window=test_window,
                purge_gap=purge_gap,
            )
        )
        test_end -= effective_step
    slices.reverse()
    return slices


def mask_for(
    timestamps: Sequence[datetime],
    span_start: datetime,
    span_end: datetime,
) -> list[bool]:
    return [span_start <= ts < span_end for ts in timestamps]


def assert_no_leakage(slice_: WalkForwardSlice) -> None:
    """Hard-error if any window overlaps; should be cheap to call before fit."""
    if slice_.purge_gap <= timedelta(0):
        raise ValueError("purge_gap must be positive")
    if slice_.train_end > slice_.validation_start:
        raise ValueError("train_end must be <= validation_start")
    if slice_.validation_end > slice_.test_start:
        raise ValueError("validation_end must be <= test_start")
    if slice_.train_end + slice_.purge_gap > slice_.validation_start:
        raise ValueError("purge gap between train and validation is too small")
    if slice_.validation_end + slice_.purge_gap > slice_.test_start:
        raise ValueError("purge gap between validation and test is too small")


def to_utc(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)
    if isinstance(value, str):
        if value.endswith("Z"):
            value = value[:-1] + "+00:00"
        return datetime.fromisoformat(value).astimezone(timezone.utc)
    raise TypeError(f"unsupported timestamp type: {type(value)!r}")
