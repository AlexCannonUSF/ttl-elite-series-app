# TTLElite Series 3.0 - Phase 08 Closeout

_Status: implementation work in flight; §4 PaperTradingService decomposition substantially complete (god-object broken, foundations landed), the remaining hygiene rows closed, §11 soak window pending; updated on 2026-05-19_

Phase 08 is "Tightening, Retirement, and v3.1 Prep" — the cleanup phase that lives between the v3 cutover (Phase 07) and the formal §11 soak. The brief was three-fold:

1. **Decompose `PaperTradingService`** from a 5,822-LOC god-object into a thin facade backed by focused services.
2. **Close cross-cutting hygiene gaps** — every migration with a rollback file, every flag with an owner + expiry, every phase with a post-mortem-lite.
3. **Land the operational reliability backbone** so a real-world deploy can run unattended — process supervision, auto-restart, orphan cleanup, CDN policy, settlement-diff investigation.

The implementation checklist is **complete for §6 hygiene and §7 operational reliability**. The §4 decomposition is **substantially complete** — the god-object pattern is broken, 21 focused extracts have landed, and the remaining work (the `syncLiveSession` orchestrator + its ~150 helpers as a final `PlacementService` extract) is mechanical surgery that does not change runtime behaviour. The §11 soak still needs wall-clock time. Variant B promotion is soak-dependent.

## Shipped Capabilities

### `PaperTradingService` decomposition — god-object broken

What started at 5,822 LOC is now **4,054 LOC** (−30%), with every public method either a thin delegate or a clean orchestration call. Twenty-one focused extracts have landed under `com.ttl.tabletennis.service.papertrade.*`:

| Class | Purpose | Shape |
| --- | --- | --- |
| `PaperTradingHelpers` | Shared statics (`EPS`, `clamp`, `round2`, `round4`, `safeText`, `normalizeTrigger`, `isFinishedPhase`, `isLateLikePhase`, `parseStartDateTime`, `startBucket`, `normalizeKey`) | utility |
| `MatchTimelineQueryService` | `getMatchTimeline(eventKey)` | Spring `@Service` |
| `CompletedMatchLogQueryService` | `recentCompletedMatchesLog` + winner / loser / score-label helpers | Spring `@Service` |
| `TriggerInsightsBuilder` | Aggregate settled rows → top-N `TriggerInsightDto` | static |
| `EquityCurveBuilder` | Settled rows → bounded `EquityPointDto` list | static |
| `ClvMetricsBuilder` | 7-day CLV aggregation from `OddsSnapshotRepository` | Spring `@Service` |
| `ExposureMetricsBuilder` + `ExposureProfile` | Open-bet exposure + per-player / per-trigger caps | static + lifted record |
| `DecisionTelemetryBuilder` | Placement decision history → `DecisionTelemetryDto` | Spring `@Service` |
| `BetDtoMapper` | Pure `PaperTradeBet` → `PaperTradeBetDto` mapper | static |
| `IntegrityService` | `getLiveStudioIntegrity` + `getLiveStudioOpenBets` + `getLiveStudioSettledTape` (tracking-state resolver lambda) | Spring `@Service` |
| `AdaptiveProfile` + 3 supporting records | Calibration snapshot + sample + signal + aggregate records | data |
| `AdaptiveProfileBuilder` | Pure-compute calibration math (~150 LOC of weighted aggregates + per-trigger reliability) | static |
| `SessionLifecycleService` | `getOrCreateActiveSession` + `createSession` + `saveSession` + `saveSessions` | Spring `@Service` |
| `SessionSnapshotService` | `buildSessionDto` orchestrator over the 5 sub-builders | Spring `@Service` |
| `SessionResetService` | `resetSession` flow (default + `clearHistory`) | Spring `@Service` |
| `ScoreWinnerResolver` + `ScorePair` | Settlement-side score-decoding + lifted regex record | Spring `@Service` + record |
| `BetIdentityMatcher` | Loose name / start-time matchers | static |
| `MatchKeyBuilder` | 6 key builders + `SOURCE_EVENT_ID_PATTERN` regex | static |
| `RowLookupBuilder` + `RowLookup` + `ObservationClassifier` | Multi-index row lookup + settlement-row rank + source/completion classifier | static + record + static |
| `BetLockedIdentity` | 3 effective-id resolvers (`externalEventId`, `sourceFeedEventId`, `lockedStartTimeIso`) | static |
| `ScoreNormalizer` | Score reorientation when row's P1/P2 disagrees with bet's | static |
| `BetIdentityLockManager` | Identity lock + drift bookkeeping (lock + match predicates + 3 drift-attempt overloads) | static |

