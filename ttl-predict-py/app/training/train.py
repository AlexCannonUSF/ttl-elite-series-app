"""LightGBM blender trainer following Prediction Engine Spec §6.

The trainer:

1. Loads a tabular dataset whose columns include every feature in the
   chosen variant's catalogue plus ``decided_at_utc`` and ``label``.
2. Builds a walk-forward (train / validation / test) split with a 4-hour
   purge gap.
3. Fits LightGBM with the §6.3 hyperparameters and early stopping on the
   validation slice.
4. Scores the test slice and evaluates the §6.4 / §7.5 acceptance gates
   against a market-baseline column (``market_prob_top`` — devigged in
   production; from synthetic seeds in tests).
5. Persists the model binary, feature registry, gate report, and a
   regenerated model card under
   ``models/prediction/{variant}-{version}/``.

The trainer is callable from Python ``train_blender(...)`` for tests, and
from the CLI ``python -m app.training.cli train``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from typing import Literal, Sequence, cast

from . import gates
from .calibration import (
    CalibrationBundle,
    DEFAULT_ALPHA,
    IsotonicCalibrator,
    MondrianGroupKey,
    fit_isotonic,
    fit_mondrian_split_conformal,
    fit_platt,
)
from .features import FeatureCatalogue, catalogue_for, validate_columns
from .walk_forward import (
    DEFAULT_PURGE_GAP,
    DEFAULT_TEST_WINDOW,
    DEFAULT_TRAIN_WINDOW,
    DEFAULT_VALIDATION_WINDOW,
    WalkForwardSlice,
    assert_no_leakage,
    to_utc,
    walk_forward_slice,
)


LGB_PARAMS = {
    "objective": "binary",
    "metric": "binary_logloss",
    "learning_rate": 0.03,
    "num_leaves": 63,
    "min_data_in_leaf": 200,
    "feature_fraction": 0.75,
    "bagging_fraction": 0.75,
    "bagging_freq": 5,
    "max_depth": -1,
    "verbose": -1,
    "deterministic": True,
}
NUM_BOOST_ROUND = 1500
EARLY_STOPPING_ROUNDS = 50
CalibrationMode = Literal["platt", "platt-isotonic"]


@dataclass
class TrainingResult:
    variant: str
    catalogue: FeatureCatalogue
    slice: WalkForwardSlice
    n_train_rows: int
    n_validation_rows: int
    n_test_rows: int
    best_iteration: int
    gate_report: gates.GateReport
    artefact_dir: Path
    test_predictions_path: Path
    model_path: Path
    feature_registry_path: Path
    model_card_path: Path
    platt_path: Path | None = None
    isotonic_path: Path | None = None
    conformal_path: Path | None = None
    calibration_bundle: CalibrationBundle | None = None
    metadata: dict = field(default_factory=dict)


def train_blender(
    *,
    rows,
    variant: str = "a",
    test_end: datetime | None = None,
    train_window=DEFAULT_TRAIN_WINDOW,
    validation_window=DEFAULT_VALIDATION_WINDOW,
    test_window=DEFAULT_TEST_WINDOW,
    purge_gap=DEFAULT_PURGE_GAP,
    output_root: Path | str = Path("models/prediction"),
    model_version: str = "v3.0.0",
    market_baseline_column: str = "market_prob_top",
    label_column: str = "label",
    timestamp_column: str = "decided_at_utc",
    calibration_mode: CalibrationMode = "platt-isotonic",
    training_half_life_days: float | None = None,
) -> TrainingResult:
    """Fit a LightGBM blender on ``rows`` and persist artefacts.

    ``rows`` may be a pandas DataFrame or a list of dicts; the trainer
    coerces to a DataFrame internally.
    """
    import lightgbm as lgb  # imported lazily so importing this module is cheap
    import numpy as np
    import pandas as pd

    if not isinstance(rows, pd.DataFrame):
        df = pd.DataFrame(list(rows))
    else:
        df = rows.copy()

    catalogue = catalogue_for(variant)
    missing = validate_columns(catalogue, df.columns)
    if missing:
        raise ValueError(f"missing feature columns: {missing}")
    if label_column not in df.columns:
        raise ValueError(f"missing label column {label_column!r}")
    if timestamp_column not in df.columns:
        raise ValueError(f"missing timestamp column {timestamp_column!r}")
    if market_baseline_column not in df.columns:
        raise ValueError(f"missing market baseline column {market_baseline_column!r}")

    df = df.copy()
    df[timestamp_column] = df[timestamp_column].apply(to_utc)

    if test_end is None:
        test_end = df[timestamp_column].max()
    if not isinstance(test_end, datetime):
        test_end = to_utc(test_end)

    slice_ = walk_forward_slice(
        test_end=test_end,
        train_window=train_window,
        validation_window=validation_window,
        test_window=test_window,
        purge_gap=purge_gap,
    )
    assert_no_leakage(slice_)

    train_df = df[df[timestamp_column].apply(slice_.contains_train)]
    val_df = df[df[timestamp_column].apply(slice_.contains_validation)]
    test_df = df[df[timestamp_column].apply(slice_.contains_test)]
    for label, frame in [("train", train_df), ("validation", val_df), ("test", test_df)]:
        if frame.empty:
            raise ValueError(f"{label} slice is empty for test_end={test_end.isoformat()}")

    feature_columns = catalogue.names
    categorical_columns = catalogue.categorical_names
    for column in categorical_columns:
        train_df[column] = train_df[column].astype("category")
        val_df[column] = val_df[column].astype("category")
        test_df[column] = test_df[column].astype("category")

    # 2026-05-19: time-decayed sample weights. When training_half_life_days is
    # set, each training row gets weight = exp(-(test_start - row_ts).days /
    # half_life). This biases the model toward the recent matchup distribution
    # to fix the tail under-confidence diagnosed in the Variant A/B walk-forward
    # reports. Validation rows are NOT weighted — early stopping should reflect
    # the current matchup distribution evenly.
    train_weights = None
    weight_summary: dict | None = None
    if training_half_life_days is not None and training_half_life_days > 0:
        # Anchor the decay at the test slice start; that's "now" for the model.
        anchor = slice_.test_start
        ages = (anchor - train_df[timestamp_column]).apply(
            lambda delta: max(delta.total_seconds() / 86400.0, 0.0)
        )
        train_weights = np.exp(-ages.to_numpy() / float(training_half_life_days))
        weight_summary = {
            "half_life_days": float(training_half_life_days),
            "anchor": anchor.isoformat(),
            "min": float(train_weights.min()),
            "max": float(train_weights.max()),
            "mean": float(train_weights.mean()),
            "effective_n": float(
                (train_weights.sum() ** 2) / float((train_weights ** 2).sum())
            ),
        }

    train_data = lgb.Dataset(
        train_df[feature_columns],
        label=train_df[label_column].astype(int),
        categorical_feature=categorical_columns or "auto",
        free_raw_data=False,
        weight=train_weights,
    )
    val_data = lgb.Dataset(
        val_df[feature_columns],
        label=val_df[label_column].astype(int),
        categorical_feature=categorical_columns or "auto",
        reference=train_data,
        free_raw_data=False,
    )

    booster = lgb.train(
        params=LGB_PARAMS,
        train_set=train_data,
        num_boost_round=NUM_BOOST_ROUND,
        valid_sets=[val_data],
        valid_names=["validation"],
        callbacks=[
            lgb.early_stopping(stopping_rounds=EARLY_STOPPING_ROUNDS, verbose=False),
            lgb.log_evaluation(period=0),
        ],
    )

    best_iteration = booster.best_iteration or NUM_BOOST_ROUND

    # ----- Calibration (Prediction Engine Spec §7 + §8) -----
    raw_val_probs = booster.predict(val_df[feature_columns], num_iteration=best_iteration)
    val_labels = val_df[label_column].astype(int).tolist()
    val_groups = [
        _mondrian_group_key(row) for _, row in val_df.iterrows()
    ]
    raw_val_prob_list = list(map(float, raw_val_probs))
    platt = fit_platt(raw_val_prob_list, val_labels, version=model_version)
    platt_validation_probs = platt.apply(raw_val_prob_list)
    resolved_calibration_mode = _resolve_calibration_mode(calibration_mode)
    if resolved_calibration_mode == "platt":
        isotonic = _identity_isotonic(version=model_version)
    else:
        isotonic = fit_isotonic(platt_validation_probs, val_labels, version=model_version)
    calibrated_val_probs = isotonic.apply(platt.apply(list(map(float, raw_val_probs))))
    conformal = fit_mondrian_split_conformal(
        probs=calibrated_val_probs,
        labels=val_labels,
        group_keys=val_groups,
        alpha=DEFAULT_ALPHA,
        version=model_version,
    )
    bundle = CalibrationBundle(platt=platt, isotonic=isotonic, conformal=conformal)

    raw_test_probs = booster.predict(test_df[feature_columns], num_iteration=best_iteration)
    test_probs = bundle.apply(list(map(float, raw_test_probs)))
    test_labels = test_df[label_column].astype(int).tolist()
    market_probs = test_df[market_baseline_column].astype(float).tolist()
    report = gates.evaluate(
        probs=test_probs,
        labels=test_labels,
        market_probs=market_probs,
    )

    artefact_dir = Path(output_root) / f"variant-{variant.lower()}-{model_version}"
    artefact_dir.mkdir(parents=True, exist_ok=True)

    model_path = artefact_dir / "blender.lgb.model"
    booster.save_model(str(model_path), num_iteration=best_iteration)

    registry_path = artefact_dir / "feature_registry.json"
    registry_path.write_text(
        json.dumps(catalogue.to_registry_dict(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    platt_path = artefact_dir / "platt.json"
    platt_path.write_text(json.dumps(platt.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    isotonic_path = artefact_dir / "isotonic.json"
    isotonic_path.write_text(json.dumps(isotonic.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    conformal_path = artefact_dir / "conformal.json"
    conformal_path.write_text(json.dumps(conformal.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")

    predictions_path = artefact_dir / "test_predictions.parquet"
    pd.DataFrame(
        {
            "decided_at_utc": test_df[timestamp_column].astype(str).values,
            "predicted_p_top": list(map(float, test_probs)),
            "label": test_labels,
            "market_p_top": market_probs,
        }
    ).to_parquet(predictions_path, index=False)

    from .model_card import write_model_card

    model_card_path = write_model_card(
        directory=artefact_dir,
        variant=variant,
        catalogue=catalogue,
        slice_=slice_,
        report=report,
        booster_metadata={
            "best_iteration": best_iteration,
            "calibration_mode": resolved_calibration_mode,
            "num_features": len(feature_columns),
            "params": LGB_PARAMS,
            "model_version": model_version,
            "trained_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "train_rows": int(len(train_df)),
            "validation_rows": int(len(val_df)),
            "test_rows": int(len(test_df)),
            "training_weights": weight_summary,
        },
    )

    return TrainingResult(
        variant=variant,
        catalogue=catalogue,
        slice=slice_,
        n_train_rows=int(len(train_df)),
        n_validation_rows=int(len(val_df)),
        n_test_rows=int(len(test_df)),
        best_iteration=best_iteration,
        gate_report=report,
        artefact_dir=artefact_dir,
        test_predictions_path=predictions_path,
        model_path=model_path,
        feature_registry_path=registry_path,
        model_card_path=model_card_path,
        platt_path=platt_path,
        isotonic_path=isotonic_path,
        conformal_path=conformal_path,
        calibration_bundle=bundle,
        metadata={
            "model_version": model_version,
            "feature_schema_hash": catalogue.schema_hash(),
            "calibration_mode": resolved_calibration_mode,
            "training_weights": weight_summary,
        },
    )


def _identity_isotonic(*, version: str) -> IsotonicCalibrator:
    return IsotonicCalibrator(
        x_breakpoints=(0.0, 1.0),
        y_breakpoints=(0.0, 1.0),
        version=version,
    )


def _resolve_calibration_mode(mode: str) -> CalibrationMode:
    legacy_platt_only = os.environ.get("TTL_TRAIN_PLATT_ONLY", "").lower() in {"1", "true", "yes"}
    if legacy_platt_only:
        return "platt"
    if mode not in {"platt", "platt-isotonic"}:
        raise ValueError(f"unsupported calibration mode: {mode!r}")
    return cast(CalibrationMode, mode)


def _mondrian_group_key(row) -> str:
    best_of = int(row["match.best_of"]) if "match.best_of" in row else 0
    if "is_in_play" in row:
        is_in_play = bool(row["is_in_play"])
    else:
        live_signal = 0.0
        for column in ("live.points_top", "live.points_bot", "live.games_top", "live.games_bot"):
            if column in row and row[column] is not None:
                try:
                    live_signal = max(live_signal, float(row[column]))
                except (TypeError, ValueError):
                    continue
        is_in_play = live_signal > 0.0
    is_major = bool(row["match.is_major_event"]) if "match.is_major_event" in row else False
    return MondrianGroupKey(best_of=best_of, is_in_play=is_in_play, is_major_event=is_major).encode()


def gates_summary(report: gates.GateReport) -> dict:
    return {
        "ece": report.ece,
        "max_bin_deviation": report.max_bin_deviation,
        "brier_score": report.brier_score,
        "brier_skill_score": report.brier_skill_score,
        "overall_pass": report.overall_pass(),
        "passes": dict(report.passes),
        "bins": [
            {
                "lower": b.lower,
                "upper": b.upper,
                "count": b.count,
                "mean_predicted": b.mean_predicted,
                "mean_observed": b.mean_observed,
            }
            for b in report.bins
        ],
    }
