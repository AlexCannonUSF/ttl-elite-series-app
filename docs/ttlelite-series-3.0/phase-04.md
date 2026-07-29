# TTLElite Series 3.0 - Phase 04 Closeout

_Status: implementation complete; runtime soak / release gate pending; updated on 2026-05-18_

Phase 04 moved the 3.0 stack from local, in-process ingestion toward durable operation. Ingestion can now route through Redis Streams, raw payloads and CV audit evidence can land in MinIO, Tier C VLM fallback is behind budget controls, and the first v3 prediction shadow path is wired end to end with model artefacts and diff logging.

The implementation checklist is complete. The formal exit criteria still require runtime evidence: seven days of Redis Streams steady state, zero data loss, no DLQ growth beyond 24 hours, VLM spend under cap, and blender Variant A shadow results that are neutral-or-positive on CLV.

## Shipped Capabilities

### Redis Streams Ingestion Bus

- `RedisStreamsBus` implements the `IngestionBus` contract and writes canonical ingest events to Redis stream families.
- `IngestionBusConfiguration` selects the delivery path with `features.redis-streams`.
- `off` keeps `ApplicationEventIngestionBus` as the active bus.
- `shadow` keeps Spring application events primary while also writing to Redis Streams.
- `on` promotes Redis Streams to the active delivery bus.
- Stream keys use the configured prefix and known families: `ttl:odds`, `ttl:scores`, `ttl:results`, `ttl:health`, and `ttl:identity`.

### Redis Runtime

- Dev and staging Redis 7 compose files live in `infra/redis/`.
- Redis uses append-only persistence and health checks.
- The monitoring README points operators at the Redis runtime.
- Local Spring Boot runs remain safe with Redis unavailable because Redis health is disabled by default and the feature flag defaults to `off`.

### MinIO Raw Store And CV Audit Buffer

- Dev and staging MinIO compose files live in `infra/minio/`.
- `ttl-raw` stores gzipped raw ingest payloads keyed by source, date, and correlation id.
- `ttl-cv-audit` stores Stream-CV contradiction evidence frames.
- Bucket lifecycle setup expires raw payloads after 14 days and CV audit evidence after 30 days.
- `RawPayloadStoringIngestionBus` decorates the active ingestion bus and fail-opens if serialization or upload fails.
- CV audit evidence refs are written into `settlement_audit.evidence_refs`.

### Tier C VLM Fallback

- `VlmClient` now has Gemini Flash and Claude Haiku adapters with a disabled fallback client.
- `GovernedVlmCaller` routes VLM calls through `CostGovernor`.
- `CostGovernor` enforces daily, hourly, and per-worker caps before paid calls leave the system.
- `VlmCallRecorder` persists `stream_vlm_call` rows for cost and reliability review.
- Prometheus metrics expose call counts, token counts, latency, spend estimates, daily totals, and governor blocks.
- `infra/monitoring/prometheus/rules/ttl-phase-04-alerts.yml` includes the Phase 04 VLM cost alerts.

### Prediction Blender Variant A

- The first production blender artefacts are committed under `models/`.
- `ttl-predict-py` exposes the blender endpoint and the Phase 04 Markov endpoint.
- Platt, isotonic, and split-conformal calibration artefacts are present for Java-side consumption.
- `PredictionFacade` can shadow-route 5 percent of decisions into the v3 path.
- `prediction_diff_log` records v2/v3 deltas without granting v3 settlement authority.

### V3 Ops Ingest Surface

- `/api/v3/ops/ingest` reports bus mode, active bus, Redis availability, DLQ depth, and partition health.
- `/v3/ops/ingest` renders bus health, DLQ pressure, and per-stream lag for the Redis families.
- The route is intentionally useful when Redis is offline: operators see `OFF` or `UNAVAILABLE` state instead of a broken page.

## Runtime Controls

Primary feature flags and properties:

```properties
features.redis-streams=off|shadow|on
features.predict-v3=off|shadow|on

ttl.ingestion.redis.streamPrefix=ttl
ttl.ingestion.raw-store.enabled=false
ttl.cv-audit.enabled=false
ttl.streamCv.vlm.engine=disabled|gemini-flash|claude-haiku
ttl.streamCv.vlm.governor.enabled=true
ttl.predict-v3.enabled=false
ttl.predict-v3.shadowRate=0.05
```

Local Redis:

```bash
docker compose -f /Users/alexcannon/Downloads/TTLEliteSeries/infra/redis/compose.dev.yaml up -d
```

