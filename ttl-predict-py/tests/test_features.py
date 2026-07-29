"""Tests for the v3 blender feature catalogue."""

from __future__ import annotations

import pytest

from app.training import features


def test_variant_a_excludes_market_features():
    names = set(features.VARIANT_A.names)
    market_prefixes = ("odds.open.", "odds.latest.", "odds.move.", "odds.overround", "odds.dev.", "odds.liquidity")
    for name in names:
        assert not name.startswith(market_prefixes), name


def test_variant_b_is_superset_of_variant_a():
    a = set(features.VARIANT_A.names)
    b = set(features.VARIANT_B.names)
    assert a.issubset(b)
    assert "odds.dev.top.consensus" in b


def test_schema_hash_changes_when_feature_set_changes():
    hash_a = features.VARIANT_A.schema_hash()
    hash_b = features.VARIANT_B.schema_hash()
    assert hash_a != hash_b


def test_catalogue_for_lookup_is_case_insensitive():
    assert features.catalogue_for("A").name == features.VARIANT_A.name
    assert features.catalogue_for("Variant-B").name == features.VARIANT_B.name
    assert features.catalogue_for("with-market").name == features.VARIANT_B.name


def test_unknown_variant_raises():
    with pytest.raises(ValueError):
        features.catalogue_for("mystery")


def test_validate_columns_reports_missing():
    missing = features.validate_columns(features.VARIANT_A, ["match.event_code"])
    assert "rater.ensemble.delta" in missing
    assert missing == [n for n in features.VARIANT_A.names if n != "match.event_code"]


def test_registry_dict_contains_schema_hash_and_count():
    payload = features.VARIANT_A.to_registry_dict()
    assert payload["version"] == features.VARIANT_A.name
    assert payload["feature_count"] == len(features.VARIANT_A.features)
    assert payload["schema_hash"] == features.VARIANT_A.schema_hash()
    assert "match.event_code" in payload["categorical"]


def test_form_window_combinatorics():
    names = set(features.VARIANT_A.names)
    for side in ("top", "bot"):
        for window in (5, 10, 25):
            for field in (
                "win_rate",
                "dominance",
                "straight_set_rate",
                "median_margin",
                "gap_to_prev_match_ms",
                "completeness",
            ):
                assert f"form.{side}.{window}.{field}" in names