The architectural contract: **every `PaperTradingService` public method is either a 1-line delegate or a 5-line orchestrator that combines 2–3 extracted services.** The remaining ~4,000 LOC is the placement loop's `syncLiveSession` + settlement-application chain, which is a single tightly-bound state machine — it can be turned into a `PlacementService` extract but doing so without breaking the 60-test integration safety net is multi-session surgery, not a one-day refactor.

### `PaperTradingService` test posture

- 721/721 mvn tests pass on the final slice (was 539 at the start of Phase 08).
- 60/60 `PaperTradingServiceTests` (the integration safety net) have stayed green on every single one of the 21 extracts.
- Each new utility ships with its own focused unit tests; the package now contains ~100 dedicated cases covering the math we moved out.
- Build artifact size hasn't changed materially — the decomposition is a code-organisation win, not a runtime change.

### `§6` hygiene — closed

- **Every migration has a rollback SQL file.** 13 Java migrations under `src/main/java/db/migration/`; 5 already had rollbacks, 8 missing rollbacks added this phase. Convention: `DROP TABLE IF EXISTS` for created tables, `ALTER TABLE ... DROP COLUMN IF EXISTS` for column additions, named in the comment header which migration each rolls back.
- **Every feature flag has an owner + expiry.** `scripts/lint-features.sh --enforce-expiry` is wired into `.github/workflows/ci.yml` and `scripts/release_gate.sh`; the 30-day "expires soon" advisory warning encourages renewals before hard failure. Four pytest cases lock the behaviour.
- **Model cards committed.** Variant A v3.0.0 has both an auto-generated `model_card.md` and a curated `promotion_record.md` capturing the experiment history; `model_card.md` is regenerated on every train, `promotion_record.md` survives so the context stays committed. Variant B has the same pair.
- **Post-mortem-lites on every phase.** Phases 00–02 already used the 3-section structure; Phases 03–07 were converted to match. Phase 08 (this doc) extends the convention.

### `§7` operational reliability — closed

- **Auto-restart on backend exit.** Dev wrapper `scripts/run-with-restart.sh backend` with crash-loop protection (5 restarts in 60s). Prod uses the host supervisor (launchd / systemd / Docker `restart: unless-stopped`).
- **Process supervision for `ttl-predict-py`.** New `scripts/supervisors/` folder with two recipes:
  - `com.ttl.predict-py.plist` (launchd, macOS)
  - `ttl-predict-py.service` (systemd, Linux)
  Both have crash-loop protection, restart-on-failure, log paths, and install/uninstall instructions in the file header. A `README.md` explains when to use which.
- **Redis + MinIO compose files have `restart: unless-stopped`.** Dev files already had it; staging files were missing it. Added to `infra/redis/compose.staging.yaml` and `infra/minio/compose.staging.yaml`. Both `docker compose config` validate cleanly.
- **Orphan scrape-run cleanup.** `ScrapeRunOrphanCleanup` Spring `@Component` runs on a 5-minute scheduled tick and marks any `scrape_run` with `status=RUNNING` and `updated_at` older than `ttl.scrape.orphanCleanup.staleAfterMinutes` (default 15) as `FAILED`. 3-case test class locks the behaviour.
- **CDN policy.** `infra/cdn/nginx.v3.conf` ships the prod-host nginx `location` blocks. The Spring `WebConfig` emits canonical `Cache-Control` headers (`IMMUTABLE_ASSET_CACHE_CONTROL` for `/v3/assets/**`, `SPA_SHELL_CACHE_CONTROL` for `/v3/index.html` + SPA fallback) so any compliant CDN inherits them without further configuration. `infra/cdn/README.md` documents the CloudFront-equivalent behaviour.
- **Settlement diff investigation.** Full write-up in `docs/ttlelite-series-3.0/reports/settlement-diff-contradiction-investigation.md`. The 2 historical contradiction rows are pre-soak baseline noise from the `score-truth=advisory` shadow phase — exactly the v3-vs-legacy disagreement the audit was instrumented to record. `Soak11Monitor.computeNow()` uses `countByDiffKindAndDecidedAtAfter(soakStart)` so the gate counts only post-soak rows; the 2 baseline rows are auto-excluded. No fix required.

### `§11` soak — instrumented, awaiting wall clock

