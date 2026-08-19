from fastapi import FastAPI
from prometheus_client import Gauge, Info
from prometheus_fastapi_instrumentator import Instrumentator

from .serving.blender_service import BlenderService, BlenderState
from .serving.router import build_router

app = FastAPI(title="ttl-predict-py", version="0.5.0-phase-05")

STUB_UP = Gauge("ttl_predict_stub_up", "Whether the ttl-predict-py service is running")
STUB_INFO = Info("ttl_predict_stub", "Static metadata for the ttl-predict-py service")
BLENDER_STATE = Gauge(
    "ttl_predict_blender_ready",
    "1 if the blender artefacts are loaded, 0 otherwise",
)
STUB_UP.set(1)
STUB_INFO.info({"version": "phase-05", "service": "ttl-predict-py"})

Instrumentator(
    should_group_status_codes=False,
    should_ignore_untemplated=True,
    should_instrument_requests_inprogress=True,
).instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)

# Eagerly load the blender on module import so tests can override the
# service via ``app.state.blender_service = ...`` without racing a startup
# event. Production runs honour the env var on first import; nothing else
# touches ``app.state.blender_service`` after that.
_initial_service = BlenderService.load()
app.state.blender_service = _initial_service
BLENDER_STATE.set(1.0 if _initial_service.state == BlenderState.READY else 0.0)

app.include_router(build_router())


@app.get("/v1/health")
def health() -> dict[str, object]:
    service: BlenderService | None = getattr(app.state, "blender_service", None)
    return {
        "service": "ttl-predict-py",
        "status": "ok",
        "phase": "05-prediction-core-markov",
        "blender": service.status_dict() if service is not None else {"state": "uninitialised"},
    }
