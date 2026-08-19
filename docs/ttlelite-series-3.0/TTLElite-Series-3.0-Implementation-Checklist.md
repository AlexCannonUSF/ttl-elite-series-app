# TTLElite Series 3.0 — Implementation Checklist
_Document v1.0 — canonical, per-phase, per-workstream task list_

This document is the single flat list an implementer can walk top-to-bottom. Every task is atomic (≤1 engineer-day) and references the phase file it belongs to. Each task has an implicit acceptance: code merged, tests green, docs updated. Do not start a phase's tasks before that phase's entry criteria (see Release Gate Checklist) are met.

Legend: `[ ]` pending · `[~]` in progress · `[x]` done · `(BE)` backend · `(FE)` frontend · `(INFRA)` infra/ops · `(DATA)` data/migrations · `(ML)` ML pipeline · `(CV)` Stream-CV · `(DOC)` documentation.

## Phase 00 — Foundations & Scaffolding
- `[x] (INFRA)` Create `docs/ttlelite-series-3.0/` and wire it into repo README / docs index navigation.
- `[x] (INFRA)` Add `features.yaml` top-level feature-flag config. Flags required: `features.canonicaliser`, `features.stream-cv`, `features.score-truth`, `features.predict-v3`, `features.ui-shell-v3`, `features.redis-streams`, `features.stake-policy-v3`.
- `[x] (BE)` Introduce `PredictionFacade` that wraps existing `PredictionModelService` (no behaviour change).
- `[x] (BE)` Introduce `SettlementFacade` that wraps existing `PaperTradingService.settleBet*` paths (no behaviour change).
- `[x] (BE)` Introduce `FeedClient` interface + `IngestEvent` record + `FeedHealth` record (per Scraper Spec §2).
- `[x] (BE)` Wrap `HardRockOddsScraper` and `TtSeriesScraper` in `FeedClient` adapters behind the facade; emit legacy payloads unchanged for now.
- `[x] (DATA)` Add `correlation_id` column to all relevant event logs; begin populating everywhere in the request path.
- `[x] (DATA)` Create shadow tables mirroring `paper_trade_bet` and `paper_trade_session`: `paper_trade_bet_shadow`, `paper_trade_session_shadow`.
- `[x] (INFRA)` Add `liquibase`/`flyway` migration pipeline if not already present; every DB change in 3.0 must ship as a numbered migration.
- `[x] (INFRA)` Add Prometheus + Grafana dev stack in `infra/monitoring/`; export base metrics from backend and the Python predict service stub.
- `[x] (BE)` Add `shadow-diff` harness: takes a production decision, replays it through the new facade, logs diffs to `settlement_diff_log`.
- `[x] (ML)` Stub out `ttl-predict-py` microservice (health + metrics endpoints only) and deploy in dev.
- `[x] (CV)` Create `cv/` module with empty `StreamRouter`, `StreamFetcher`, `FrameSampler` classes behind `features.stream-cv=off`.
- `[x] (FE)` Create `web-v3/` workspace in the monorepo with Vite + React 19 + shadcn/ui + Tailwind v4 skeleton. No pages yet; just the shell route.
- `[x] (FE)` Mount `web-v3/` at `/v3/*`, keep 2.0 at `/`; both ship from the same Spring Boot static resources for now.
- `[x] (DOC)` Write `docs/ttlelite-series-3.0/phase-00.md` (done via this repo).
- **Exit**: Prod deploy with zero behaviour change, all facades in place, metrics visible, shadow-diff harness writing rows, `/v3/` renders a placeholder.

