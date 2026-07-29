"""Pydantic request/response schemas for `/v1/blend` and `/v1/markov`.

Field names match Prediction Engine Spec §10 so the Java `BlenderClient`
can deserialize without translation. Markov fields mirror §5.2.
"""

from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, ConfigDict, Field


class BlendRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    matchId: str = Field(..., min_length=1, max_length=120)
    featureSchemaHash: str = Field(..., min_length=8, max_length=128)
    isInPlay: bool = False
    isMajorEvent: bool = False
    features: dict[str, object] = Field(..., min_length=1)


class ProbabilityBlock(BaseModel):
    value: float = Field(..., ge=0.0, le=1.0)
    rawValue: float = Field(..., ge=0.0, le=1.0)


class UncertaintyBlock(BaseModel):
    coverage: float
    alpha: float
    label: str
    intervalLow: float
    intervalHigh: float
    groupKey: str
    quantile: float
    method: str
    version: str


class SanityBlock(BaseModel):
    """Variant B sanity-check result, per Prediction Engine Spec §6.3 + §9.3."""

    variant: str = "B"
    modelVersion: str
    calibratorVersion: str
    conformalVersion: str
    featureSchemaHash: str
    pTop: ProbabilityBlock
    pBot: ProbabilityBlock
    uncertainty: UncertaintyBlock
    absoluteDiffPTop: float = Field(..., ge=0.0, le=1.0)
    latencyMs: float


class BlendResponse(BaseModel):
    matchId: str
    modelVersion: str
    calibratorVersion: str
    conformalVersion: str
    featureSchemaHash: str
    pTop: ProbabilityBlock
    pBot: ProbabilityBlock
    uncertainty: UncertaintyBlock
    computedAtUtc: str
    latencyMs: float
    sanity: Optional[SanityBlock] = None


class MarkovRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    matchId: str = Field(..., min_length=1, max_length=120)
    pPointTopOnServe: float = Field(..., ge=0.0, le=1.0)
    pPointTopOnReceive: Optional[float] = Field(default=None, ge=0.0, le=1.0)
    bestOf: int = Field(default=5, ge=3, le=9)


class MarkovResponse(BaseModel):
    matchId: str
    pMatchTop: Optional[float] = None
    p_3_0: Optional[float] = None
    p_3_1: Optional[float] = None
    p_3_2: Optional[float] = None
    expTotalPoints: Optional[float] = None
    medianMatchMinutes: Optional[float] = None
    method: str
    version: str
    note: str


class HealthResponse(BaseModel):
    service: str
    status: str
    phase: str
    blender: dict[str, object]
