# TTLElite Series 3.0 - Phase 03 Closeout

_Status: implementation complete; advisory soak / release gate pending; updated on 2026-05-18_

Phase 03 moved Score Truth from a Phase 02 shadow-only foundation into an advisory operating mode. The system can now hold ambiguous bets open, surface review actions to operators, recover stale live sessions, expand evidence across new feeds, and expose Stream-CV and review health through V3 operations screens.

The implementation checklist is complete. The formal release gate is not complete until the advisory soak produces operational evidence: two weeks of advisory operation, manual review queue p95 below threshold, staleness recovery exercised in live-like operation, and production ROI template coverage.

## Shipped Capabilities

### Score Truth Advisory Settlement

- `ScoreTruthAdvisoryService` promotes low-confidence Score Truth evidence into operator-facing advisories without granting bet-closing authority.
- `SettlementFacade` and `SettlementShadowAuditService` preserve the separation between observed truth, advisory state, and settlement authority.
- Manual review actions are persisted and auditable through advisory records rather than hidden runtime decisions.

### Hold-Open And Pending Evidence TTL

- Paper trades can enter `PENDING_EVIDENCE` while Score Truth waits for higher-confidence evidence.
- The hold-open path records expiry timestamps, last advisory reason, review status, and retry context.
- TTL expiry is measurable through Phase 03 metrics and visible to operators instead of silently collapsing into a generic settlement outcome.

### Stale-Live Recovery

- `StaleLiveRecoveryService` detects bets stuck in live state beyond the configured threshold.
- Recovery emits `stale.live.detected`, escalates targeted polling, and records advisory recovery outcomes.
- The recovery path is implemented and covered by tests; the release gate still requires live-like evidence of at least ten recovered sessions.

### Policy Catalog And Audit Trail

- `bet_settlement_policy.yaml` is loaded through `BetSettlementPolicyCatalog`.
- Settlement policy changes are audited in a dedicated persistence table.
- The policy loader supports runtime-safe configuration updates while keeping changes observable.

### Expanded Evidence Feeds

- Phase 03 added evidence clients for BetsAPI, AI Score, and ITTF/WTT paths.
- Hard Rock targeted polling and tree discovery provide sportsbook-specific escalation paths for stale or ambiguous matches.
- Feed behavior is test-covered; live compliance, availability, and operating limits still need release-gate signoff.

### Stream-CV Tier B And Operator Hook

- The classic CV digit recognizer and stream route catalog provide Tier B fallback coverage.
- `stream_routes.yaml` and persisted stream route records give operations a source of truth for configured streams.
- `StreamCvVlmFallbackHook` provides the Force VLM integration point. The paid VLM client, cost governor, raw store, and production VLM execution path remain Phase 04 work.

### CV Template Builder

- `tools/cv-template-builder/` gives operators a usable browser tool for drawing score ROIs and exporting templates.
- The builder includes core template logic, styling, and smoke-test entry points.
- Production release still needs the ROI corpus expanded and exercised beyond the initial templates.

### V3 Review Queue

- `/v3/review` exposes advisory queue filtering, row-level status, comments, accept/reject actions, and queue metrics.
- `ScoreTruthReviewService` backs the UI with persisted advisory state and queue-depth accounting.
- Operators can now work the advisory queue without direct database inspection.

### V3 Stream Operations

- `/v3/ops/feeds/streams` exposes Stream-CV status, VLM usage snapshots, route inventory, and worker health.
- `OpsStreamsService` now prefers persisted stream route counts when the database tables are available.
- The route remains usable before Stream-CV workers are fully deployed, with clear empty-state behavior.

### Stream Worker Data Tables

- Phase 03 added database support for `stream_worker_config`, `stream_worker_health_1m`, and `stream_route`.
- Java entities and repositories cover worker configuration, minute-level health snapshots, and persisted route metadata.
- Flyway migration and rollback coverage are in place.

### Phase 03 Alerts

- `ScoreTruthMetricsBinder` exports manual-review queue depth and pending-evidence TTL expiry metrics.
- `ttl-phase-03-alerts.yml` defines alerts for high manual review depth and high pending-evidence TTL expiries.
- The monitoring README now includes the Phase 03 alert file in the Prometheus rule inventory.

## Verification

Phase 03 implementation has been exercised through the project test and build flow:

- Backend suite: `./mvnw test` passed after the Phase 03 metrics and alert work.
- Frontend build: `npm --prefix web-v3 run build` passed after the V3 review and stream operations additions.
- Migration coverage: Flyway migration tests cover the Phase 03 hold-open, policy audit, and stream worker tables.
- Alert validation: Prometheus rule YAML loads successfully.
- Repository hygiene: `git diff --check` and `./scripts/lint-features.sh` passed during Phase 03 closeout work.

Representative test coverage includes advisory settlement, stale-live recovery, settlement policy loading, feed clients, Hard Rock targeted polling/tree discovery, Stream-CV scaffolding, V3 review service behavior, V3 stream operations, and Score Truth metrics binding.

## Release Gate Status

| Gate | Requirement | Status |
| --- | --- | --- |
| P03-G1 | 14-day advisory operation with <= 0.2% operator overrides reversing the engine | Pending operational evidence |
| P03-G2 | Manual review queue p95 depth below 15 | Instrumented; pending soak evidence |
| P03-G3 | Staleness recovery exercised end-to-end for at least ten recovered sessions | Implemented; pending live-like recovery evidence |
| P03-G4 | At least five score ROI templates proven in production | Tooling present; production template corpus pending |
| P03-G5 | New feed paths approved for compliance and live operating limits | Clients and tests present; external signoff pending |

## Residual Limits

- Score Truth remains advisory only. It can hold, flag, and recommend, but it does not close bets as settlement authority.
- Tier C VLM is intentionally a hook in Phase 03, not a paid production path.
- The release gate depends on runtime evidence that cannot be produced by unit tests alone.
- Stream worker tables are ready, but worker deployment and durable stream transport belong to Phase 04.
- The CV template builder is usable, but production coverage depends on collecting and validating more ROI templates.

## Handoff To Phase 04

Phase 04 should use this Phase 03 foundation to add durable stream infrastructure and production promotion paths:

- Deploy Redis Streams and move score/fill/manual-review events off the local in-process bus.
- Add MinIO raw evidence storage and link Score Truth audits to retained artifacts.
- Wire the paid VLM client behind the Phase 03 fallback hook with budget and rate controls.
- Add the CV audit buffer and promotion workflow for low-confidence Stream-CV evidence.
- Build the prediction blender and calibration path that consumes advisory-grade evidence safely.
- Extend operations screens from queue visibility into ingest and stream control.

## Post-Mortem Summary

Phase 03 worked best where the authority boundaries stayed explicit: advisory evidence, settlement policy, review actions, and operator-facing metrics are now separate enough to reason about and test independently.

The main scope pressure was VLM. The implementation deliberately stopped at the Force VLM hook so Phase 04 can add paid inference, budget control, and raw evidence retention as one coherent production feature.

The next phase should preserve the same discipline: add durable infrastructure first, promote authority only after operational evidence exists, and keep every automated decision explainable to the review queue.
