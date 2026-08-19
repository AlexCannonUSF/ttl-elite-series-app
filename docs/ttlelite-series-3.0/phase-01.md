# TTLElite Series 3.0 — Phase 01 Closeout
_Status: implementation complete · Release gate / soak pending · Updated on 2026-04-19_

Phase 01 implementation is now complete. The repository has the Phase 01 data and identity foundation in place: the ingestion/observation tables exist, feed adapters publish through a unified `IngestionBus`, player identity is canonicalised through a dedicated service, tick-level odds are materialised into `odds_snapshot`, feed-health samples are persisted and surfaced in V3, and shadow mirror observations can now land independently of the sportsbook feed.

This document is intentionally precise about the current state:

- **Implementation checklist status**: complete
- **Formal release-gate status**: not yet signed off in this repo

The remaining gap is operational verification, not missing build work.

## What shipped

1. **Phase 01 schema foundation**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/db/migration/V20260416003__phase_01_data_identity_foundation.java` creates:
     - `odds_snapshot`
     - `mirror_observation`
     - `stream_observation`
     - `feed_health_sample`
     - `ingest_dlq`
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/db/migration/V20260416003__phase_01_data_identity_foundation.rollback.sql` ships the rollback sidecar in the same folder, matching the 3.0 migration rule.

2. **Unified ingestion seam**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/IngestionBus.java` defines the bus contract.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/ApplicationEventIngestionBus.java` provides the current in-process implementation on Spring application events.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/IngestEvent.java` is now the common event envelope for feed-originated observations.

3. **Identity canonicalisation**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PlayerCanonicaliser.java` implements the Phase 01 matching policy:
     - Jaro-Winkler threshold `>= 0.92`
     - country tiebreak
     - first-seen anchor
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/PlayerAliasIngestionListener.java` populates alias rows from ingestion events instead of burying alias writes inside scraper classes.

4. **Source identity and trust hierarchy**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/SourceId.java` and `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/TrustTier.java` establish the source vocabulary used by feed health, mirror observation, and later Score Truth evidence.

5. **Tick-level odds materialisation**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/OddsSnapshot.java` and `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/OddsSnapshotRepository.java` persist tick-level odds.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/OddsSnapshotFactory.java` maps sportsbook feed events into tracked-event snapshots.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/OddsSnapshotIngestionListener.java` materialises the events into storage.

6. **Historical odds backfill and retention**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsSnapshotBackfillService.java` backfills historical `odds_quote` rows into `odds_snapshot` while deduping against existing snapshots.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsSnapshotRetentionService.java` prunes expired snapshots on the configured retention cadence.

7. **Feed health persistence and telemetry**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeedHealthService.java` emits `feed.health` events once per second per source and persists `feed_health_sample` rows every 30 seconds.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/FeedHealthSample.java` and `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/FeedHealthSampleRepository.java` store the durable health trail.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/config/TtlBaseMetricsBinder.java` exposes feed-health gauges.

8. **Independent mirror observation path**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/SofaScoreFeedClient.java` adds the first mirror feed adapter in shadow-only mode.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/MirrorObservationPayload.java`, `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/MirrorObservationFactory.java`, and `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/MirrorObservationIngestionListener.java` persist mirror observations into:
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/MirrorObservation.java`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/MirrorObservationRepository.java`
   - The mirror path remains **read-only** relative to settlement.

9. **V3 ops feed surface**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OpsFeedsService.java` assembles feed health, latest persisted sample, capabilities, and DLQ depth.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/V3OpsController.java` exposes `GET /api/v3/ops/feeds`.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/routes/OpsFeedsRoute.tsx` renders the first real V3 operational page.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/components/layout/V3Shell.tsx` now carries route-aware navigation and current phase framing instead of stale shell-only copy.

10. **Ingest metrics**
    - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/IngestMetricsRecorder.java` records:
      - `ingest_events_total{source}`
      - `ingest_latency_ms{source, quantile}`
    - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/config/IngestMetricsBinder.java` exposes:
      - `ingest_dlq_depth{source}`

## Verification performed

The following implementation-level verification has been completed in-repo during Phase 01:

