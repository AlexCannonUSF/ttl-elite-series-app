# Phase 03 — Score Truth Advisory + Scraper Completeness
_Target duration: 2 weeks · Blast radius: medium (operator-visible review queue) · Reversibility: easy_

## Purpose
Promote the Score Truth Engine from shadow to **advisory**: it can hold bets open pending evidence and mark decisions for manual review, but it cannot auto-close bets yet. Ship the stale-live recovery flow for the classic "closed bet, ghost-ambiguous outcome" case. Expand the scraper fleet (BetsAPI, AiScore, HR-TGT, ITTF-WTT) to make evidence bundles richer. Give Stream-CV its Tier B/C fallbacks and its operator surface.

## Entry criteria
- Phase 02 soak passed.
- Operator has reviewed the first 7 days of `settlement_diff_log` to familiarise themselves with contradictions and ambiguities.
- Review-queue UI reviewed by the operator.

## Deliverables
1. `SettlementEngine` promoted to advisory: `HoldOpen` and `ManualReview` decisions persist; settlement remains on 2.0 unless the operator intervenes.
2. `StaleLiveRecoveryService` implementing the flow in Score Truth Engine §6.2.
3. `BetSettlementPolicy` YAML loader with hot-reload + audit.
4. New feeds live: `BetsApiFeedClient` (T2), `AiScoreFeedClient` (T3), `ItftWttFeedClient` (T4 historical).
5. `HardRockTargetedPoller` (HR-TGT) + `HardRockTreeDiscovery` (HR-TREE) per Scraper Spec §3.3.
6. Stream-CV Tier B (classic CV) and Tier C (VLM) paths implemented; `stream_routes.yaml` live; `tools/cv-template-builder` ships.
7. `/v3/review` queue UI: accept / reject / comment on `ManualReview` decisions.
8. `/v3/ops/feeds/streams` panel.

## Work breakdown
- `HoldOpen` decisions persist the bet as `PENDING_EVIDENCE` with a TTL; a scheduled poller re-evaluates when new evidence arrives, and the settlement path closes the bet only after the operator confirms (Phase 04+ automates).
- The stale-live recovery flow retrieves post-match evidence from TT-Series, BetsAPI, and AiScore; if three independent T3+ sources agree, the decision is promoted to `Settle`.
- Stream-CV Tier B/C tested against the remaining four replay fixtures plus two synthetic "scoreboard occluded" scenarios; Tier C VLM budget logs verified.

## Exit criteria
- Release Gate Checklist §5 fully ticked.
- 14-day advisory soak with ≤ 0.2 % of operator overrides reversing the engine.
- Manual review queue p95 depth < 15.
- Staleness recovery exercised end-to-end with ≥ 10 recovered sessions and zero permanently stuck sessions.

## Risks
- **Operators missing reviews.** Mitigation: review queue SLA alerts (depth > 15, oldest > 2 h); pager integration.
- **Scraper TOS concerns from new feeds.** Mitigation: compliance check per source (Scraper Spec §2 checklist) before production.
- **VLM misread confirmed by OCR in collusion.** Mitigation: weighted voting gives PaddleOCR + EasyOCR higher combined weight than Gemini single-call.

## Rollback
- Demote `SettlementEngine` back to shadow (flag).
- Disable any single feed via `/api/admin/feeds/{id}/quarantine`.
- Disable VLM path via `features.stream-cv-vlm=off`.

## Operator runbook
- `./scripts/deploy-phase-03.sh staging`
- `./scripts/review-queue-report.sh` — shows queue age distribution and override rate.
- `./scripts/feed-add.sh <name>` — scaffolds a new feed adapter; includes the compliance template to fill in.
- `./scripts/cv-template-new.sh <templateId>` — opens the template builder.
- `./scripts/rollback-phase-03.sh staging`
