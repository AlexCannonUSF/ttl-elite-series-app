"""FastAPI router gluing schemas + services to the HTTP layer."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request

from .blender_service import BlenderError, BlenderService
from .markov_service import simulate
from .schemas import BlendRequest, BlendResponse, MarkovRequest, MarkovResponse


def get_blender_service(request: Request) -> BlenderService:
    service = getattr(request.app.state, "blender_service", None)
    if service is None:
        raise HTTPException(status_code=503, detail="blender service not initialised")
    return service


def build_router() -> APIRouter:
    router = APIRouter()

    @router.post("/v1/blend", response_model=BlendResponse)
    def blend(payload: BlendRequest, service: BlenderService = Depends(get_blender_service)) -> BlendResponse:
        try:
            result = service.score(
                match_id=payload.matchId,
                feature_schema_hash=payload.featureSchemaHash,
                features=payload.features,
                is_in_play=payload.isInPlay,
                is_major_event=payload.isMajorEvent,
            )
        except BlenderError as exc:
            raise HTTPException(status_code=exc.status_code, detail=str(exc))
        return BlendResponse(**result)

    @router.post("/v1/markov", response_model=MarkovResponse)
    def markov(payload: MarkovRequest) -> MarkovResponse:
        return simulate(payload)

    return router
