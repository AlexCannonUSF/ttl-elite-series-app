"""Feature catalogue for the v3 LightGBM blender.

Maps Prediction Engine Spec §3.1–§3.8 to concrete column names that the
training pipeline expects. Two variants are exposed:

- ``VARIANT_A`` — no market features (§3.1, §3.2, §3.3, §3.4, §3.5, §3.7, §3.8).
- ``VARIANT_B`` — Variant A plus the §3.6 devigged market columns.

A FeatureRegistry hash is published with every model artefact; a model
refuses to score a feature vector whose registry hash differs from the
trained hash (§3.10 hard-error contract).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from hashlib import sha256
import json
from typing import Iterable, Sequence


@dataclass(frozen=True)
class Feature:
    name: str
    unit: str
    source: str
    max_age_ms: int
    categorical: bool = False

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "unit": self.unit,
            "source": self.source,
            "max_age_ms": self.max_age_ms,
        }


# §3.1 Identity and context — categorical/scalar mix
_IDENTITY = [
    Feature("match.event_code", "id", "match", 0, categorical=True),
    Feature("match.round", "id", "match", 0, categorical=True),
    Feature("match.best_of", "int", "match", 0),
    Feature("match.is_televised", "bool", "match", 0),
    Feature("match.is_major_event", "bool", "match", 0),
    Feature("match.table_number", "id", "match", 0, categorical=True),
    Feature("match.day_of_week", "int", "match", 0),
    Feature("match.minutes_to_start", "minutes", "scheduler", 60_000),
    Feature("match.is_backup_table", "bool", "match", 0),
]

# §3.2 Raters
_RATERS = [
    Feature("rater.glicko.top.mu", "rating", "glicko2", 86_400_000),
    Feature("rater.glicko.bot.mu", "rating", "glicko2", 86_400_000),
    Feature("rater.glicko.top.phi", "rating", "glicko2", 86_400_000),
    Feature("rater.glicko.bot.phi", "rating", "glicko2", 86_400_000),
    Feature("rater.glicko.delta_mu", "rating", "glicko2", 86_400_000),
    Feature("rater.glicko.delta_phi_sum", "rating", "glicko2", 86_400_000),
    Feature("rater.ts2.top.mu", "rating", "trueskill2", 86_400_000),
    Feature("rater.ts2.bot.mu", "rating", "trueskill2", 86_400_000),
    Feature("rater.ts2.top.sigma", "rating", "trueskill2", 86_400_000),
    Feature("rater.ts2.bot.sigma", "rating", "trueskill2", 86_400_000),
    Feature("rater.ts2.skill_gap", "rating", "trueskill2", 86_400_000),
    Feature("rater.wenglin.delta", "prob", "openskill", 86_400_000),
    Feature("rater.ensemble.delta", "prob", "ensemble", 86_400_000),
]


def _form_features(sides: Sequence[str], windows: Sequence[int]) -> list[Feature]:
    fields = [
        ("win_rate", "rate"),
        ("dominance", "rate"),
        ("straight_set_rate", "rate"),
        ("median_margin", "points"),
        ("gap_to_prev_match_ms", "millis"),
        ("completeness", "rate"),
    ]
    out: list[Feature] = []
    for side in sides:
        for window in windows:
            for field_name, unit in fields:
                out.append(
                    Feature(
                        name=f"form.{side}.{window}.{field_name}",
                        unit=unit,
                        source="feature-builder",
                        max_age_ms=900_000,
                    )
                )
    return out


# §3.3 Recent form windows {5, 10, 25} × {top, bot}
_FORM = _form_features(["top", "bot"], [5, 10, 25])

# §3.4 H2H
_H2H = [
    Feature("h2h.count", "int", "h2h-builder", 900_000),
    Feature("h2h.win_rate_top", "rate", "h2h-builder", 900_000),
    Feature("h2h.recency_decay_wins_top", "rate", "h2h-builder", 900_000),
    Feature("h2h.median_margin_top", "points", "h2h-builder", 900_000),
    Feature("h2h.same_event_h2h_count", "int", "h2h-builder", 900_000),
]

# §3.5 Surface/venue/schedule
_CONTEXT = [
    Feature("ctx.same_day_prior_matches_top", "int", "schedule", 60_000),
    Feature("ctx.same_day_prior_matches_bot", "int", "schedule", 60_000),
    Feature("ctx.rest_gap_minutes_top", "minutes", "schedule", 60_000),
    Feature("ctx.rest_gap_minutes_bot", "minutes", "schedule", 60_000),
    Feature("ctx.travel_tz_delta_top", "hours", "schedule", 60_000),
    Feature("ctx.venue_familiarity_top", "rate", "schedule", 60_000),
]

# §3.6 Market microstructure (Variant B only)
_MARKET = [
    Feature("odds.open.top.decimal", "decimal", "market", 60_000),
    Feature("odds.open.bot.decimal", "decimal", "market", 60_000),
    Feature("odds.latest.top.decimal", "decimal", "market", 1_000),
    Feature("odds.latest.bot.decimal", "decimal", "market", 1_000),
    Feature("odds.move.top_bps_since_open", "bps", "market", 1_000),
    Feature("odds.move.top_bps_last_5m", "bps", "market", 1_000),
    Feature("odds.overround_bps", "bps", "market", 1_000),
    Feature("odds.overround_bps_trend_1m", "bps", "market", 1_000),
    Feature("odds.dev.top.shin", "prob", "devig", 1_000),
    Feature("odds.dev.bot.shin", "prob", "devig", 1_000),
    Feature("odds.dev.top.power", "prob", "devig", 1_000),
    Feature("odds.dev.bot.power", "prob", "devig", 1_000),
    Feature("odds.dev.top.multiplicative", "prob", "devig", 1_000),
    Feature("odds.dev.bot.multiplicative", "prob", "devig", 1_000),
    Feature("odds.dev.top.consensus", "prob", "devig", 1_000),
    Feature("odds.liquidity_proxy", "ticks", "market", 60_000),
]

# §3.7 Live-in-progress
_LIVE = [
    Feature("live.games_top", "int", "stream-cv", 30_000),
    Feature("live.games_bot", "int", "stream-cv", 30_000),
    Feature("live.points_top", "int", "stream-cv", 30_000),
    Feature("live.points_bot", "int", "stream-cv", 30_000),
    Feature("live.server", "id", "stream-cv", 30_000, categorical=True),
    Feature("live.is_deuce", "bool", "stream-cv", 30_000),
    Feature("live.point_differential", "points", "stream-cv", 30_000),
    Feature("live.current_lead_top", "points", "stream-cv", 30_000),
    Feature("live.time_since_last_point_s", "seconds", "stream-cv", 30_000),
]

# §3.8 Data quality
_DATA_QUALITY = [
    Feature("dq.feed_ticks_1m.top", "int", "feed-health", 60_000),
    Feature("dq.feed_ticks_1m.bot", "int", "feed-health", 60_000),
    Feature("dq.mirror_disagreement_flag", "bool", "score-truth", 60_000),
    Feature("dq.stream_cv_present", "bool", "stream-cv", 60_000),
    Feature("dq.player_canonicalised", "bool", "identity", 60_000),
    Feature("dq.feature_completeness", "rate", "feature-builder", 0),
]


@dataclass(frozen=True)
class FeatureCatalogue:
    name: str
    features: tuple[Feature, ...] = field(repr=False)

    @property
    def names(self) -> list[str]:
        return [feature.name for feature in self.features]

    @property
    def categorical_names(self) -> list[str]:
        return [feature.name for feature in self.features if feature.categorical]

    def schema_hash(self) -> str:
        """sha256 of the canonical JSON of (name, unit, source, max_age_ms)."""
        canonical = sorted([feature.to_dict() for feature in self.features], key=lambda d: d["name"])
        return sha256(json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()

    def to_registry_dict(self) -> dict:
        return {
            "version": self.name,
            "feature_count": len(self.features),
            "schema_hash": self.schema_hash(),
            "categorical": self.categorical_names,
            "features": [feature.to_dict() for feature in self.features],
        }


VARIANT_A = FeatureCatalogue(
    name="v3.0.0-variant-a",
    features=tuple(_IDENTITY + _RATERS + _FORM + _H2H + _CONTEXT + _LIVE + _DATA_QUALITY),
)

VARIANT_B = FeatureCatalogue(
    name="v3.0.0-variant-b",
    features=tuple(_IDENTITY + _RATERS + _FORM + _H2H + _CONTEXT + _MARKET + _LIVE + _DATA_QUALITY),
)


def catalogue_for(variant: str) -> FeatureCatalogue:
    key = (variant or "").strip().lower()
    if key in {"a", "variant-a", "no-market"}:
        return VARIANT_A
    if key in {"b", "variant-b", "with-market"}:
        return VARIANT_B
    raise ValueError(f"unknown blender variant: {variant!r}")


def validate_columns(catalogue: FeatureCatalogue, columns: Iterable[str]) -> list[str]:
    """Return the list of feature names missing from ``columns``."""
    available = set(columns)
    return [name for name in catalogue.names if name not in available]
