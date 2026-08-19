# Phase 08 — Tightening, Retirement, and v3.1 Prep
_Target duration: open-ended · Blast radius: low (cleanup) · Reversibility: git revert_

## Purpose
Finish the strangler-fig migration by decomposing `PaperTradingService` into the five services defined in Score Truth Engine §9, delete the dead 2.0 code paths, retire obsolete feature flags, right-size infrastructure, and plant the seeds for v3.1.

## Entry criteria
- Phase 07 stable for ≥ 30 days.
- No `p0` or `p1` issues open.
- Operator has at least one week of uninterrupted focus available for the decomposition PR.

## Deliverables
1. `PaperTradingService` decomposed into `PlacementService`, `SessionService`, `IntegrityService`, `SettlementEngine` (existing), and a thin `PaperTradingFacade` (< 800 LOC).
2. Dead code gated by `off`-for-30-days flags removed.
3. Promoted Blender Variant B to the production ensemble if §8 gates pass.
4. Two new Stream-CV platforms added behind compliance review.
5. Capacity plan document `infra/capacity-2026q3.md` written, reviewed, and committed.
6. Master Plan §11 updated with lessons learned and v3.1 seeds.
7. At least three v3.1 ideas filed as tickets with owners.

## Work breakdown
- Decomposition uses a "red tests first" approach: add tests pinning current behaviour, then refactor in small PRs. Target < 500-LOC PRs with shadow-diff verification each time.
- v3.1 seeds (suggested):
  - **Proxy-liquidity features** in the prediction stack.
  - **Schedule-aware context features** (rest gap, tournament stage, weekday pattern).
  - **Automatic ROI-template discovery** so new broadcaster overlays require no operator work.
- Capacity plan must include per-component rightsizing (JVM heaps, Redis memory, MinIO storage, Postgres IOPS), projected growth over the next quarter, and a cost envelope.

## Exit criteria
- `PaperTradingService` ≤ 800 LOC (measured by `cloc`).
- Feature-flag expirer CI lint is green (no expired flags).
- Capacity plan signed off by operator.
- v3.1 seed tickets filed with owners.

## Risks
- **Refactor introduces silent regressions.** Mitigation: shadow-diff remains on for every decomposition PR; high-test-coverage gates.
- **Capacity plan drifts from reality.** Mitigation: plan is a living doc with a monthly revisit date.

## Rollback
- Git revert individual decomposition PRs if shadow-diff detects a regression.
- Removed feature flags can be re-added with a diff from the removal PR if absolutely needed.

## Operator runbook
- `./scripts/decompose-pts.sh --service PlacementService` — scaffolds a new service and migrates the specified methods under shadow-diff.
- `./scripts/flags-expire-report.sh` — lists flags due for removal.
- `./scripts/capacity-forecast.sh --quarter 2026Q3` — generates the capacity plan draft.
