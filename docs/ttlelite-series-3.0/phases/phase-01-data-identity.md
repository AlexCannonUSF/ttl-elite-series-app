# Phase 01 — Data & Identity
_Target duration: 1–2 weeks · Blast radius: low (new tables, new read paths) · Reversibility: straightforward rollback_

## Purpose
Unify all feeds under `FeedClient`, make identity canonical across sources, and materialise tick-level odds so every later phase has durable, queryable data. No settlement behaviour changes; the goal is purely to give us the data foundation we need for Score Truth, CLV, and Stream-CV.

## Entry criteria
- Phase 00 complete and stable 24 h.
- Migration framework green on staging.
- Operator has bandwidth to triage identity false-merges during the first 48 h.

## Deliverables
1. New tables live on staging → prod: `odds_snapshot`, `mirror_observation`, `stream_observation`, `feed_health_sample`, `ingest_dlq` (schemas per Scraper Spec §4).
2. `IngestionBus` v0 using Spring `ApplicationEventPublisher` with the `IngestEvent` contract.
3. `PlayerCanonicaliser` and populated `player_alias` table. Jaro-Winkler 0.92 threshold with country tiebreak and first-seen anchor.
4. `odds_snapshot` populated from Hard Rock with every tick; retention 30 days via a scheduled sweep job.
5. `FeedHealthService` emitting `feed.health` once per second per source to the bus and to Prometheus, while persisting `feed_health_sample` rows every 30 s.
6. Mirror feed adapter: `SofaScoreFeedClient` in shadow-read mode (produces `mirror_observation` rows but **does not** influence settlement). It defaults to `ttl.sofascore.enabled=false` until the operator opts in.
7. `/v3/ops/feeds` page showing per-source p50/p95 latency, last-seen, and DLQ depth via `/api/v3/ops/feeds`.

## Work breakdown
- Migration `V20260416003__phase_01_data_identity_foundation` creates the ingestion/observation tables, and a rollback SQL sidecar is committed in the same folder.
- The Hard Rock scraper's existing JSON payloads land in `hardrock_raw` (existing), but now every extracted tick also lands in `odds_snapshot` through an ingestion-bus listener that persists one row per side and prunes expired rows on a daily 30-day retention sweep.
- `PlayerCanonicaliser` includes a benchmark test that loads the 500-pair labelled dataset `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/resources/players/canonical-pairs.csv` and asserts precision ≥ 0.99 / recall ≥ 0.95.
- Alias seeding runs through an ingestion listener on the Phase 01 `IngestionBus`, so Hard Rock and TT Series feed events populate `player_alias` without embedding alias writes into the scraper classes themselves.
- `FeedHealthService` writes to `feed_health_sample` every 30 s with p50 and p95 latency over the trailing minute, emits `feed.health` once per second per source, and exposes p50/p95/staleness gauges to Prometheus.
- Ingestion observability also exposes `ingest_events_total{source}`, `ingest_latency_ms{source, quantile}`, and `ingest_dlq_depth{source}` from the ingestion-bus seam and DLQ repository.

## Exit criteria
- Release Gate Checklist §3 fully ticked.
- 48 h soak with steady-state DLQ depth `< 50` per source.
- Identity benchmark scores meet the gate.
- CLV baseline SQL returns a usable value for last 7 days — even if conservative — proving the ticks are landing.

## Risks
- **False-merge of distinct players with near-identical names.** Mitigation: threshold + country tiebreak + operator UI to "split alias". Any auto-merge is logged and reversible from `/v3/ops/players/:id/aliases`.
- **Backfill explodes `odds_snapshot`.** Mitigation: the historical backfill is paged from `odds_quote` through a dedicated service and startup runner, defaults to off, and dedupes against existing `odds_snapshot` rows so reruns stay safe.

## Rollback
- Turn off the `odds_snapshot` writer and the `SofaScoreFeedClient`. The new tables can be truncated but are safer to leave populated — nothing downstream reads them yet.
- `PlayerCanonicaliser` runs inside the Hard Rock adapter; if it misbehaves, a flag `features.canonicaliser=off` reverts to the previous "first-seen normalised string" behaviour.

## Operator runbook
- `./scripts/deploy-phase-01.sh staging`
- `./scripts/verify-phase-01.sh staging`
- `./scripts/rollback-phase-01.sh staging`

## Post-mortem (appended after phase close)

_Implementation completed on 2026-04-19. Formal release-gate soak and benchmark evidence remain pending._

### What went well

- Phase 01 produced a coherent data and identity substrate rather than isolated utilities.
- The ingest seam (`FeedClient` -> `IngestionBus` -> listeners) now gives later phases a clean place to add evidence, replay, and bus upgrades.
- `/v3/ops/feeds` proved that the new backend observability work is already usable through the V3 workspace.

### What surprised us

- The release gate asks for stronger operational proof than the raw implementation checklist alone captures.
- Mirror-source work remains dependent on unstable third-party endpoint behaviour, so shipping it default-off was the right call.

### What we should keep doing

- Keep implementation closeouts explicit about what is built versus what is operationally signed off.
- Keep documentation, metrics, and rollback artefacts moving in lockstep with code.
