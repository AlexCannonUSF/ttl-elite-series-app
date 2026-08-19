# TTLElite Series 3.0 — Phase 02 Closeout
_Status: implementation complete · Release gate / shadow soak pending · Updated on 2026-04-28_

Phase 02 implementation is now complete. The repository has the Score Truth Engine running in shadow-only form, persistence for evidence, contradictions, audit, and diff rows, V3 readers for score-truth evidence and settlement diffs, Phase 02 monitoring alerts, and a deterministic Stream-CV Tier A path with replay fixtures.

This document keeps the same split as Phase 01:

- **Implementation checklist status**: complete
- **Formal release-gate status**: not yet signed off in this repo

The remaining gap is operational evidence: the 14-day shadow soak, production/staging parity numbers, contradiction triage history, and security smoke results have not been recorded here.

## What shipped

1. **Score Truth model and decision contracts**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/settlement/SettlementEvidence.java` is the immutable evidence bundle for one settlement attempt.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/settlement/observation/Observation.java` and its `DatabaseObservation`, `LiveObservation`, `MirrorObservation`, `OfficialObservation`, and `StreamObservation` implementations model source-specific evidence.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/settlement/Decision.java` and the concrete `Settle`, `HoldOpen`, `Escalate`, `ManualReview`, and `VoidDecision` types give the engine explicit outcomes instead of implicit side effects.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/settlement/SettlementReason.java` centralizes reason codes used by shadow decisions.

2. **Ambiguity and contradiction logic**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/settlement/AmbiguityScorer.java` scores evidence bundles into explicit ambiguity bands.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/settlement/ContradictionGuard.java` blocks incompatible evidence before any future settlement promotion.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/settlement/DefaultSettlementEngine.java` evaluates bundles in shadow mode and returns decision objects.

3. **Shadow diff integration**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/SettlementFacade.java` keeps the 2.0 settlement path authoritative while recording score-truth shadow comparisons.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/SettlementDiffLogService.java` writes and queries `settlement_diff_log`.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OpsSettlementDiffService.java` prepares paginated V3 ops data for settlement diff review.

4. **Score Truth persistence**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/db/migration/V20260419001__phase_02_score_truth_persistence.java` creates the Phase 02 score-truth persistence tables.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/db/migration/V20260419001__phase_02_score_truth_persistence.rollback.sql` ships the rollback sidecar.
   - The runtime entities and repositories are:
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/SettlementEvidenceRecord.java`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/SettlementContradictionRecord.java`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/SettlementAuditRecord.java`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/SettlementEvidenceRecordRepository.java`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/SettlementContradictionRecordRepository.java`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/SettlementAuditRecordRepository.java`

5. **Score Truth readers and V3 UI**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/ScoreTruthController.java` exposes shadow-only score-truth evidence readers.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/ScoreTruthQueryService.java` assembles evidence responses.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/routes/MatchEvidenceRoute.tsx` renders `/v3/matches/:id/evidence`.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/routes/OpsDiffsRoute.tsx` renders `/v3/ops/diffs`.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/features/score-truth/api.ts` and `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/features/ops-diffs/api.ts` provide the frontend API clients.

6. **Stream-CV Tier A ingress**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/cv/StreamRouter.java`, `StreamFetcher.java`, and `FrameSampler.java` resolve streams, build the `yt-dlp -> ffmpeg` plan, and parse sampled MJPEG frames.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/cv/RoiTemplateCatalog.java` and `BoardLocator.java` load ROI templates and locate configured scoreboard regions.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/cv/ScoreboardTextReader.java`, `ScoreboardImagePreprocessor.java`, and `PaddleOcrDigitEngine.java` form the Phase 02 OCR adapter boundary.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/cv/ScoreStateMachine.java`, `StreamFrameConsensus.java`, and `StreamFrameEventFactory.java` validate score progression, require three-frame consensus, and emit `stream.frame` ingestion events.

7. **Stream-CV assets and replay gate**
   - ROI templates now exist at:
     - `/Users/alexcannon/Downloads/TTLEliteSeries/cv-assets/roi/wstt.generic.v1/roi.json`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/cv-assets/roi/ttcup.table1.v2/roi.json`
   - Replay fixtures now exist at:
     - `/Users/alexcannon/Downloads/TTLEliteSeries/cv-assets/fixtures/wstt.generic.v1-short-rally/clip.json`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/cv-assets/fixtures/ttcup.table1.v2-deuce-finish/clip.json`
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/cv/StreamCvReplayFixtureTests.java` replays those fixtures through the Java pipeline.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/scripts/cv-replay.sh` runs the fixture gate locally.

8. **Monitoring and alerts**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/config/TtlBaseMetricsBinder.java` exposes settlement diff and contradiction gauges.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/infra/monitoring/prometheus/rules/ttl-phase-02-alerts.yml` defines `ContradictionsPerDay`, `SettlementDiffRateHigh`, and `StreamCVSilent`.
   - `/Users/alexcannon/Downloads/TTLEliteSeries/infra/monitoring/prometheus/prometheus.yml` wires the Phase 02 rules into the local monitoring config.

## Verification performed

The following implementation-level verification has been completed in-repo during Phase 02:

1. **Score Truth engine tests**
   - Verified test groups include:
     - `SettlementEvidenceTests`
     - `SettlementEvidenceBuilderTests`
     - `AmbiguityScorerTests`
     - `ContradictionGuardTests`
     - `DefaultSettlementEngineTests`
     - `SettlementFacadeTests`
     - `SettlementDiffLogServiceTests`
     - `SettlementShadowAuditServiceTests`

2. **Persistence and API tests**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/config/FlywayMigrationTests.java` covers the Phase 02 migration.
   - Verified API/service tests include:
     - `ScoreTruthControllerTests`
     - `ScoreTruthQueryServiceTests`
     - `OpsSettlementDiffServiceTests`
     - `V3OpsControllerTests`

