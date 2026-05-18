# Monitoring Dev Stack

This stack gives Phase 00 observability locally:

- Prometheus at `http://localhost:9090`
- Grafana at `http://localhost:3001` with `admin` / `admin`
- `ttl-predict-py` stub at `http://localhost:8090`

## What it expects

- The Spring Boot backend is running on the host at `http://localhost:8080`
- Docker Desktop is available so Prometheus can scrape `host.docker.internal:8080`

## Run it

```bash
./mvnw spring-boot:run

docker compose -f /Users/alexcannon/Downloads/TTLEliteSeries/infra/monitoring/compose.yaml up --build
```

## Provisioned dashboards

- `ttl-health`
- `ttl-facade`
- `ttl-ingest-placeholder`

`ttl-facade` includes the Phase 00 shadow-diff counters:

- `ttl_settlement_diff_rows`
- `ttl_settlement_diff_disagreements`
- `ttl_settlement_diff_contradictions`

Prometheus also loads Score Truth alert rules from `prometheus/rules/`:

- `ContradictionsPerDay`
- `StreamCVSilent`
- `SettlementDiffRateHigh`
- `ManualReviewQueueDepthHigh`
- `PendingEvidenceTtlExpiriesHigh`

## Notes

- The backend exports Prometheus metrics from `/actuator/prometheus`.
- The Python service is intentionally a stub in this phase: `/v1/health` and `/metrics` only.
- The stack is dev-focused and should not be treated as a production deployment template.
