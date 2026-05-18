# ttl-predict-py

Phase 00 Python prediction-service skeleton for TTLElite Series 3.0.

## Endpoints

- `GET /v1/health`
- `GET /metrics`

The model-serving endpoints (`/v1/blend`, `/v1/markov`, `/v1/retrain`) intentionally do not exist yet in this scaffold phase.

## Local run

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8090
```