3. **Stream-CV tests**
   - Verified test groups include:
     - `StreamCvScaffoldTests`
     - `StreamCvRecognitionTests`
     - `StreamCvReplayFixtureTests`
   - `/Users/alexcannon/Downloads/TTLEliteSeries/scripts/cv-replay.sh all` passed against the two Phase 02 replay fixtures.

4. **Full backend regression**
   - `./mvnw test` passed locally with 220 tests, 0 failures, and 0 errors after the Phase 02 fixture closeout.

5. **Frontend verification**
   - `npm run build` succeeded in `/Users/alexcannon/Downloads/TTLEliteSeries/web-v3` after the Phase 02 V3 evidence and ops-diff routes were added.

6. **Monitoring verification**
   - `docker compose -f infra/monitoring/compose.yaml config` succeeded.
   - Prometheus `promtool check config` succeeded with the Phase 02 alert rules loaded.

7. **Feature flag validation**
   - `/Users/alexcannon/Downloads/TTLEliteSeries/scripts/lint-features.sh` passed for the seven canonical rollout flags.

## Release-gate status

Phase 02 implementation is complete, but formal signoff remains open because the release checklist requires operational evidence that cannot be proven by unit tests alone.

| Gate | Current status | Notes |
|---|---|---|
| `P02-G1` Ambiguity scorer unit tests | Evidenced locally | `AmbiguityScorerTests` passes. The release checklist mentions property-based coverage; this repo currently has deterministic adversarial fixtures rather than a jqwik/property suite. |
| `P02-G2` Contradiction guard simulation | Not evidenced | `ContradictionGuardTests` and shadow audit tests exist, but no 14-day labelled shadow replay has been recorded here. |
| `P02-G3` Stream-CV replay accuracy | Partially evidenced | `./scripts/cv-replay.sh all` passes on the two Phase 02 fixtures. The release checklist still asks for the full six-fixture corpus, so four additional fixtures remain for later gate completion. |
| `P02-G4` Settlement diff parity | Not evidenced | Shadow diff rows are written and visible, but the ≥99.5 % 14-day parity report has not been run or attached. |
| `P02-G5` Evidence endpoint authenticated and authorised | Not evidenced | Score-truth reader endpoints and controller tests exist; no security smoke script/result is present in this repo state. |

**Summary**: Phase 02 build work is complete, but release promotion is still blocked on shadow soak, full fixture corpus, parity reporting, contradiction triage evidence, and security smoke evidence.

## Residual limits and known follow-up items

1. **Shadow-only authority**
   - Score Truth can observe, decide, log, and surface diffs, but it does not close bets.
   - Phase 03 begins the advisory path; Phase 06 is where primary settlement authority is considered.

2. **Replay corpus scope**
   - The implementation checklist asked for two Phase 02 replay clips, and those are present.
   - The formal release gate asks for six fixtures; the remaining four should be added before claiming full `P02-G3` signoff.

3. **Stream-CV is still Tier A only**
   - Template ROI, OCR adapter boundary, state validation, and consensus exist.
   - Tier B classic CV, Tier C VLM fallback, cost governor, and operator controls are Phase 03/04 work.

4. **Operational evidence gap**
   - The 14-day shadow soak, contradiction triage log, endpoint security smoke, and parity report are not recorded here.

## Handoff to Phase 03

Phase 03 can now start from a working shadow engine and a deterministic Stream-CV replay harness. The immediate next work is:

1. Promote Score Truth from shadow-only to advisory: manual-review marking, hold-open TTLs, and stale-live recovery.
2. Add `BetSettlementPolicy` YAML loading with audit and hot reload.
3. Expand feed coverage with `BetsApiFeedClient`, `AiScoreFeedClient`, `ItftWttFeedClient`, and `HardRockTargetedPoller`.
4. Add Stream-CV Tier B fallback, operator `Force VLM` hook, and `tools/cv-template-builder/`.
5. Build `/v3/review` and `/v3/ops/feeds/streams`.
6. Add `stream_worker_config`, `stream_worker_health_1m`, and `stream_route` tables.

## Post-mortem summary

### What went well

- The phase kept settlement authority conservative: the new engine can explain itself and log disagreements without changing the live outcome path.
- Evidence, contradiction, and diff persistence gave the V3 UI real operational surfaces instead of mock dashboards.
- Stream-CV now has an executable replay gate, which turns a research-heavy subsystem into something we can regression-test.

### What surprised us

- The release gate is ahead of the Phase 02 implementation checklist on replay fixture count and security evidence.
- The OCR adapter boundary needed to stay JVM-friendly: the app can call an external PaddleOCR command, but tests use deterministic recognizers so CI does not depend on native OCR installs.

### What we should keep doing

- Keep implementation completion and operational signoff separate.
- Keep adding small deterministic fixtures before adding costly live or video-heavy tests.
- Keep every Phase 03 promotion path reversible and observable before it touches settlement authority.