Local MinIO:

```bash
docker compose -f /Users/alexcannon/Downloads/TTLEliteSeries/infra/minio/compose.dev.yaml up -d
```

Local v3 UI:

```bash
npm --prefix /Users/alexcannon/Downloads/TTLEliteSeries/web-v3 run dev -- --host 127.0.0.1
```

Useful operator URLs:

- `/v3/ops/ingest` - bus health, DLQ depth, and partition lag.
- `/v3/ops/feeds` - feed health and source DLQ pressure.
- `/v3/ops/feeds/streams` - Stream-CV worker and VLM readiness.
- `/actuator/prometheus` - ingest, DLQ, and VLM metrics.

## Verification

Phase 04 was exercised through focused and full verification:

- Backend suite: `./mvnw -q test`
- Targeted ingest UI contract: `./mvnw -q -Dtest=OpsIngestServiceTests,V3OpsControllerTests test`
- Frontend build: `npm --prefix web-v3 run build`
- Feature flag lint: `./scripts/lint-features.sh`
- Repository whitespace check: `git diff --check`
- Redis compose validation: `docker compose -f infra/redis/compose.dev.yaml config`
- Redis staging compose validation: `docker compose -f infra/redis/compose.staging.yaml config`
- MinIO compose validation was performed during the MinIO implementation work.
- Browser smoke: `/v3/ops/ingest` rendered against a real backend contract with no console errors.

Representative test coverage includes Redis stream publication, shadow Redis behavior, raw payload storage, MinIO upload failure handling, CV audit evidence refs, VLM clients, VLM response parsing, budget governor behavior, VLM call recording, prediction shadow logging, and the new V3 ingest operations contract.

## Release Gate Status

| Gate | Requirement | Status |
| --- | --- | --- |
| P04-G1 | Redis Streams steady state for 7 days | Implemented; pending soak evidence |
| P04-G2 | Zero data loss through cutover | Instrumented; pending staged cutover evidence |
| P04-G3 | No DLQ growth lasting more than 24 hours | Visible in `/v3/ops/ingest`; pending soak evidence |
| P04-G4 | VLM budget steady under configured cap | Implemented with governor and alerts; pending live cost evidence |
| P04-G5 | Blender Variant A shadow is neutral-or-positive on CLV | Shadow path implemented; pending production shadow sample |

## Residual Limits

- Redis Streams is feature-flagged and default-off until the 7-day steady-state soak is complete.
- No production consumer-group cutover should be treated as complete until partition lag is observed under live load.
- VLM clients are wired, but paid usage should stay disabled unless API keys, caps, and alerts are confirmed in the target environment.
- MinIO refs are evidence pointers, not settlement authority.
- Blender Variant A remains shadow-only; it logs diffs but does not control staking or settlement.
- Markov serving exists in the Python app, but deeper Markov simulator work belongs to Phase 05.

## Handoff To Phase 05

Phase 05 can now build prediction-core work on durable Phase 04 foundations:

- Use Phase 04 model artefact conventions for TrueSkill-2 and Weng-Lin nightly jobs.
- Read prediction shadow deltas from `prediction_diff_log` before promoting any new model authority.
- Preserve `PredictionFacade` as the routing point for staged model rollouts.
- Keep `/v3/ops/ingest` in the operator loop while Phase 05 adds heavier prediction jobs that depend on fresh ingest data.
- Treat Redis, MinIO, and VLM budget status as release prerequisites before enabling more autonomous prediction decisions.

## Post-Mortem Summary

### What went well

- Durable bus, object store, budgeted external inference, model artefacts, and operator visibility all landed together. The system finally has production-shape plumbing instead of a research demo with hooks.
- The Redis Streams shadow mode let us cut the bus over to durable delivery without a forklift change — every event mirrors to both paths and we can compare in real time.
- The VLM budget governor is the right kind of conservative: hard cap + per-worker cap + visible cost-per-hour gauge.

### What surprised us

- The main engineering risk flipped from "missing code" to "missing runtime evidence." Phases 04 onward are bounded by wall-clock observation, not by what's written.
- Model-artefact loading and feature-schema-hash enforcement absorbed more boundary-design time than expected — but the §3.10 hard-error contract makes drift impossible to miss, which paid back the cost.

### One improvement to bake in

- "Boring is the new fast." After Phase 04, the next steps should default to soak windows + observability before any new authority. Watch lag, watch DLQ, cap external spend, let prediction shadow diffs accumulate, and only then talk about flipping a flag.