## Phase 01 — Data & Identity
- `[x] (DATA)` Migration: create `odds_snapshot`, `mirror_observation`, `stream_observation`, `feed_health_sample`, `ingest_dlq` tables per Scraper Spec §4.
- `[x] (BE)` `IngestionBus` v0 using Spring `ApplicationEventPublisher`; every feed adapter emits `IngestEvent`.
- `[x] (BE)` Implement `PlayerCanonicaliser` using Jaro-Winkler ≥ 0.92 + country tiebreak + first-seen anchor.
- `[x] (BE)` Populate `player_alias` table on ingestion.
- `[x] (BE)` Materialise `odds_snapshot` ticks from the Hard Rock feed adapter; keep 30-day retention.
- `[x] (DATA)` Backfill historical odds into `odds_snapshot` from existing `odds_quote` table where possible (for CLV baselines).
- `[x] (BE)` `FeedHealthService` collects p50/p95 latency and staleness per `SourceId`; emits `feed.health` events.
- `[x] (BE)` Introduce `SourceId` enum and `TrustTier` enum per Score Truth Engine §3.
- `[x] (BE)` Add `MirrorFeedClient` for SofaScore as the first independent read (read-only first — no settlement use yet).
- `[x] (FE)` `/v3/ops/feeds` page: table of feeds with live health ticks.
- `[x] (INFRA)` Prometheus metrics for feed ingestion: `ingest_events_total{source}`, `ingest_latency_ms{source, quantile}`, `ingest_dlq_depth{source}`.
- `[x] (DOC)` `phase-01.md`.
- **Exit**: All feeds unified under `FeedClient`; health visible; identity canonicalised; shadow observations flowing with zero settlement impact.

## Phase 02 — Score Truth Engine MVP + Stream-CV Ingress
- `[x] (BE)` Implement `SettlementEvidence` bundle + `Observation` sealed interface per Score Truth Engine §2.
- `[x] (BE)` Implement `AmbiguityScorer` with tests (per Score Truth Engine §5).
- `[x] (BE)` Implement `ContradictionGuard`; wire into facade in **shadow mode**.
- `[x] (BE)` Implement `SettlementEngine.decide()` that returns `Decision` objects; runs in shadow mode alongside 2.0 `settleBet*`.
- `[x] (BE)` Shadow-mode `settlement_diff_log` writes for every settlement attempt; Ops Console shows the diff.
- `[x] (CV)` Implement `StreamRouter`, `StreamFetcher` (yt-dlp + ffmpeg), `FrameSampler` (1 fps).
- `[x] (CV)` Tier A `BoardLocator` + PaddleOCR + state-machine + 3-frame consensus; emit `stream.frame` to bus. Locator, PaddleOCR adapter boundary, score-state, consensus, and event factory are in place.
- `[x] (CV)` First two ROI templates: `wstt.generic.v1`, `ttcup.table1.v2`.
- `[x] (CV)` Ship `cv-assets/fixtures/` with 2 replay clips; add unit + integration tests.
- `[x] (DATA)` Migration: `settlement_evidence`, `contradiction`, `settlement_audit`, `settlement_diff_log` tables (Score Truth Engine §8).
- `[x] (BE)` API endpoints `/api/score-truth/evidence/{matchId}` and `/api/score-truth/decisions?from=…` (shadow-only readers).
- `[x] (FE)` `/v3/matches/:id/evidence` — render evidence bundle timeline with per-observation confidence.
- `[x] (FE)` `/v3/ops/diffs` — paginated `settlement_diff_log` viewer with filters for ambiguity/contradiction.
- `[x] (INFRA)` Alerts: `ContradictionsPerDay > 0`, `StreamCVSilent`, `SettlementDiffRate > 1%`.
- `[x] (DOC)` `phase-02.md`.
- **Exit**: Two-week shadow run with: ≥99.5 % diff parity with 2.0 on undisputed matches, all contradictions triaged, ≥95 % Stream-CV tuple accuracy on replay fixtures.

