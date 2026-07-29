"""Inference-time wiring for the LightGBM blender.

Loads the four committed artefacts from ``models/prediction/variant-x-vY.Y.Z/``
on startup:

- ``blender.lgb.model``
- ``feature_registry.json``
- ``platt.json``
- ``isotonic.json``
- ``conformal.json``

Exposes ``BlenderService.score(...)`` which:

1. Verifies the request's ``featureSchemaHash`` matches the primary
   (Variant A) trained hash — Prediction Engine Spec §3.10 hard error.
2. Builds the feature row in the order recorded in the primary registry.
3. Scores via LightGBM, applies Platt + isotonic, and computes a
   Mondrian split-conformal uncertainty block.
4. When a secondary (Variant B) artefact directory is loaded, also scores
   the request through Variant B and embeds the result as a ``sanity``
   block — Prediction Engine Spec §6.3 + §9.3. The secondary skips the
   schema-hash check because the request only carries the primary hash; a
   request whose features don't satisfy Variant B's catalogue silently
   gets zero-filled (the diff log captures the gap so operators can spot
   it).

Loading is best-effort: a missing primary leaves the service in state
``MODEL_MISSING`` so the FastAPI router can return ``503`` for
``/v1/blend`` while the rest of the service stays up. A missing secondary
just means the response has no ``sanity`` block — primary scoring still
works.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
import json
import logging
import os
from pathlib import Path
import time
from typing import Any, Iterable, Mapping, Optional

from ..training.calibration import (
    CalibrationBundle,
    IsotonicCalibrator,
    MondrianGroupKey,
    MondrianSplitConformal,
    PlattCalibrator,
)


log = logging.getLogger("ttl.predict.blender")


class BlenderState(str, Enum):
    READY = "ready"
    MODEL_MISSING = "model_missing"
    INIT_FAILED = "init_failed"


class BlenderError(Exception):
    """Raised when /v1/blend cannot fulfil a request."""

    def __init__(self, message: str, status_code: int = 503):
        super().__init__(message)
        self.status_code = status_code


@dataclass(frozen=True)
class FeatureRegistry:
    version: str
    schema_hash: str
    feature_names: tuple[str, ...]
    categorical_names: frozenset[str]

    @classmethod
    def load(cls, path: Path) -> "FeatureRegistry":
        payload = json.loads(path.read_text(encoding="utf-8"))
        return cls(
            version=str(payload.get("version", "unknown")),
            schema_hash=str(payload["schema_hash"]),
            feature_names=tuple(
                feature["name"] for feature in payload.get("features", [])
            ),
            categorical_names=frozenset(payload.get("categorical", [])),
        )


@dataclass(frozen=True)
class BlenderArtefacts:
    booster: Any
    registry: FeatureRegistry
    calibration: CalibrationBundle
    artefact_dir: Path
    model_version: str


def _booster_predict(booster, row: list[Any]) -> float:
    """Score a single row. Lazily imports numpy so unit tests can fake the
    booster without depending on the heavy stack."""
    if hasattr(booster, "predict_row"):
        return float(booster.predict_row(row))
    try:
        import numpy as np

        result = booster.predict(np.array([row]))
        return float(result[0])
    except Exception as exc:  # pragma: no cover — fail loud at runtime
        raise BlenderError(f"booster.predict failed: {exc}", status_code=500) from exc


def _load_artefacts(directory: Path) -> BlenderArtefacts | None:
    if not directory.is_dir() or not (directory / "blender.lgb.model").exists():
        return None
    try:
        registry = FeatureRegistry.load(directory / "feature_registry.json")
        calibration = CalibrationBundle(
            platt=PlattCalibrator.from_dict(
                json.loads((directory / "platt.json").read_text(encoding="utf-8"))
            ),
            isotonic=IsotonicCalibrator.from_dict(
                json.loads((directory / "isotonic.json").read_text(encoding="utf-8"))
            ),
            conformal=MondrianSplitConformal.from_dict(
                json.loads((directory / "conformal.json").read_text(encoding="utf-8"))
            ),
        )
        import lightgbm as lgb

        booster = lgb.Booster(model_file=str(directory / "blender.lgb.model"))
        return BlenderArtefacts(
            booster=booster,
            registry=registry,
            calibration=calibration,
            artefact_dir=directory,
            model_version=registry.version,
        )
    except Exception as exc:
        log.exception("[blender] failed to load artefacts from %s: %s", directory, exc)
        return None


class BlenderService:
    """Wires the primary + optional secondary blender artefacts behind ``score``."""

    DEFAULT_PRIMARY_PATH = Path("models/prediction/variant-a-v3.0.0")
    DEFAULT_SECONDARY_PATH = Path("models/prediction/variant-b-v3.0.0")

    def __init__(self,
                 primary: BlenderArtefacts | None = None,
                 state: BlenderState = BlenderState.MODEL_MISSING,
                 secondary: BlenderArtefacts | None = None,
                 artefacts: BlenderArtefacts | None = None):
        if primary is None and artefacts is not None:
            primary = artefacts
        self._primary = primary
        self._secondary = secondary
        self._state = state

    # --- construction ------------------------------------------------------

    @classmethod
    def load(cls,
             artefact_dir: Path | None = None,
             secondary_dir: Path | None = None) -> "BlenderService":
        primary_dir = artefact_dir or Path(
            os.environ.get("TTL_BLENDER_ARTEFACT_DIR", str(cls.DEFAULT_PRIMARY_PATH))
        )
        sanity_dir = secondary_dir or Path(
            os.environ.get("TTL_BLENDER_SANITY_ARTEFACT_DIR", str(cls.DEFAULT_SECONDARY_PATH))
        )

        primary = _load_artefacts(primary_dir)
        if primary is None:
            log.info("[blender] primary artefact directory missing at %s; service will return 503", primary_dir)
            return cls(primary=None, state=BlenderState.MODEL_MISSING)

        secondary = _load_artefacts(sanity_dir)
        if secondary is None:
            log.info("[blender] sanity (Variant B) artefacts missing at %s; sanity block disabled", sanity_dir)
        else:
            log.info(
                "[blender] sanity (Variant B) loaded; registry=%s schema_hash=%s",
                secondary.registry.version, secondary.registry.schema_hash,
            )
        log.info(
            "[blender] primary artefacts loaded; registry=%s schema_hash=%s",
            primary.registry.version, primary.registry.schema_hash,
        )
        return cls(primary=primary, state=BlenderState.READY, secondary=secondary)

    @classmethod
    def with_artefacts(cls,
                       artefacts: BlenderArtefacts,
                       secondary: BlenderArtefacts | None = None) -> "BlenderService":
        return cls(primary=artefacts, state=BlenderState.READY, secondary=secondary)

    # --- API ---------------------------------------------------------------

    @property
    def state(self) -> BlenderState:
        return self._state

    def status_dict(self) -> dict[str, object]:
        if self._primary is None:
            return {"state": self._state.value}
        status: dict[str, object] = {
            "state": self._state.value,
            "model_version": self._primary.model_version,
            "feature_schema_hash": self._primary.registry.schema_hash,
            "feature_count": len(self._primary.registry.feature_names),
            "artefact_dir": str(self._primary.artefact_dir),
        }
        if self._secondary is not None:
            status["sanity"] = {
                "model_version": self._secondary.model_version,
                "feature_schema_hash": self._secondary.registry.schema_hash,
                "feature_count": len(self._secondary.registry.feature_names),
                "artefact_dir": str(self._secondary.artefact_dir),
            }
        return status

    def score(
        self,
        *,
        match_id: str,
        feature_schema_hash: str,
        features: Mapping[str, Any],
        is_in_play: bool,
        is_major_event: bool,
    ) -> dict[str, Any]:
        if self._primary is None:
            raise BlenderError(
                f"blender service is not ready ({self._state.value})",
                status_code=503,
            )
        registry = self._primary.registry
        if feature_schema_hash != registry.schema_hash:
            raise BlenderError(
                "feature schema hash mismatch — model trained on "
                f"{registry.schema_hash!r}, request carries {feature_schema_hash!r}",
                status_code=409,
            )

        best_of = _safe_int(features.get("match.best_of"), default=5)
        group_key = MondrianGroupKey(best_of=best_of, is_in_play=is_in_play, is_major_event=is_major_event)
        primary_block = self._score_artefacts(self._primary, features, group_key)
        platt_version = self._primary.calibration.platt.version
        isotonic_version = self._primary.calibration.isotonic.version
        conformal_version = self._primary.calibration.conformal.version

        response: dict[str, Any] = {
            "matchId": match_id,
            "modelVersion": self._primary.model_version,
            "calibratorVersion": f"platt+iso-{platt_version}",
            "conformalVersion": f"mondrian-split-{conformal_version}",
            "featureSchemaHash": registry.schema_hash,
            "pTop": {"value": primary_block["calibrated"], "rawValue": primary_block["raw"]},
            "pBot": {
                "value": 1.0 - primary_block["calibrated"],
                "rawValue": 1.0 - primary_block["raw"],
            },
            "uncertainty": _uncertainty_block(primary_block["uncertainty"], isotonic_version),
            "computedAtUtc": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "latencyMs": primary_block["latency_ms"],
        }

        if self._secondary is not None:
            sanity_block = self._score_artefacts(self._secondary, features, group_key)
            sanity_platt = self._secondary.calibration.platt.version
            sanity_isotonic = self._secondary.calibration.isotonic.version
            sanity_conformal = self._secondary.calibration.conformal.version
            response["sanity"] = {
                "variant": "B",
                "modelVersion": self._secondary.model_version,
                "calibratorVersion": f"platt+iso-{sanity_platt}",
                "conformalVersion": f"mondrian-split-{sanity_conformal}",
                "featureSchemaHash": self._secondary.registry.schema_hash,
                "pTop": {"value": sanity_block["calibrated"], "rawValue": sanity_block["raw"]},
                "pBot": {
                    "value": 1.0 - sanity_block["calibrated"],
                    "rawValue": 1.0 - sanity_block["raw"],
                },
                "uncertainty": _uncertainty_block(sanity_block["uncertainty"], sanity_isotonic),
                "absoluteDiffPTop": abs(primary_block["calibrated"] - sanity_block["calibrated"]),
                "latencyMs": sanity_block["latency_ms"],
            }

        return response

    def _score_artefacts(self,
                         artefacts: BlenderArtefacts,
                         features: Mapping[str, Any],
                         group_key: MondrianGroupKey) -> dict[str, Any]:
        row = _build_row(features, artefacts.registry.feature_names)
        start = time.perf_counter()
        raw = _booster_predict(artefacts.booster, row)
        raw_clamped = max(0.0, min(1.0, raw))
        calibrated = artefacts.calibration.calibrate(raw_clamped)
        uncertainty = artefacts.calibration.conformal.uncertainty(calibrated, group_key)
        latency_ms = (time.perf_counter() - start) * 1000.0
        return {
            "raw": raw_clamped,
            "calibrated": calibrated,
            "uncertainty": uncertainty,
            "latency_ms": latency_ms,
        }


def _uncertainty_block(uncertainty, isotonic_version: str) -> dict[str, Any]:
    return {
        "coverage": uncertainty.coverage,
        "alpha": uncertainty.alpha,
        "label": uncertainty.label,
        "intervalLow": uncertainty.intervalLow,
        "intervalHigh": uncertainty.intervalHigh,
        "groupKey": uncertainty.groupKey,
        "quantile": uncertainty.quantile,
        "method": uncertainty.method,
        "version": isotonic_version,
    }


# --- helpers ---------------------------------------------------------------


def _build_row(features: Mapping[str, Any], feature_names: Iterable[str]) -> list[Any]:
    row: list[Any] = []
    for name in feature_names:
        value = features.get(name)
        if isinstance(value, bool):
            row.append(1.0 if value else 0.0)
        elif value is None:
            row.append(0.0)
        elif isinstance(value, (int, float)):
            row.append(float(value))
        else:
            row.append(value)
    return row


def _safe_int(value: Any, *, default: int) -> int:
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


__all__ = [
    "BlenderArtefacts",
    "BlenderError",
    "BlenderService",
    "BlenderState",
    "FeatureRegistry",
]
