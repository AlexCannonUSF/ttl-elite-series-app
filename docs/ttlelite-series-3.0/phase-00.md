# TTLElite Series 3.0 — Phase 00 Closeout
_Status: complete · Closed on 2026-04-16_

Phase 00 is now complete. The repository has the core 3.0 scaffolding in place with no intended production behaviour change: facades exist, feature flags are centralized, the shadow-diff path is wired, the migration path is standardized, base monitoring is present, the prediction-service stub is alive, the `cv/` module is scaffolded, and the new `web-v3` shell is mounted at `/v3/*`.

## What shipped

1. **3.0 documentation lane**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-3.0` is now the canonical 3.0 planning and execution folder.
   - The repo root README and docs navigation point to the 3.0 docs set directly.

2. **Feature-flag foundation**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/features.yaml` exists as the top-level feature catalog.
   - The Phase 00 rollout flags are present and lintable, which gives later phases a safe promotion and rollback mechanism.

3. **Backend seam creation**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/prediction/PredictionFacade.java` wraps the existing prediction stack without changing results.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/settlement/SettlementFacade.java` wraps settlement entry points without changing settlement behaviour.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/feed/FeedClient.java` and companion ingest contracts establish the 3.0 source boundary.

4. **Feed adapter layer**
   - Hard Rock and TT Series scraper paths are now reachable through `FeedClient` adapters.
   - The adapter seam preserves legacy payload expectations for downstream code while enabling later feed unification work.

5. **Correlation and shadow persistence**
   - Correlation IDs now flow through the request path and into the relevant logs/entities introduced in Phase 00.
   - Shadow session/bet tables exist so 3.0 experiments can observe without replacing the live paper-trading record model.

6. **Migration discipline**
   - Flyway is in place as the numbered migration path for 3.0 database changes.
   - Phase 00 established the rule that schema work must ship as explicit migrations rather than implicit Hibernate drift.

7. **Monitoring baseline**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/infra/monitoring` contains the Prometheus + Grafana dev stack.
   - Base application metrics and the prediction-service stub metrics are exposed for local/dev monitoring.

8. **Shadow-diff harness**
   - Settlement diff logging is in place so later phases can compare 2.0 and 3.0 decision paths safely.
   - Phase 00 keeps this harness on identity-style comparisons so the instrumentation can be trusted before any behavioural change.

9. **Prediction-service stub**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/ttl-predict-py` exists as the 3.0 Python service skeleton.
   - Health and metrics endpoints are available so Phase 04 and Phase 05 have a stable deployment target.

10. **Stream-CV scaffold**
    - `/Users/alexcannon/Downloads/TTLEliteSeries/cv` and the backend `cv` package structure are present.
    - `StreamRouter`, `StreamFetcher`, and `FrameSampler` are scaffolded behind the `features.stream-cv` flag.

11. **V3 frontend shell**
    - `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3` exists as a separate Vite + React 19 + Tailwind v4 workspace.
    - A placeholder shell route is available so 3.0 UI work can proceed without destabilizing the current 2.0 app.

12. **Dual static-resource mount**
    - `/` continues serving the 2.0 frontend.
    - `/v3/*` now serves the new shell from the same Spring Boot app, with SPA fallback handled at the resource-chain level so routes resolve cleanly without breaking asset delivery.

## Verification performed

The following Phase 00 verification work has been completed in-repo:

1. **Feature flag validation**
   - `scripts/lint-features.sh` was introduced to validate the feature catalog contract.

2. **Backend regression coverage**
   - The Phase 00-focused Maven suite passed, including facade, feed-client, metrics, migration, and shadow-diff coverage.
   - Verified test groups included:
     - `PredictionFacadeTests`
     - `SettlementFacadeTests`
     - `SettlementDiffLogServiceTests`
     - `HardRockFeedClientTests`
     - `TtSeriesFeedClientTests`
     - `FlywayMigrationTests`
     - `FeatureFlagCatalogTests`
     - `WebConfigTests`
     - `SpaPageResourceResolverTests`

3. **`web-v3` build**
   - `npm install` and `npm run build` succeeded in `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3`.

4. **Mount verification**
   - Spring Boot was started with nonessential startup jobs disabled.
   - HTTP checks confirmed:
     - `/` returns the 2.0 shell
     - `/players` still resolves through the 2.0 SPA shell
     - `/v3/` returns the 3.0 shell
     - `/v3/review` resolves through the 3.0 SPA shell
     - `/v3/assets/...` is served as a real static asset rather than being swallowed by SPA forwarding

## Exit criteria status

Phase 00 exit criteria are satisfied at the scaffolding level intended for this phase:

| Exit item | Status | Notes |
|---|---|---|
| Zero-behaviour-change seam creation | Complete | Facades and adapters are in place as pass-throughs. |
| Metrics visible | Complete | Backend + predict stub metrics are exposed through the monitoring stack. |
| Shadow-diff harness writing rows | Complete | Identity-path diff logging is wired for later comparison use. |
| `/v3/` placeholder renders | Complete | `web-v3` builds and is mounted under Spring Boot. |

## Residual limits deliberately left for later phases

These are not Phase 00 defects. They are intentionally deferred:

1. **No feed unification yet**
   - Feed adapters exist, but the ingestion bus and richer observation storage begin in Phase 01.

2. **No behaviour promotion yet**
   - Settlement and prediction still use 2.0 behaviour. Phase 00 only created safe seams around that behaviour.

3. **No real Stream-CV output yet**
   - The CV module is scaffolded only. Frame routing, OCR, template recognition, and VLM fallback begin later.

4. **No 3.0 UI product pages yet**
   - `web-v3` is a shell workspace, not the finished Live Studio replacement.

## Handoff to Phase 01

Phase 01 can now start on a stable base. The immediate next work is:

1. Create the new ingestion and observation tables: `odds_snapshot`, `mirror_observation`, `stream_observation`, `feed_health_sample`, and `ingest_dlq`.
2. Introduce `IngestionBus` v0 and move feed adapters onto a unified event emission path.
3. Implement `PlayerCanonicaliser`, `SourceId`, and `TrustTier`.
4. Materialize feed health and odds snapshots without changing settlement authority yet.
5. Expose the first `/v3/ops/feeds` surface against the new unified feed-health model.

## Post-mortem summary

### What went well

- The seam-first approach stayed true to the Phase 00 intent: we created future leverage without forcing early behaviour change.
- The `/v3/*` mount landed cleanly once SPA fallback was moved into the Spring resource chain instead of controller forwarding.
- The documentation lane is now structured enough that later phases can be executed top-down instead of from scattered notes.

### What surprised us

- Static-resource mounting was trickier than it looked because naive route forwarding can break real asset resolution.
- `web-v3` setup needed local npm cache isolation to stay reliable in this environment.

### What we should keep doing

- Keep phase closeouts explicit and evidence-based.
- Continue treating feature flags, metrics, migrations, and rollback as first-class deliverables rather than cleanup work.
