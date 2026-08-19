# TTLElite Series 3.0 — Documentation Index
_v1.0 — 2026-04-16_

This folder contains the complete planning and implementation package for TTLElite Series 3.0. It is organised so an engineer, operator, or reviewer can enter at the Master Plan and drill down into exactly as much detail as they need.

## Reading order
1. **`TTLElite-Series-3.0-Master-Plan.md`** — the one-stop overview. Read this first. Every other document in this folder is referenced by section number from the Master Plan.
2. **`TTLElite-Series-3.0-UI-Redesign-Spec.md`** — the visual and interaction design, the stack choice (shadcn/ui + Tailwind v4), the information architecture, and the performance + a11y budgets.
3. **`TTLElite-Series-3.0-Score-Truth-Engine.md`** — how 3.0 finally closes the Bug-A "closed bets lose live scoring" defect. Evidence bundles, trust tiers, weighted settlement decisions, contradiction handling, ambiguity scoring, stale-live recovery.
4. **`TTLElite-Series-3.0-Scraper-And-Data-Ingestion-Spec.md`** — the 11 data sources, the `FeedClient` contract, the new tables (`odds_snapshot`, `mirror_observation`, `stream_observation`, etc.), the ingestion bus evolution from in-process events to Redis Streams, and the identity canonicalisation layer.
5. **`TTLElite-Series-3.0-Stream-CV-Spec.md`** — the computer-vision scoreboard reader that provides score evidence independent of any market. Three-tier recognition (template → classic CV → VLM), cost governor, worker lifecycle, ops console surfaces.
6. **`TTLElite-Series-3.0-Prediction-Engine-Spec.md`** — the new prediction stack: Glicko2 + TrueSkill-2 + Weng-Lin raters, LightGBM blender (Variant A no-market, Variant B with-market), Markov point-by-point simulator, Platt + isotonic + conformal calibration, Shin/Power/multiplicative devigging, fractional Kelly staking with portfolio caps.
7. **`TTLElite-Series-3.0-Implementation-Checklist.md`** — the flat, atomic task list you can walk top-to-bottom.
8. **`TTLElite-Series-3.0-Release-Gate-Checklist.md`** — the operator-facing gates for each phase transition, with pass criteria, how-to-run recipes, and the release ticket template.
9. **`phases/phase-00-foundations.md` … `phases/phase-08-tightening-retirement.md`** — one file per phase with purpose, entry/exit criteria, deliverables, risks, rollback, and operator runbook.
10. **`phase-00.md`**, **`phase-01.md`**, **`phase-02.md`**, and **`phase-03.md`** — shipped closeouts that record what actually landed, what was verified, and what remains before the next phase is fully signed off.

## Phase map at a glance
| Phase | Name | Blast radius | Reversibility | Primary focus |
|---|---|---|---|---|
| 00 | Foundations & Scaffolding | zero | trivial | facades, flags, diff harness, metrics, v3 UI shell |
| 01 | Data & Identity | low | easy | unified FeedClient, new tables, player canonicaliser |
| 02 | Score Truth MVP + Stream-CV Ingress | medium | easy | evidence bundles in shadow, first CV templates |
| 03 | Score Truth Advisory + Scraper Completeness | medium | easy | HoldOpen/ManualReview live, more feeds, CV Tier B/C |
| 04 | Bus + VLM + Raw Store + Blender A | med-high | flag-off | Redis Streams, MinIO, CostGovernor, Variant A |
| 05 | Prediction Core + Markov | medium | flag-off | TS2 + WL raters, Markov sim, conformal, devigging |
| 06 | Staking v3 + Settlement Promotion | HIGH | flag-off | Kelly v3 caps, SettlementEngine primary, Stream-CV required |
| 07 | UI Cutover | high | flag-off | v3 becomes canonical, retire 2.0 UI |
| 08 | Tightening, Retirement, v3.1 Prep | low | git revert | decompose PaperTradingService, delete dead code |

## Cross-reference index
- **Bug A (closed bets lose live scoring)** → Master Plan §1, Score Truth Engine §6, Stream-CV Spec §0, Phase 02/03/06.
- **UI redesign** → UI Redesign Spec, Phase 00/07.
- **Scraper completeness** → Scraper & Data Ingestion Spec, Phase 01/02/03/04.
- **Prediction upgrade** → Prediction Engine Spec, Phase 04/05.
- **Staking v3 caps** → Prediction Engine Spec §9 + §11, Phase 06.
- **Operator safety** → Release Gate Checklist, each phase's rollback section, Master Plan §10.

## Conventions
- All dates are UTC unless otherwise stated.
- "Paper bankroll" is expressed in units, where 1 unit = 1 % of the configured bankroll.
- All specs use American spelling for code identifiers and British spelling ("behaviour", "canonicalise") in prose; both are acceptable in comments.
- Every new service ships with Micrometer metrics, a Grafana dashboard, an on-call runbook entry, and a feature flag with a 90-day expiry.

## How this folder evolves
- Each phase closes with a **post-mortem-lite** appended to the relevant `phases/phase-XX-*.md` file under a `## Post-mortem (appended after phase close)` heading.
- New specs (v3.1 seeds) go into `docs/ttlelite-series-3.1/` once v3.0 is complete; this folder stays frozen as the historical record.
- Model cards are committed under `models/prediction/<version>/model_card.md` and **not** duplicated here.
- Compliance files for Stream-CV platforms live under `cv-assets/platforms/<name>/COMPLIANCE.md`.

## Owners
- **Product + release owner**: Alex.
- **Engineering on-call rotation**: ops docs in `infra/oncall.md`.
- **Model promotion approver**: operator after reviewing the model card + walk-forward gates.

## Contact & escalation
- Alerts and kill-switches: see Release Gate Checklist §12.
- If a gate is ambiguous, treat it as failing until clarified. Err on the side of caution — the whole 3.0 plan is built around "better to hold open than to settle wrong."
