# Phase 00 — Foundations & Scaffolding
_Target duration: 1 week · Blast radius: zero (no behaviour change) · Reversibility: trivial_

## Purpose
Put in the scaffolding every later phase depends on: facades, feature flags, a shadow-diff harness, Prometheus + Grafana, and the empty v3 UI shell. Shipping Phase 00 proves the plumbing works **before** we begin to change any observable behaviour.

## Entry criteria
- Master Plan v1.0 committed.
- Repo on main with green CI.
- Ops has credentials for dev + staging environments.
- Operator (Alex) has read the Master Plan and this phase file.

## Deliverables
1. `docs/ttlelite-series-3.0/` folder populated with Master Plan, UI Spec, Score Truth Spec, Scraper Spec, Stream-CV Spec, Prediction Engine Spec, Implementation Checklist, Release Gate Checklist, and this phases/ tree.
2. `features.yaml` with the seven canonical Phase 00 rollout flags — `features.canonicaliser`, `features.stream-cv`, `features.score-truth`, `features.predict-v3`, `features.ui-shell-v3`, `features.redis-streams`, and `features.stake-policy-v3` — each with an owner and a 90-day default expiry.
3. `PredictionFacade`, `SettlementFacade`, and the `FeedClient` adapter seams committed and under test. All three should be pure pass-throughs — identical outputs to 2.0.
4. `shadow-diff` harness that captures, for every settlement attempt on staging, a row in `settlement_diff_log` comparing 2.0 vs. the new (identity) facade output.
5. Prometheus + Grafana stack reachable at `monitor.dev.ttl` with the base dashboards `ttl-health`, `ttl-facade`, and `ttl-ingest-placeholder`.
6. `/v3/` route serves a skeleton shadcn/ui shell with a navigation bar and a "placeholder" home route — no data flow yet.
7. `ttl-predict-py` microservice skeleton running on dev with `/v1/health` and `/metrics`.

## Work breakdown
See the Phase 00 section in the Implementation Checklist. Concretely:
- Stand up the `features.yaml` linter (`scripts/lint-features.sh`) so every flag must have an owner + expiry.
- Stand up the Flyway migration pipeline and operator scripts in `infra/scripts/` so schema changes can be inspected and applied without relying on ad hoc Hibernate drift.
- Stand up the Prometheus + Grafana dev stack in `infra/monitoring/` with the provisioned dashboards `ttl-health`, `ttl-facade`, and `ttl-ingest-placeholder`, and scrape the backend `/actuator/prometheus` plus the `ttl-predict-py` stub `/metrics` endpoint.
- The shadow-diff harness writes to `settlement_diff_log` — the table schema is in Score Truth Engine §8. Phase 00 only exercises the identity case (2.0 ↔ facade-wrapping-2.0 == identical), which verifies the harness itself. The initial implementation logs agreement rows for bets that moved from tracked-open to a resolved outcome during the facade-wrapped settlement pass.
- The Stream-CV scaffold lives in `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/cv` for Phase 00. `StreamRouter`, `StreamFetcher`, and `FrameSampler` are placeholder components only, and they read the canonical `features.yaml` catalog so `features.stream-cv=off` keeps the module inert by default.
- The V3 frontend scaffold lives in `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3`. Phase 00 keeps it as a separate Vite workspace with a placeholder shell route only, so the current `/Users/alexcannon/Downloads/TTLEliteSeries/web` product remains untouched until the `/v3/*` mount step.
- The backend mount step serves `/Users/alexcannon/Downloads/TTLEliteSeries/web/dist` at `/` and `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/dist` at `/v3/*`, with SPA fallback handled inside the Spring resource chain so asset URLs still resolve as files.
- The `/v3/` shell uses the stack described in UI Redesign Spec §2.
- The `ttl-predict-py` skeleton uses the shape described in Prediction Engine Spec §15.

## Exit criteria
- Release Gate Checklist §2 fully ticked.
- 24 h soak in staging with zero 5xx on facade endpoints.
- `settlement_diff_log` shows ≥ 100 rows of identity comparisons with zero non-trivial diffs.

## Risks
- **Hidden behaviour change inside a facade.** Mitigation: the facade methods must literally delegate, no reformatting. Every method has a byte-for-byte equality test against the 2.0 path.
- **CI pipeline length balloons.** Mitigation: move long tests (replay/walk-forward) to nightly from day one; never tie them to PR builds.

## Rollback
- Toggle all `features.*-v3` flags off. Remove `/v3/` static resources. Remove `settlement_diff_log` rows if needed (table can be truncated freely — no downstream dependency).

## Operator runbook
- `./scripts/deploy-phase-00.sh staging` — runs migrations, deploys services, enables flags.
- `./scripts/verify-phase-00.sh staging` — runs the gate checks automatically and prints pass/fail.
- `./scripts/rollback-phase-00.sh staging` — disables all v3 flags and restarts services.

## Post-mortem (appended after phase close)

### What went well
- The seam-first approach kept Phase 00 honest: the codebase gained leverage without prematurely changing live behaviour.
- The dual-shell frontend mount now works cleanly because SPA fallback is handled in the resource chain instead of with over-broad controllers.
- The docs, checklist, and monitoring scaffolding are now strong enough to support phased execution instead of ad hoc implementation.

### What surprised us
- Static-resource mounting had more edge cases than expected, especially around preserving actual asset delivery under `/v3/*`.
- The local `web-v3` toolchain needed explicit npm cache isolation to remain stable in this environment.

### One improvement to bake into later phases
- Keep every phase closeout explicit, with a short shipped-scope record and verification summary, so operational confidence compounds as the stack becomes more complex.