## Phase 03 — Score Truth Engine Promotion + Scraper Completeness
- `[x] (BE)` Promote `SettlementEngine` from shadow to **advisory** — it can mark a bet for manual review, but cannot close a bet yet.
- `[x] (BE)` Implement `StaleLiveRecoveryService` flow per Score Truth Engine §6.2.
- `[x] (BE)` Add `HoldOpen` decision path that persists bets as `PENDING_EVIDENCE` with a TTL and polling.
- `[x] (BE)` Implement `BetSettlementPolicy` YAML loader with hot-reload + audit.
- `[x] (BE)` Add `BetsApiFeedClient` (T2); add `AiScoreFeedClient` (T3); add `ItftWttFeedClient` (T4 historical).
- `[x] (BE)` `HardRockTargetedPoller` (HR-TGT) for closed bets; integrate with `HR-TREE` discovery per Scraper Spec §3.3.
- `[x] (CV)` Tier B classic CV fallback path; `stream_routes.yaml` loader; operator `Force VLM` hook ready (not wired to API).
- `[x] (CV)` Add `cv-template-builder` tool under `tools/cv-template-builder/`.
- `[x] (FE)` Review queue: `/v3/review` paginates `ManualReview` decisions with accept/reject/comment.
- `[x] (FE)` Streams panel: `/v3/ops/feeds/streams` lists workers + VLM usage.
- `[x] (DATA)` Add `stream_worker_config`, `stream_worker_health_1m`, `stream_route` tables.
- `[x] (INFRA)` Add alerts for manual review queue depth > 15 and TTL expiries > 2/h.
- `[x] (DOC)` `phase-03.md`.
- **Exit**: 2 weeks of advisory operation with ≤0.2 % decisions overturned by operators; staleness recovery path exercised end-to-end; at least 5 distinct ROI templates in production.

## Phase 04 — Ingestion Bus + VLM Budget + Raw Store
- `[x] (INFRA)` Deploy Redis 7 in dev + staging; wire `RedisStreamsBus` implementing `IngestionBus`.
- `[x] (INFRA)` Deploy MinIO in dev + staging for raw payloads + CV audit buffer.
- `[x] (BE)` Move all feed adapters and `stream.frame` emissions from Spring events to Redis Streams behind `features.redis-streams=on`.
- `[x] (BE)` Write raw payload store writer: every ingested event persists its raw body to MinIO keyed by correlationId (Scraper Spec §5).
- `[x] (CV)` Wire Tier C VLM fallback (Gemini 2.0 Flash) via `VlmClient` adapter; include a `ClaudeHaikuVisionClient` adapter behind a switch.
- `[x] (CV)` `CostGovernor` with per-day, per-hour, per-worker caps; Prometheus `stream_vlm_*` metrics; alert `StreamVLMCostSpike`.
- `[x] (CV)` `cv-audit` MinIO bucket with 30-day lifecycle; evidence refs written into `settlement_audit.evidence_refs`.
- `[x] (ML)` First production LightGBM blender (Variant A) trained; walk-forward gates passed on 60-day slice; model card committed.
- `[x] (ML)` Platt + isotonic + split-conformal calibrators implemented and shipped as Java artefacts.
- `[x] (ML)` `ttl-predict-py` deploys blender and Markov endpoints; p99 latency budget 150 ms.
- `[x] (BE)` `PredictionFacade` routes 5 % of traffic to v3 pipeline in shadow mode; diffs logged to `prediction_diff_log`.
- `[x] (FE)` `/v3/ops/ingest` shows bus health, DLQ depth, partition lag.
- `[x] (DOC)` `phase-04.md`.
- **Exit**: Redis Streams steady state for 7 days, zero data loss, zero DLQ growth > 24 h; VLM budget steady under cap; blender Variant A shadow showing neutral-or-positive CLV.

## Phase 05 — Prediction Stack Core + Markov Simulator
- `[x] (ML)` TrueSkill-2 ratings nightly job; `TrueSkill2Service` Java reader.
- `[x] (ML)` Weng-Lin ratings nightly job; `WengLinService` reader.
- `[x] (ML)` Ensemble rater combination (`rater.ensemble.delta`).
- `[x] (ML)` Markov simulator in Python microservice; JVM orchestrator `MarkovSimulator`.
- `[x] (ML)` Variant B blender (with devigged-market features) trained & shadowed.
- `[x] (BE)` `DeviggingService` with Shin + Power + Multiplicative; unit-test against fixtures.
- `[x] (BE)` `EdgeEngine` combining `Prediction` + devigged market → `Edge`.
- `[x] (BE)` `ConformalPredictor` implementing Mondrian split conformal in Java.
- `[x] (FE)` `/v3/match/:id` prediction panel with reliability curve + SHAP top-K contributions + conformal interval.
- `[x] (FE)` Reliability & drift dashboards under `/v3/ml/quality`.
- `[x] (DOC)` `phase-05.md`.
- **Exit**: PredictionFacade shadows 100 %; Variant A matches or beats production Brier & CLV over 7-day window.

