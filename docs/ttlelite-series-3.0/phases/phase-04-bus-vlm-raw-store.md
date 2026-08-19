# Phase 04 — Ingestion Bus + VLM Budget + Raw Store + Blender A
_Target duration: 2–3 weeks · Blast radius: medium-high (infra changes) · Reversibility: flag-off + switchback_

## Purpose
Move ingestion from in-process Spring events to Redis Streams, persist raw payloads and Stream-CV audit frames to MinIO, and promote the first production LightGBM blender (Variant A) into shadow. These are durable-storage changes that give later phases decoupling and replayability, and the blender finally makes the prediction stack honest about its features.

## Entry criteria
- Phase 03 soak passed.
- Ops has tested Redis 7 + MinIO on staging.
- 60+ days of usable training data in `odds_snapshot` + `paper_trade_bet` for walk-forward.

## Deliverables
1. Redis 7 deployed; `RedisStreamsBus` implementing `IngestionBus`.
2. All feed adapters + `stream.frame` emitters moved to Redis Streams behind `features.redis-streams=on`.
3. MinIO deployed; raw payload writer persists the body of every ingested event keyed by `correlationId`.
4. Stream-CV audit buffer writes to MinIO on contradictions; `settlement_audit.evidence_refs` points to the JPEGs.
5. Stream-CV Tier C VLM fallback goes live with `CostGovernor`, `stream_vlm_call` log, and alerts.
6. LightGBM blender Variant A trained with walk-forward protocol (Prediction Engine Spec §6 + §12); model card committed; Platt + isotonic + split-conformal shipped as Java artefacts.
7. `ttl-predict-py` deploys `/v1/blend` and `/v1/markov` (Markov returns placeholder in this phase).
8. `PredictionFacade` routes 5 % shadow to v3 pipeline; `prediction_diff_log` table populated.
9. `/v3/ops/ingest` page shows bus health, DLQ, partition lag.

## Work breakdown
- Redis Streams cutover is per-feed: each adapter moves independently with a shadow-read step where the old Spring-event path stays live until the Streams path is verified.
- MinIO bucket policy disables public access; lifecycle rule deletes raw payloads after 14 days and CV audit frames after 30 days.
- VLM CostGovernor enforces day/hour/worker caps. `StreamVLMCostSpike` alert tested via a synthetic workload.
- Blender Variant A training job runs in CI; model artefact is immutable once committed; model card includes training cohort, features, and metrics.
- `PredictionFacade` shadow routes 5 % of production decisions to v3; results logged, not acted upon.

## Exit criteria
- Release Gate Checklist §6 fully ticked.
- 7-day post-cutover soak with consumer lag p95 < 2 s.
- Blender Variant A shadow shows BSS ≥ 0 and ECE ≤ 0.02 on the trailing 7 days.
- Stream-CV VLM daily cost < $1.20.
- MinIO growth within ±30 % of estimate.

## Risks
- **Ingestion bus cutover causes event loss.** Mitigation: feed-by-feed migration with shadow-read verification; Streams consumer groups with explicit acknowledgement; `ingest_dlq` for unackable events.
- **Blender overfits.** Mitigation: walk-forward with purge; early stopping; subgroup metrics; Brier skill score vs. market.
- **MinIO cost run-away.** Mitigation: lifecycle rules + bucket size alerts + daily delta metric.

## Rollback
- Flip `features.redis-streams=off` and the adapters fall back to Spring events. Redis can be left running for later re-enable.
- Drop the MinIO raw store (no downstream hard dependency — evidence refs are references, not authoritative).
- Demote Blender Variant A shadow to 0 %.

## Operator runbook
- `./scripts/deploy-phase-04.sh staging`
- `./scripts/bus-cutover.sh --feed hr-mkt` — migrates one feed at a time to Streams.
- `./scripts/minio-growth.sh` — reports daily bytes added per bucket.
- `./scripts/predict-walkforward.sh --variant A --days 60` — reruns gates.
- `./scripts/rollback-phase-04.sh staging`
