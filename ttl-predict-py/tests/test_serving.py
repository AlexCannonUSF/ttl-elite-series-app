"""End-to-end tests for /v1/blend and /v1/markov via FastAPI TestClient."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.serving.blender_service import (
    BlenderArtefacts,
    BlenderService,
    BlenderState,
    FeatureRegistry,
)
from app.training import features as features_mod
from app.training.calibration import (
    CalibrationBundle,
    IsotonicCalibrator,
    MondrianSplitConformal,
    PlattCalibrator,
)


class _FakeBooster:
    """Deterministic booster stand-in: returns 0.6 - 0.5*first_feature + 0.5."""

    def __init__(self, prob: float = 0.62) -> None:
        self.prob = prob
        self.calls: list[list[float]] = []

    def predict_row(self, row: list[float]) -> float:
        self.calls.append(row)
        return self.prob


def _bundle() -> CalibrationBundle:
    return CalibrationBundle(
        platt=PlattCalibrator(coef=1.0, intercept=0.0, version="v3.0.0"),
        isotonic=IsotonicCalibrator(
            x_breakpoints=(0.0, 0.5, 1.0),
            y_breakpoints=(0.0, 0.5, 1.0),
            version="v3.0.0",
        ),
        conformal=MondrianSplitConformal(
            alpha=0.1,
            fallback_quantile=0.85,
            quantiles={"5|false|true": 0.85},
            counts={"5|false|true": 200},
            version="v3.0.0",
        ),
    )


def _registry() -> FeatureRegistry:
    cat = features_mod.VARIANT_A
    return FeatureRegistry(
        version=cat.name,
        schema_hash=cat.schema_hash(),
        feature_names=tuple(cat.names),
        categorical_names=frozenset(cat.categorical_names),
    )


def _service_with_fake_booster(prob: float = 0.62) -> tuple[BlenderService, _FakeBooster, FeatureRegistry]:
    booster = _FakeBooster(prob=prob)
    registry = _registry()
    artefacts = BlenderArtefacts(
        booster=booster,
        registry=registry,
        calibration=_bundle(),
        artefact_dir=Path("/tmp/fake"),
        model_version=registry.version,
    )
    return BlenderService.with_artefacts(artefacts), booster, registry


def _variant_b_registry() -> FeatureRegistry:
    cat = features_mod.VARIANT_B
    return FeatureRegistry(
        version=cat.name,
        schema_hash=cat.schema_hash(),
        feature_names=tuple(cat.names),
        categorical_names=frozenset(cat.categorical_names),
    )


def _service_with_sanity(primary_prob: float, sanity_prob: float) -> tuple[BlenderService, _FakeBooster, _FakeBooster, FeatureRegistry, FeatureRegistry]:
    primary_booster = _FakeBooster(prob=primary_prob)
    primary_registry = _registry()
    primary = BlenderArtefacts(
        booster=primary_booster,
        registry=primary_registry,
        calibration=_bundle(),
        artefact_dir=Path("/tmp/variant-a"),
        model_version=primary_registry.version,
    )
    sanity_booster = _FakeBooster(prob=sanity_prob)
    sanity_registry = _variant_b_registry()
    secondary = BlenderArtefacts(
        booster=sanity_booster,
        registry=sanity_registry,
        calibration=_bundle(),
        artefact_dir=Path("/tmp/variant-b"),
        model_version=sanity_registry.version,
    )
    service = BlenderService.with_artefacts(primary, secondary=secondary)
    return service, primary_booster, sanity_booster, primary_registry, sanity_registry


def _client_with_service(service: BlenderService) -> TestClient:
    from app.main import app

    app.state.blender_service = service
    return TestClient(app)


def _features_payload(registry: FeatureRegistry) -> dict[str, object]:
    payload: dict[str, object] = {}
    for name in registry.feature_names:
        if name == "match.best_of":
            payload[name] = 5
        elif name.startswith("live."):
            payload[name] = 0
        elif name == "match.is_major_event":
            payload[name] = True
        else:
            payload[name] = 0.5
    return payload


# ---- /v1/blend -------------------------------------------------------------


def test_blend_happy_path_returns_calibrated_prediction():
    service, booster, registry = _service_with_fake_booster(prob=0.62)
    client = _client_with_service(service)
    body = {
        "matchId": "match-1",
        "featureSchemaHash": registry.schema_hash,
        "isInPlay": False,
        "isMajorEvent": True,
        "features": _features_payload(registry),
    }
    resp = client.post("/v1/blend", json=body)
    assert resp.status_code == 200, resp.text
    payload = resp.json()
    assert payload["matchId"] == "match-1"
    assert payload["modelVersion"] == registry.version
    assert payload["featureSchemaHash"] == registry.schema_hash
    assert payload["pTop"]["rawValue"] == pytest.approx(0.62, rel=1e-6)
    assert payload["pTop"]["value"] == pytest.approx(0.62, rel=1e-6)
    assert payload["pBot"]["value"] == pytest.approx(0.38, rel=1e-6)
    assert payload["uncertainty"]["method"] == "mondrian-split-conformal"
    assert payload["uncertainty"]["groupKey"] == "5|false|true"
    assert payload["uncertainty"]["coverage"] == pytest.approx(0.9, rel=1e-6)
    assert payload["uncertainty"]["label"] in {"CONFIDENT_TOP", "CONFIDENT_BOT", "AMBIGUOUS", "ANOMALOUS"}
    assert booster.calls, "booster should have been invoked"


def test_blend_rejects_schema_hash_mismatch():
    service, _, registry = _service_with_fake_booster()
    client = _client_with_service(service)
    body = {
        "matchId": "match-1",
        "featureSchemaHash": "deadbeef" * 8,
        "isInPlay": False,
        "isMajorEvent": False,
        "features": _features_payload(registry),
    }
    resp = client.post("/v1/blend", json=body)
    assert resp.status_code == 409
    assert "feature schema hash mismatch" in resp.text


def test_blend_returns_503_when_service_not_ready():
    service = BlenderService(artefacts=None, state=BlenderState.MODEL_MISSING)
    client = _client_with_service(service)
    body = {
        "matchId": "match-1",
        "featureSchemaHash": "a" * 32,
        "isInPlay": False,
        "isMajorEvent": False,
        "features": {"match.best_of": 5},
    }
    resp = client.post("/v1/blend", json=body)
    assert resp.status_code == 503
    assert "not ready" in resp.text


def test_blend_routes_under_ambiguous_when_quantile_high():
    service, _, registry = _service_with_fake_booster(prob=0.5)
    client = _client_with_service(service)
    body = {
        "matchId": "match-1",
        "featureSchemaHash": registry.schema_hash,
        "isInPlay": False,
        "isMajorEvent": True,
        "features": _features_payload(registry),
    }
    resp = client.post("/v1/blend", json=body)
    assert resp.status_code == 200
    assert resp.json()["uncertainty"]["label"] == "AMBIGUOUS"


def test_blend_validates_request_shape():
    service, _, registry = _service_with_fake_booster()
    client = _client_with_service(service)
    resp = client.post("/v1/blend", json={"matchId": "m", "features": {}})
    assert resp.status_code == 422


def test_blend_omits_sanity_block_when_variant_b_missing():
    service, _, registry = _service_with_fake_booster(prob=0.62)
    client = _client_with_service(service)
    body = {
        "matchId": "match-1",
        "featureSchemaHash": registry.schema_hash,
        "isInPlay": False,
        "isMajorEvent": True,
        "features": _features_payload(registry),
    }
    resp = client.post("/v1/blend", json=body)
    assert resp.status_code == 200
    assert resp.json().get("sanity") is None


def test_blend_emits_sanity_block_when_variant_b_loaded():
    service, primary_booster, sanity_booster, primary_registry, sanity_registry = _service_with_sanity(
        primary_prob=0.62, sanity_prob=0.58,
    )
    client = _client_with_service(service)
    body = {
        "matchId": "match-1",
        "featureSchemaHash": primary_registry.schema_hash,
        "isInPlay": False,
        "isMajorEvent": True,
        "features": _features_payload(primary_registry),
    }
    resp = client.post("/v1/blend", json=body)
    assert resp.status_code == 200
    payload = resp.json()
    assert payload["pTop"]["value"] == pytest.approx(0.62, rel=1e-6)

    sanity = payload["sanity"]
    assert sanity is not None
    assert sanity["variant"] == "B"
    assert sanity["modelVersion"] == sanity_registry.version
    assert sanity["featureSchemaHash"] == sanity_registry.schema_hash
    assert sanity["pTop"]["value"] == pytest.approx(0.58, rel=1e-6)
    assert sanity["pBot"]["value"] == pytest.approx(0.42, rel=1e-6)
    assert sanity["absoluteDiffPTop"] == pytest.approx(abs(0.62 - 0.58), rel=1e-6)
    assert sanity["uncertainty"]["method"] == "mondrian-split-conformal"

    # Both boosters were invoked
    assert primary_booster.calls, "primary booster should have been invoked"
    assert sanity_booster.calls, "sanity booster should have been invoked"


def test_blend_sanity_block_uses_primary_request_hash_only():
    """Variant B has a different schema hash; the request only carries Variant A's.
    The service must not 409 on the secondary mismatch — it just scores
    opportunistically and embeds the sanity block."""
    service, _, _, primary_registry, _ = _service_with_sanity(primary_prob=0.5, sanity_prob=0.5)
    client = _client_with_service(service)
    body = {
        "matchId": "match-1",
        "featureSchemaHash": primary_registry.schema_hash,
        "isInPlay": False,
        "isMajorEvent": True,
        "features": _features_payload(primary_registry),
    }
    resp = client.post("/v1/blend", json=body)
    assert resp.status_code == 200
    assert "sanity" in resp.json()


# ---- /v1/markov ------------------------------------------------------------


def test_markov_returns_closed_form_contract():
    service, _, _ = _service_with_fake_booster()
    client = _client_with_service(service)
    resp = client.post(
        "/v1/markov",
        json={
            "matchId": "match-1",
            "pPointTopOnServe": 0.55,
            "pPointTopOnReceive": 0.51,
            "bestOf": 5,
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["matchId"] == "match-1"
    assert body["method"] == "closed-form-best-of-5"
    assert body["pMatchTop"] > 0.5
    assert body["p_3_0"] > 0.0
    assert body["p_3_1"] > 0.0
    assert body["p_3_2"] > 0.0
    assert body["pMatchTop"] == pytest.approx(body["p_3_0"] + body["p_3_1"] + body["p_3_2"], rel=1e-7)
    assert body["expTotalPoints"] > 30.0
    assert body["medianMatchMinutes"] > 0.0
    assert "point-by-point" in body["note"].lower()


def test_markov_is_symmetric_for_fair_point_model():
    service, _, _ = _service_with_fake_booster()
    client = _client_with_service(service)
    resp = client.post(
        "/v1/markov",
        json={
            "matchId": "fair",
            "pPointTopOnServe": 0.5,
            "pPointTopOnReceive": 0.5,
            "bestOf": 5,
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["pMatchTop"] == pytest.approx(0.5, abs=1e-9)
    assert body["p_3_0"] == pytest.approx(0.125, rel=1e-9)
    assert body["p_3_1"] == pytest.approx(0.1875, rel=1e-9)
    assert body["p_3_2"] == pytest.approx(0.1875, rel=1e-9)


def test_markov_best_of_three_uses_closed_form_without_best_of_five_scores():
    service, _, _ = _service_with_fake_booster()
    client = _client_with_service(service)
    resp = client.post(
        "/v1/markov",
        json={"matchId": "bo3", "pPointTopOnServe": 0.52, "bestOf": 3},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["method"] == "closed-form-best-of-3"
    assert body["pMatchTop"] > 0.5
    assert body["p_3_0"] is None
    assert body["p_3_1"] is None
    assert body["p_3_2"] is None


def test_markov_rejects_out_of_range_probability():
    service, _, _ = _service_with_fake_booster()
    client = _client_with_service(service)
    resp = client.post(
        "/v1/markov",
        json={"matchId": "match-1", "pPointTopOnServe": 1.5, "bestOf": 5},
    )
    assert resp.status_code == 422


# ---- /v1/health -----------------------------------------------------------


def test_health_includes_blender_status():
    service, _, registry = _service_with_fake_booster()
    client = _client_with_service(service)
    resp = client.get("/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["service"] == "ttl-predict-py"
    assert body["blender"]["state"] == "ready"
    assert body["blender"]["feature_schema_hash"] == registry.schema_hash
