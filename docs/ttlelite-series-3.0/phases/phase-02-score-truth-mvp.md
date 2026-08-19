# Phase 02 — Score Truth Engine MVP + Stream-CV Ingress
_Target duration: 2 weeks · Blast radius: medium (shadow only) · Reversibility: easy_

## Purpose
Stand up the Score Truth Engine in shadow mode alongside 2.0 settlement, and begin producing independent score evidence via Stream-CV. No user-visible settlement changes yet; every decision is compared against 2.0 via `settlement_diff_log`. This is the phase that first addresses Bug A — the "closed bets lose live scoring" defect — by proving that we can *see* the score independently even when the market closes.

## Entry criteria
- Phase 01 stable for ≥ 48 h with DLQ under control.
- Operator has reviewed Score Truth Engine §1–§8 and Stream-CV Spec §1–§10.
- At least one staging stream route populated in `stream_routes.yaml`.

## Deliverables
1. `SettlementEvidence`, `Observation` sealed hierarchy, `IdentityLock`, `Contradiction`, `Decision`, and `SettlementReason` catalog shipped (Score Truth Engine §2).
2. `AmbiguityScorer`, `ContradictionGuard`, `SettlementEngine.decide()` — all running in shadow, writing to `settlement_diff_log`.
3. New tables live: `settlement_evidence`, `contradiction`, `settlement_audit`, `settlement_diff_log`.
4. Stream-CV Phase 02 deliverables per Stream-CV Spec §18: `StreamRouter`, `StreamFetcher`, `FrameSampler`, Tier A `BoardLocator`, PaddleOCR reader, state machine, 3-frame consensus, emitting `stream.frame`.
5. Two ROI templates: `wstt.generic.v1`, `ttcup.table1.v2`.
6. Replay fixtures `cv-assets/fixtures/*` (6 clips) + CI harness.
7. API endpoints `/api/score-truth/evidence/{matchId}`, `/api/score-truth/decisions`.
8. `/v3/matches/:id/evidence` timeline view.
9. `/v3/ops/diffs` viewer with filters.

## Work breakdown
- Shadow-mode wrapper around `PaperTradingService.settleBet*` calls `SettlementEngine.decide()` in parallel; both decisions are logged and compared.
- `AmbiguityScorer` is unit-tested against synthetic inputs including adversarial "same day, same player, same opponent-name" pairings that mirror Bug A.
- Stream-CV emits `stream.frame` with a well-formed `ingestEvent`; the Score Truth Engine records them in `settlement_evidence.observations`.
- The evidence timeline UI renders the bundle ordered by `capturedAtUtc` with per-observation trust tier, confidence, and collapse-by-source toggles.

## Exit criteria
- Release Gate Checklist §4 fully ticked.
- 14-day shadow soak.
- Stream-CV replay accuracy ≥ 95 % across fixtures.
- ≥ 99.5 % shadow Decision parity with 2.0 on undisputed matches.
- All contradictions observed during the soak have been triaged and either resolved or explicitly whitelisted.

## Risks
- **False contradictions from low-trust sources.** Mitigation: trust tiers + per-source confidence floors + contradiction filtering by trust tier.
- **Stream-CV produces a plausible but wrong score.** Mitigation: 3-frame consensus + state-machine rejection; in Phase 02 Stream-CV is only a shadow source.
- **Settlement engine deadlocks on ambiguous bundles.** Mitigation: explicit `HoldOpen` timeouts; if TTL expires, the shadow path simply logs and moves on — the 2.0 path is authoritative.

## Rollback
- `features.score-truth=off` switches the shadow wrapper off; 2.0 path continues untouched.
- `features.stream-cv=off` halts all CV workers; stream observations stop flowing.

## Operator runbook
- `./scripts/deploy-phase-02.sh staging`
- `./scripts/replay-cv.sh all` — runs all 6 fixtures through Stream-CV and prints accuracy.
- `./scripts/shadow-diff-report.sh --days 7` — prints parity and the top 20 divergent decisions.
- `./scripts/rollback-phase-02.sh staging`