## Phase 06 — Staking v3 + Settlement Promotion
- `[x] (BE)` `StakingPolicy` v3 with fractional Kelly + portfolio caps + correlation caps + drawdown stop.
- `[x] (BE)` `policy.yaml` loader with hot-reload + audit log; kill-switch endpoint for ops.
- `[x] (BE)` Promote `SettlementEngine` from advisory to **primary** — it now closes bets; 2.0 path remains as the cold-standby code, reachable only via feature flag rollback.
- `[x] (BE)` Stream-CV becomes **required** input for matches where `market_closed_before_end == true`; absence triggers `SCORE_BACKED_ONLY` policy.
- `[x] (FE)` Session ribbon with live P&L, CLV, exposure utilisation (per UI Redesign Spec §3 and §4).
- `[x] (FE)` Command palette (`⌘K`) with navigation, bet management, feed actions.
- `[x] (INFRA)` Alerts: `ExposureCapBreach`, `StakingPolicyHotReloadFailed`, `CLVNegative7Day`.
- `[x] (DOC)` `phase-06.md`.
- **Exit**: 2 weeks of v3 settlement in production with zero Bug-A-style contradictions reaching users; staking v3 caps never breached; CLV ≥ 2.0 baseline with p<0.05.

## Phase 07 — UI Cutover
- `[x] (FE)` Port Live Board to v3 shell with FlashOnChange + TradingView Lightweight Charts for odds.
- `[x] (FE)` Port Match Detail view with tabs (Evidence, Prediction, History, Market).
- `[x] (FE)` Port Review queue, Ops Console, Feeds page to v3 shell.
- `[x] (FE)` Retire MUI v7 routes at `/` — `/v3/*` becomes the canonical root, `/` redirects.
- `[x] (FE)` A11y audit (WCAG 2.2 AA): keyboard nav, contrast, ARIA labels, reduced-motion.
- `[x] (FE)` Performance audit: LCP ≤ 2.0 s, TTI ≤ 1.5 s, initial JS ≤ 450 KB gz (per UI Redesign Spec §9).
- `[x] (INFRA)` CDN + long-term caching for v3 static bundles; stale-while-revalidate on the index HTML only.
- `[x] (DOC)` `phase-07.md`.
- **Exit**: v3 UI is default; 2.0 UI removed; perf and a11y budgets met.

## Phase 08 — Tightening, Retirement, and v3.1 Prep
- `[ ] (BE)` Decompose `PaperTradingService` into `PlacementService`, `SessionService`, `IntegrityService`, `PaperTradingFacade` per Score Truth Engine §9.
- `[ ] (BE)` Delete dead 2.0 code paths gated by feature flags that have been `off` for ≥30 days.
- `[ ] (ML)` Promote Variant B blender if Variant A + B ensemble beats solo Variant A on CLV + Brier.
- `[ ] (ML)` Begin collecting v3.1 features (proxy liquidity, schedule-aware context).
- `[ ] (CV)` Add two new platforms (a regional TT federation + an emerging youth league) behind compliance review.
- `[ ] (INFRA)` Capacity planning: rightsize workers, Redis, MinIO, Postgres. Document in `infra/capacity-2026q3.md`.
- `[ ] (DOC)` `phase-08.md`; update Master Plan §11 with lessons learned and v3.1 seeds.
- **Exit**: `PaperTradingService` < 800 LOC (pure facade); all flags in `features.yaml` either removed or defaulted to `on`; Master Plan updated; v3.1 discovery kicked off.

## Cross-cutting — Always-on tasks
- `[ ] (BE)` Every new service ships with Micrometer metrics and a dedicated Grafana dashboard.
- `[ ] (DOC)` Every phase closes with a post-mortem-lite (what went well, what surprised us, one improvement to bake in).
- `[ ] (DOC)` Model cards committed on every ML promotion; no exceptions.
- `[ ] (INFRA)` Every migration is accompanied by a rollback SQL file in the same folder.
- `[ ] (BE)` Every feature flag ships with an "owner" in `features.yaml` and an expiry date; CI fails if expiry passes without removal or renewal.

---
*End of Implementation Checklist v1.0.*
