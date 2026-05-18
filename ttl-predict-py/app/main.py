from fastapi import FastAPI
from prometheus_client import Gauge, Info
from prometheus_fastapi_instrumentator import Instrumentator

app = FastAPI(title="ttl-predict-py", version="0.1.0-phase-00")

STUB_UP = Gauge("ttl_predict_stub_up", "Whether the ttl-predict-py stub is running")
STUB_INFO = Info("ttl_predict_stub", "Static metadata for the ttl-predict-py stub")
STUB_UP.set(1)
STUB_INFO.info({"version": "phase-00", "service": "ttl-predict-py"})

Instrumentator(
    should_group_status_codes=False,
    should_ignore_untemplated=True,
    should_instrument_requests_inprogress=True,
).instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)


@app.get("/v1/health")
def health() -> dict[str, str]:
    return {
        "service": "ttl-predict-py",
        "status": "ok",
        "phase": "00-foundations",
    }