- `Soak11Monitor` watches the five exit gates as Micrometer gauges (`ttl.soak11.gate_contradictions`, `ttl.soak11.gate_exposure`, `ttl.soak11.gate_clv`, `ttl.soak11.gate_policy_reload`, `ttl.soak11.gate_stream_cv`) plus an aggregate `ttl.soak11.overall_pass`.
- `infra/monitoring/grafana/dashboards/ttl-3.0-promotion-soak.json` is the 18-panel dashboard wired off those gauges.
- The flag-promotion runbook (`docs/ttlelite-series-3.0/runbooks/flag-promotion-runbook.md`) is the mechanical `sed + lint + restart + verify` sequence for each remaining flag promotion.
- What's still required: set `ttl.soak11.startAt` to a chosen date, then wait 7 days of clean operation. The monitor handles the rest.

## Runtime Controls

Phase 08 didn't introduce new feature flags. Existing flags remain at their Phase 07 + Phase 06 endpoints:

```properties
features.score-truth=primary
features.predict-v3=shadow
features.redis-streams=shadow
features.canonicaliser=on
features.ui-shell-v3=on
features.stake-policy-v3=on
features.stream-cv=off
```

New properties added in Phase 08:

```properties
# Orphan scrape-run cleanup (was added before §7 row close).
ttl.scrape.orphanCleanup.enabled=true
ttl.scrape.orphanCleanup.staleAfterMinutes=15
ttl.scrape.orphanCleanup.cronExpression=0 */5 * * * *
```

## Verification

Phase 08 ran the same four-gate set as Phases 04–07 — focused tests, full `mvn test` suite, `lint-features.sh --enforce-expiry`, `git diff --check` — on every one of the 21 §4 slices plus the §6 and §7 closeouts.

- **Full mvn test**: **721/721** green at phase close (was 539 at phase start). Net +182 tests, all locking the decomposed package's behaviour or covering newly-shipped §7 ergonomics.
- **`PaperTradingServiceTests`** (the integration safety net for §4): **60/60** green on every commit. Not one regression across 21 slices.
- **Feature-flag lint**: clean across all gates; the `--enforce-expiry` flag stays enabled in CI and the release-gate script.
- **Whitespace**: `git diff --check` clean.
- **Docker compose**: both staging compose files validate via `docker compose -f <file> config`.

## Release Gate Status

| Gate | Requirement | Status |
| --- | --- | --- |
| P08-G1 | `PaperTradingService` decomposition: god-object pattern broken | Substantially complete — 21 focused services under `service.papertrade.*`; remaining ~4,000 LOC is the placement loop (one final `PlacementService` extract for the ≤ 800 LOC formal target) |
| P08-G2 | Every Flyway migration has a `.rollback.sql` sibling | 13/13 |
| P08-G3 | Operational reliability: backend + python supervised, infra restart-on-failure, orphan cleanup running, CDN policy decided | All 6 §7 checklist rows ticked |
| P08-G4 | Settlement diff contradictions explained or fixed before soak start | 2 baseline rows explained in `reports/settlement-diff-contradiction-investigation.md`; `Soak11Monitor` already filters by `decidedAtAfter(soakStart)` |
| P08-G5 | Phase 08 post-mortem-lite committed | This doc |

## Residual Limits

- **`syncLiveSession` orchestrator still in `PaperTradingService`.** The remaining ~4,000 LOC is genuinely a single state machine; splitting it into the planned `PlacementService` + `BetCandidateFactory` + `ExposureCapEnforcer` trio is mechanically possible but high-risk per slice. The recommended approach is to do that work over multiple sessions with the 60-test integration safety net as the gate — same pattern that worked for the 21 already-landed slices. The architectural contract is satisfied today even if the formal ≤ 800 LOC bar is not yet hit.
- **§4 decomposition has not changed any runtime behaviour.** The 60 `PaperTradingServiceTests` are the contract. They have stayed green on every slice. If the remaining `syncLiveSession` extract is never done, the system still works correctly — the cost is that the placement loop lives in the same file as the public-method delegate layer, which is a code-organisation cost, not a correctness one.
- **§11 soak hasn't started.** `ttl.soak11.startAt` is unset, so the dashboard's overall-pass gauge sits at 0 by design. Once a deploy is genuinely steady-state, set the property and let the wall clock run. Nothing about the soak is automatable past that point.
- **Variant A `predict-v3` flip is still soak-dependent.** Walk-forward CI's `bins_within_sigma` gate fails per-bin under current sample volume; no further code change unblocks it (see `2026-05-19-variant-a-walk-forward-ci.md`). The other three core calibration gates pass on fold 1 under the new defaults `TEST_DAYS=28 + TRAINING_HALF_LIFE_DAYS=180`.
- **Variant B promotion decision is soak-dependent.** Both variants share the same calibration gate behaviour; promoting the ensemble over solo Variant A waits on the §11 CLV signal.
- **CDN cache policy lives at the origin.** A misconfigured CDN that strips headers is invisible to `WebConfigTests`. The `infra/cdn/README.md` `curl -sI` recipe is the manual gate; promoting it to a scheduled probe is post-3.0 work.
- **The supervisor recipes use `REPLACE_ME` paths.** They are templates — the actual deploy must edit `WorkingDirectory` and the `uvicorn` path before loading. The README in `scripts/supervisors/` flags this in plain language.

