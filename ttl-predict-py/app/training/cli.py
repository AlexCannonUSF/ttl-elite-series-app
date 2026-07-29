"""CLI entry point for the blender harness.

Usage::

    python -m app.training.cli train \
        --variant a \
        --data data/blender_training.parquet \
        --output models/prediction \
        --version v3.0.0

    python -m app.training.cli smoke \
        --variant a \
        --output build/blender-smoke
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta
from pathlib import Path

from .features import catalogue_for
from .synthetic import generate
from .train import gates_summary, train_blender
from .walk_forward import (
    DEFAULT_PURGE_GAP,
    DEFAULT_TEST_WINDOW,
    DEFAULT_TRAIN_WINDOW,
    DEFAULT_VALIDATION_WINDOW,
    expanding_walk_forward,
    to_utc,
)


def _add_walk_forward_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--train-days", type=int, default=DEFAULT_TRAIN_WINDOW.days)
    parser.add_argument("--validation-days", type=int, default=DEFAULT_VALIDATION_WINDOW.days)
    parser.add_argument("--test-days", type=int, default=DEFAULT_TEST_WINDOW.days)
    parser.add_argument(
        "--purge-hours",
        type=float,
        default=DEFAULT_PURGE_GAP.total_seconds() / 3600.0,
    )


def _windows(args: argparse.Namespace):
    from datetime import timedelta

    return {
        "train_window": timedelta(days=args.train_days),
        "validation_window": timedelta(days=args.validation_days),
        "test_window": timedelta(days=args.test_days),
        "purge_gap": timedelta(hours=args.purge_hours),
    }


def _train_command(args: argparse.Namespace) -> int:
    import pandas as pd

    data_path = Path(args.data)
    if data_path.suffix.lower() == ".csv":
        df = pd.read_csv(data_path)
    else:
        df = pd.read_parquet(data_path)
    test_end = datetime.fromisoformat(args.test_end) if args.test_end else None
    result = train_blender(
        rows=df,
        variant=args.variant,
        test_end=test_end,
        output_root=Path(args.output),
        model_version=args.version,
        market_baseline_column=args.market_column,
        label_column=args.label_column,
        timestamp_column=args.timestamp_column,
        calibration_mode=args.calibration_mode,
        training_half_life_days=getattr(args, "training_half_life_days", None) or None,
        **_windows(args),
    )
    summary = {
        "variant": result.variant,
        "feature_registry": result.catalogue.name,
        "feature_schema_hash": result.catalogue.schema_hash(),
        "best_iteration": result.best_iteration,
        "rows": {
            "train": result.n_train_rows,
            "validation": result.n_validation_rows,
            "test": result.n_test_rows,
        },
        "slice": result.slice.as_dict(),
        "artefacts": {
            "model": str(result.model_path),
            "feature_registry": str(result.feature_registry_path),
            "model_card": str(result.model_card_path),
            "test_predictions": str(result.test_predictions_path),
        },
        "gates": gates_summary(result.gate_report),
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if result.gate_report.overall_pass() else 1


def _walk_forward_ci_command(args: argparse.Namespace) -> int:
    import pandas as pd

    data_path = Path(args.data)
    if data_path.suffix.lower() == ".csv":
        df = pd.read_csv(data_path)
    else:
        df = pd.read_parquet(data_path)
    if args.timestamp_column not in df.columns:
        raise ValueError(f"missing timestamp column {args.timestamp_column!r}")

    timestamps = df[args.timestamp_column].apply(to_utc)
    history_start = timestamps.min()
    history_end = to_utc(args.test_end) if args.test_end else timestamps.max()
    if args.folds <= 0:
        raise ValueError("folds must be positive")
    windows = _windows(args)
    slices = expanding_walk_forward(
        history_start=history_start,
        history_end=history_end,
        train_window=windows["train_window"],
        validation_window=windows["validation_window"],
        test_window=windows["test_window"],
        purge_gap=windows["purge_gap"],
        step=timedelta(days=args.step_days),
    )
    if not slices:
        raise ValueError("not enough history for a walk-forward CI run")
    selected = slices[-args.folds :]

    output_root = Path(args.output)
    output_root.mkdir(parents=True, exist_ok=True)
    fold_summaries = []
    for index, slice_ in enumerate(selected, start=1):
        version = f"{args.version}-wf{index:02d}-{slice_.test_end:%Y%m%d}"
        result = train_blender(
            rows=df,
            variant=args.variant,
            test_end=slice_.test_end,
            output_root=output_root,
            model_version=version,
            market_baseline_column=args.market_column,
            label_column=args.label_column,
            timestamp_column=args.timestamp_column,
            calibration_mode=args.calibration_mode,
            training_half_life_days=getattr(args, "training_half_life_days", None) or None,
            **windows,
        )
        fold_weight_summary = (result.metadata or {}).get("training_weights")
        fold_summaries.append(
            {
                "fold": index,
                "model_version": version,
                "slice": result.slice.as_dict(),
                "rows": {
                    "train": result.n_train_rows,
                    "validation": result.n_validation_rows,
                    "test": result.n_test_rows,
                },
                "best_iteration": result.best_iteration,
                "artefact_dir": str(result.artefact_dir),
                "gates": gates_summary(result.gate_report),
                "training_weights": fold_weight_summary,
            }
        )

    failed = [fold for fold in fold_summaries if not fold["gates"]["overall_pass"]]
    summary = {
        "variant": args.variant,
        "feature_schema_hash": catalogue_for(args.variant).schema_hash(),
        "calibration_mode": args.calibration_mode,
        "folds_requested": args.folds,
        "folds_ran": len(fold_summaries),
        "folds_passed": len(fold_summaries) - len(failed),
        "overall_pass": not failed,
        "failed_folds": [fold["fold"] for fold in failed],
        "folds": fold_summaries,
    }
    report_path = output_root / f"walk_forward_ci_variant_{args.variant.lower()}_{args.version}.json"
    report_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    summary["report_path"] = str(report_path)
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0 if summary["overall_pass"] else 1


def _smoke_command(args: argparse.Namespace) -> int:
    dataset = generate(variant=args.variant, n_rows=args.rows, days_span=args.span_days, seed=args.seed)
    df = dataset.to_dataframe()
    result = train_blender(
        rows=df,
        variant=args.variant,
        output_root=Path(args.output),
        model_version="smoke",
        calibration_mode=args.calibration_mode,
        training_half_life_days=getattr(args, "training_half_life_days", None) or None,
        **_windows(args),
    )
    summary = {
        "smoke": True,
        "variant": result.variant,
        "rows_generated": len(df),
        "feature_schema_hash": result.catalogue.schema_hash(),
        "gates": gates_summary(result.gate_report),
        "artefacts": {
            "model": str(result.model_path),
            "model_card": str(result.model_card_path),
        },
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ttl-predict-blender")
    sub = parser.add_subparsers(dest="command", required=True)

    train = sub.add_parser("train", help="Train the LightGBM blender against a real dataset.")
    train.add_argument(
        "--training-half-life-days",
        type=float,
        default=None,
        help="If set, each training row is weighted by exp(-age_days / half_life). Mitigates train/test distribution shift by biasing toward recent matchups.",
    )
    train.add_argument("--variant", default="a")
    train.add_argument("--data", required=True, help="Parquet or CSV file with features + label + timestamp + market_prob_top.")
    train.add_argument("--output", default="models/prediction")
    train.add_argument("--version", default="v3.0.0")
    train.add_argument("--market-column", default="market_prob_top")
    train.add_argument("--label-column", default="label")
    train.add_argument("--timestamp-column", default="decided_at_utc")
    train.add_argument("--test-end", default=None, help="ISO-8601 UTC timestamp; defaults to max(decided_at_utc).")
    train.add_argument(
        "--calibration-mode",
        choices=["platt", "platt-isotonic"],
        default="platt-isotonic",
        help="Use Platt only, or Platt followed by isotonic regression.",
    )
    _add_walk_forward_args(train)
    train.set_defaults(func=_train_command)

    ci = sub.add_parser("walk-forward-ci", help="Run promotion gates across multiple walk-forward folds.")
    ci.add_argument(
        "--training-half-life-days",
        type=float,
        default=None,
        help="If set, each training row is weighted by exp(-age_days / half_life). Mitigates train/test distribution shift by biasing toward recent matchups.",
    )
    ci.add_argument("--variant", default="a")
    ci.add_argument("--data", required=True, help="Parquet or CSV file with features + label + timestamp + market_prob_top.")
    ci.add_argument("--output", default="build/walk-forward-ci")
    ci.add_argument("--version", default="v3.0.0")
    ci.add_argument("--market-column", default="market_prob_top")
    ci.add_argument("--label-column", default="label")
    ci.add_argument("--timestamp-column", default="decided_at_utc")
    ci.add_argument("--test-end", default=None, help="ISO-8601 UTC timestamp; defaults to max(decided_at_utc).")
    ci.add_argument("--folds", type=int, default=4, help="Number of latest non-overlapping test folds to run.")
    ci.add_argument("--step-days", type=int, default=DEFAULT_TEST_WINDOW.days)
    ci.add_argument(
        "--calibration-mode",
        choices=["platt", "platt-isotonic"],
        default="platt-isotonic",
        help="Use Platt only, or Platt followed by isotonic regression.",
    )
    _add_walk_forward_args(ci)
    ci.set_defaults(func=_walk_forward_ci_command)

    smoke = sub.add_parser("smoke", help="Run the harness end-to-end on synthetic data.")
    smoke.add_argument("--variant", default="a")
    smoke.add_argument("--rows", type=int, default=2048)
    smoke.add_argument("--span-days", type=int, default=420)
    smoke.add_argument("--seed", type=int, default=42)
    smoke.add_argument("--output", default="build/blender-smoke")
    smoke.add_argument(
        "--calibration-mode",
        choices=["platt", "platt-isotonic"],
        default="platt-isotonic",
        help="Use Platt only, or Platt followed by isotonic regression.",
    )
    _add_walk_forward_args(smoke)
    smoke.set_defaults(func=_smoke_command)

    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