1. **Migration coverage**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/config/FlywayMigrationTests.java` verifies the Phase 01 tables, columns, and indexes.

2. **Feed contract and ingestion tests**
   - Verified test groups include:
     - `ApplicationEventIngestionBusTests`
     - `FeedClientContractTests`
     - `HardRockFeedClientTests`
     - `TtSeriesFeedClientTests`
     - `SofaScoreFeedClientTests`
     - `MirrorObservationFactoryTests`
     - `MirrorObservationIngestionListenerTests`
     - `OddsSnapshotFactoryTests`
     - `OddsSnapshotIngestionListenerTests`
     - `PlayerAliasIngestionListenerTests`

3. **Identity and storage tests**
   - Verified test groups include:
     - `PlayerCanonicaliserTests`
     - `PlayerCanonicaliserBenchmarkIT`
     - `PlayerIdentityServiceTests`
     - `OddsSnapshotBackfillServiceTests`
     - `OddsSnapshotRetentionServiceTests`
     - `FeedHealthServiceTests`
     - `OpsFeedsServiceTests`

4. **Metrics verification**
   - Verified test groups include:
     - `TtlBaseMetricsBinderTests`
     - `IngestMetricsBinderTests`
     - `IngestMetricsRecorderTests`

5. **Gate-query verification**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/infra/sql/clv_baseline.sql` now exists for the Phase 01 CLV baseline gate.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/config/ClvBaselineSqlTests.java` executes the exact SQL file against representative Phase 01 fixture data and asserts that the baseline is non-null and numerically correct.

6. **Gate tooling verification**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/infra/scripts/assert-tables.sh` now exists for the `P01-G1` table-presence / population gate.
   - The script has been smoke-tested locally against mocked MySQL CLI responses for both:
     - a passing case where all required Phase 01 tables exist and have rows
     - a failing case where one required table exists but remains empty

7. **V3 frontend verification**
   - `npm run build` succeeded in `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3` after adding `/v3/ops/feeds`.

## Release-gate status

Phase 01 now has a clean split between **implemented** and **operationally signed off**.

| Gate | Current status | Notes |
|---|---|---|
| `P01-G1` New tables present and populating | Partially evidenced | Tables, migration coverage, and `/Users/alexcannon/Downloads/TTLEliteSeries/infra/scripts/assert-tables.sh` now exist in-repo; the live promotion/population check still needs staging or prod verification. |
| `P01-G2` Player canonicaliser benchmark | Evidenced locally | `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/service/PlayerCanonicaliserBenchmarkIT.java` loads `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/resources/players/canonical-pairs.csv` and enforces precision ≥ 0.99 / recall ≥ 0.95. |
| `P01-G3` `feed.health` emits continuously | Partially evidenced | Emission code and tests exist; the Prometheus pass condition still needs runtime observation. |
| `P01-G4` DLQ bounded | Not evidenced | Metric exists; bounded soak has not been demonstrated in this repo state. |
| `P01-G5` CLV baseline computable | Evidenced locally | `/Users/alexcannon/Downloads/TTLEliteSeries/infra/sql/clv_baseline.sql` exists and `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/config/ClvBaselineSqlTests.java` verifies that it returns a non-null baseline on representative Phase 01 data. |

**Summary**: Phase 01 implementation is complete, but the formal gate remains open until soak and operational checks are performed.

## Residual limits and known follow-up items

These are not missing Phase 01 build tasks anymore. They are the remaining operational and readiness gaps.

1. **Operational soak gap**
   - The 48-hour Phase 01 soak has not been recorded here.
   - DLQ boundedness, feed pulse continuity, and table-population checks still need live observation.

2. **Mirror source caution**
   - `SofaScoreFeedClient` remains default-off and shadow-only, which is correct for Phase 01.
   - External endpoint availability and anti-bot behaviour still need operator validation before any broader use.

## Handoff to Phase 02

Phase 02 can now begin on the data layer side because the underlying observation and identity infrastructure exists. The immediate next work is:

1. Implement the `SettlementEvidence` bundle and `Observation` model on top of the new source/trust vocabulary.
2. Build `AmbiguityScorer` and `ContradictionGuard` against the new ingestion/observation foundation.
3. Keep settlement in shadow mode while the new evidence-driven engine proves parity and contradiction handling.
4. Start Stream-CV ingress work knowing the bus, trust tiers, mirror table, and V3 ops surface already exist.

## Post-mortem summary

### What went well

- The phase stayed true to the reliability-first plan: we built the data substrate before touching settlement authority.
- The `FeedClient` and `IngestionBus` seam gave Phase 01 a coherent shape instead of another pile of scraper-side special cases.
- The first real V3 route landed during the same phase, which helped validate that backend observability work can show up as product-facing operations tooling immediately.

### What surprised us

- The release-gate spec is stricter than “code compiles and tests pass,” especially around the canonicalisation benchmark and CLV-baseline evidence.
- Mirror-source work is more fragile in practice than on paper because live public endpoints can change behaviour or reject direct access.

### What we should keep doing

- Continue separating build completion from operational signoff explicitly.
- Keep metrics and observability at the shared seams, not sprinkled inside every adapter.
- Keep phase docs honest about which items are coded, tested, soaked, and promoted.