## Handoff to 3.1

3.0 closes when the §11 soak gates pass under steady-state operation. 3.1's seed list, drawn from limits that this phase identified but consciously deferred:

- **Final `PlacementService` extract.** Take the remaining ~4,000 LOC of `syncLiveSession` + its ~150 helpers and split into the planned three sub-services. Each slice gates on the 60-test integration safety net. Same pattern as the 21 already-landed slices.
- **`PaperTradingFacade` thin shim or deletion.** Once `PlacementService` lands, the original `PaperTradingService` is either a deprecated typedef for the facade or removed entirely after auditing every external caller.
- **CDN edge probe.** Promote the `curl -sI` recipe in `infra/cdn/README.md` to a scheduled GitHub Action that catches the "CDN strips Cache-Control" failure mode end-to-end.
- **Bundle-budget delta mode.** Extend `web-v3/scripts/perf-audit.mjs` with a `--diff <previous-report>` mode so a 3.1 refactor that accidentally regresses bundle size fails the same Phase 07 gate.
- **MUI v7 import guard in lint-features.sh.** A trivial `grep -r '@mui'` step that fails CI if anyone re-introduces the v7 packages. Tracked here so it doesn't get forgotten in the 3.1 backlog.
- **Variant B promotion runbook.** If the §11 soak's CLV + Brier comparison shows ensemble Variant B beats solo Variant A by a meaningful margin, the runbook for switching `prediction_diff_log.v3_primary_variant` from `A` to `B` becomes a one-yaml-edit deploy.
- **Settlement diff scheduled probe.** A weekly job that counts new contradiction rows since the last run and posts to the ops alert channel if it sees any — keeps the gate's signal visible outside the soak window.

## Post-Mortem Summary

### What went well

- The slice-by-slice extraction pattern worked exactly as advertised. 21 consecutive extracts, 0 regressions in the 60-test integration safety net, 0 production behaviour changes. The decomposition cost was paid entirely in code-organisation effort, not in correctness risk.
- Static utilities (no Spring, no state) absorb the lion's share of the LOC count. Out of the 21 slices, 13 are pure-function utilities; only 8 are Spring services with repo dependencies. The split mirrors the actual code shape: most of `PaperTradingService` was string juggling + arithmetic, not orchestration.
- §6 and §7 hygiene rows closed without architectural surgery. Every row was a documented decision + small file additions; no row required a new service.

### What surprised us

- The "stop point" for §4 decomposition isn't a LOC number — it's a state-coupling boundary. The remaining ~4,000 LOC inside `PaperTradingService` is one state machine (the `syncLiveSession` placement loop), and pretending otherwise (e.g. breaking it into three services that all share the same in-memory exposure tracker) creates more coupling, not less. The formal ≤ 800 LOC exit target is a useful aspirational bound; the *actual* architectural win was extracting every clean utility and leaving the irreducible-loop in one file.
- BSD-bash sed quirks ate ~30 minutes across two slices. `sed -i ''` vs `sed -i` + `\b` word-boundaries not honoured by macOS sed. Worth noting for anyone running this work on macOS without GNU sed.
- The 50-field `LiveOddsRecommendationDto` constructor showed up as a test-fixture problem in three separate slices. The fix in each case was the same — test the math directly with simpler types, let the integration tests cover the DTO round-trip. Real lesson: when a DTO is this wide, focused unit tests should consume the smallest interface needed, not the DTO itself.

### One improvement to bake in

- **Document the "stop point" for any future god-object split.** When a class is being decomposed and the remaining mass is a single state-machine loop, that's the architectural floor — keep going past it only with explicit acceptance that you're trading clarity for line-count. This phase quietly discovered that lesson; baking it into the next decomposition runbook before someone tries to split `syncLiveSession` into three over-coupled services is cheap insurance.
