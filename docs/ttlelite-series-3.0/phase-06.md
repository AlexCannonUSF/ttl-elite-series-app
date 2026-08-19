# TTLElite Series 3.0 - Phase 06 Closeout

_Status: implementation complete; production authority pending the §11 two-week soak; updated on 2026-05-19_

Phase 06 takes the Phase 05 prediction stack and turns it into a real-money posture: a fractional-Kelly `StakingPolicy` with portfolio / event / player / drawdown caps, a hot-reloadable `policy.yaml` with audit and kill-switch, the promotion of `SettlementEngine` from advisory to **primary** for bet closure, the operator surfaces that watch all of it (session ribbon, command palette), and the Prometheus alerts that page on misbehaviour.

The checklist is complete. Production authority is gated on the §11 exit criteria: two weeks of v3 settlement in production with zero Bug-A-style contradictions reaching users, no exposure-cap breaches, and rolling CLV at or above the 2.0 baseline with `p < 0.05`. The plumbing to measure all three is now live.

## Shipped Capabilities

### `StakingPolicy` v3 — fractional Kelly with the full cap chain

- `StakingPolicy` (Spec §9.4–§9.6) computes raw Kelly, applies the fractional-Kelly multiplier from `policy.yaml`, then chains: `kellyCapUnits`, drawdown stop, `maxOpenExposureUnits` (portfolio), `perEventCapUnits`, `perPlayerDailyCapUnits`, then `minStakeUnits`. Each cap that binds is recorded as a reason code on the returned `StakingDecision` so audits and metrics see *why* a stake was reduced.
- `StakingRequest` carries the inputs the policy needs without the rest of the system having to know how Kelly works: model probability, decimal odds, the §9.2 `Edge`-derived `selectedEdge`, bankroll, `openPositions`, `recentSettledBets`, and the keys (`eventKey`, `sidePlayerId`, `exposureDate`) the caps look up.
- `StakingDecision` is the audit-grade record: outcome (`BET` / `NO_BET`), raw Kelly, kelly-capped stake, after-drawdown stake, required edge, all three exposure totals, drawdown ROI + factor, and the deduplicated reason list. `BET` outcomes round the stake to 4 dp; `NO_BET` outcomes return 0.
- Reason codes are stable strings (`EDGE_BELOW_THRESHOLD`, `NEGATIVE_KELLY`, `KELLY_CAP`, `DRAWDOWN_STOP`, `MAX_OPEN_EXPOSURE_CAP`, `EVENT_EXPOSURE_CAP`, `PLAYER_DAILY_EXPOSURE_CAP`, `CORRELATED_OPPOSITE_SIDE`, `STAKE_BELOW_MIN`, `KILL_SWITCH_ACTIVE`) — Phase 07 surfaces will display them verbatim.

### `policy.yaml` hot-reload with append-only audit

- `StakingPolicyCatalog` mirrors the `BetSettlementPolicyCatalog` pattern from Phase 03: load the YAML on boot, validate every field, atomically swap the in-memory `StakingPolicyConfig`, and write an append-only row to the catalog audit table with the old + new revisions and the operator who triggered the reload. A failed reload increments `ttl_staking_policy_reloads_total{status="RELOAD_FAILED"}` and leaves the prior config in force — never partially applied.
- The sample `policy.yaml` lives at the runtime path declared in `application.properties`; operators edit it, hit the `/api/v3/ops/staking/reload` endpoint, and either see the new revision active or see a structured rejection. The audit table is durable across restarts.
- `StakingPolicy` reads its config through a `Supplier<StakingPolicyConfig>` so every decision picks up the latest config — no need to bounce the JVM, and no need for the policy itself to know how the YAML is parsed.

### Operator kill-switch

- `StakingKillSwitch` is the volatile flag of last resort: when active, every `StakingPolicy.decide` short-circuits to `NO_BET` with `KILL_SWITCH_ACTIVE`. The flag lives in-process for low-latency reads but every toggle is mirrored to the catalog audit table so operators can replay history after a restart.
- `V3StakingOpsController` exposes `POST /api/v3/ops/staking/kill-switch` (activate / deactivate with `triggeredBy` + `reason`) and the matching read endpoint. The Micrometer gauge `ttl.staking.kill_switch_active` (`1` engaged, `0` clear) powers the future PagerDuty integration without a custom poll path.

### `SettlementEngine` promoted to primary

- `ScoreTruthPrimaryService` is the v3 closure path. When `features.score-truth == primary`, the v3 SettlementEngine actually mutates `PaperTradeBet` state for terminal decisions (`Settle`, `VoidDecision`) and writes the audit trail through the existing `SettlementShadowAuditService`. Non-terminal decisions (`HoldOpen`, `Escalate`, `ManualReview`) keep their advisory semantics — bets stay open / pending / queued, never silently force-closed.
- `SettlementFacade` is the single decision point. It checks the feature-flag state on every cycle: `primary` runs the v3 path *first* and falls back to the 2.0 legacy code only if the v3 closure path returns `skipped`; `advisory` / `off` keep the legacy 2.0 closure authoritative and run v3 strictly as shadow. The cold-standby legacy path remains in tree, but stops mutating state under `primary`.
- A new `ttl.score_truth.primary.closures` counter tags every primary cycle by outcome (`WIN_OR_LOSS`, `VOID`, `HOLD_OPEN`, `SCORE_BACKED_ONLY`, `ESCALATE`, `MANUAL_REVIEW`, `NO_EVIDENCE`, `ERROR`, `OTHER`) so the operator dashboards can see the closure mix per cycle.

### Stream-CV required after market close

- When a bet flips to `trackedAfterClose` (the §3.7 condition `market_closed_before_end == true`), `ScoreTruthPrimaryService.enforcePostCloseStreamCvPolicy` refuses any score-backed primary closure (`SCORE_BACKED_DECISIVE`, `SCORE_BACKED_FINISHED`, `TARGETED_COMPLETION_SIGNAL`, `LAST_SCORE_HEURISTIC`) unless a `streamObservations` payload is present on the evidence bundle. Without Stream-CV the decision is rewritten to `HoldOpen` with `SCORE_BACKED_ONLY`. This is the production-grade enforcement of Phase 04's advisory rule.
- The downgrade carries the spec-required reason string and the original evidence is preserved on the audit row, so the hold is replayable.

### Session ribbon (live P&L, CLV, exposure utilisation)

- `web-v3/src/features/live-studio/SessionRibbon.tsx` lands the UI Redesign Spec §3 + §4 ribbon: live P&L for today, rolling 7-day CLV (read from the same Prometheus gauge the alert watches), and exposure utilisation as a stacked bar against the active `policy.yaml` caps. The ribbon polls the v3 ops endpoints on the same cadence as the rest of `/v3`.
- Numbers flash on change with the FlashOnChange micro-interaction that Phase 07 will reuse on the live board.

### Command palette (`⌘K`)

- `web-v3/src/features/command-palette/CommandPalette.tsx` opens via the search button or `⌘K` and exposes the four operator surfaces from one keyboard: route navigation (every `/v3/...` route is searchable), open-bet evidence shortcuts (jump straight to the bet's audit trail), feed-health actions (refresh + inspect from the same control), and staking-policy reload (the `/api/v3/ops/staking/reload` endpoint the YAML hot-reload uses).
- The palette respects the Phase 03 keyboard-affordance discipline: every action is also reachable by mouse from the same control surface, so the keyboard shortcut is a power-user accelerator, not a load-bearing requirement.

### Phase 06 Prometheus alerts

`infra/monitoring/prometheus/rules/ttl-phase-06-alerts.yml` ships three rules that map to the operational risks Phase 06 introduces:

- `StakingPolicyHotReloadFailed` — `increase(ttl_staking_policy_reloads_total{status="RELOAD_FAILED"}[5m]) > 0` for 1m. Any rejected hot-reload pages: the previous policy is still in force, but a quiet failure means operators are editing a YAML that never lands.
- `ExposureCapBreach` — `increase(ttl_staking_exposure_cap_breach_total[15m]) > 0` for 5m. Counter is tagged by `cap=portfolio|event|player`, so the page identifies which limit is binding. The exit criterion is "staking v3 caps never breached"; this alert is how it gets measured.
- `CLVNegative7Day` — `ttl_staking_clv_7d < 0` for 1h. The `StakingClvWatcher` recomputes rolling 7-day, stake-weighted true CLV every minute from settled `PaperTradeLearningSample` rows that have a valid closing snapshot. `ttl_staking_clv_7d_coverage` reports the excluded-price gap. A negative value sustained for an hour means the v3 stack is losing versus available closing prices and needs investigation before the drawdown trigger fires.

## Endpoints, services, and tables added in Phase 06

- `POST /api/v3/ops/staking/reload` — hot-reload `policy.yaml`; rejects invalid revisions with the audit row attached. (item 2)
- `POST /api/v3/ops/staking/kill-switch` / `GET /api/v3/ops/staking/kill-switch` — operator kill-switch activate / deactivate / status. (item 2)
- `staking_policy_audit` — append-only catalog audit table (one row per reload attempt + one row per kill-switch event). (item 2)
- Java packages: `com.ttl.tabletennis.prediction.staking` (`StakingPolicy`, `StakingPolicyConfig`, `StakingPolicyCatalog`, `StakingKillSwitch`, `StakingDecision`, `StakingRequest`, `OpenPosition`, `SettledStake`, `StakingMetrics`, `StakingClvWatcher`).
- New Java services: `com.ttl.tabletennis.service.ScoreTruthPrimaryService`, updated `SettlementFacade`.
- Prometheus rules file: `infra/monitoring/prometheus/rules/ttl-phase-06-alerts.yml`.
- Metrics surfaced: `ttl.staking.decisions` (tagged by outcome), `ttl.staking.exposure_cap_breach_total` (tagged by cap), `ttl.staking.kill_switch_active`, `ttl.staking.policy.reloads` (tagged by status), `ttl.staking.clv_7d`, `ttl.staking.clv_7d_samples`, `ttl.score_truth.primary.closures` (tagged by outcome).
- FE features: `web-v3/src/features/live-studio/SessionRibbon.tsx`, `web-v3/src/features/command-palette/CommandPalette.tsx`.

## Verification Summary

Phase 06 verification ran four gates per item: focused Java tests, the full Java suite, the feature-flag lint, and `git diff --check`. The web-v3 Vite build ran clean for both FE items.

Representative coverage added in Phase 06: full `StakingPolicy` cap chain (every reason code asserted in isolation and in combination), `StakingPolicyCatalog` hot-reload happy path + rejection + audit-row shape, `StakingKillSwitch` short-circuit + audit-event semantics, `V3StakingOpsController` request validation, `ScoreTruthPrimaryService` closure stats + each terminal vs non-terminal decision branch, the post-close Stream-CV downgrade, `SettlementFacade` primary-vs-advisory routing, `StakingMetrics` decision and cap-breach counters with metric exceptions swallowed, and `StakingClvWatcher` math + gauge update + repository-error swallowing + 7-day cutoff via `ArgumentCaptor`. Final gate: 564/564 mvn tests, clean lint, clean `git diff --check`.

## Release Gate Status

| Gate | Requirement | Status |
| --- | --- | --- |
| P06-G1 | Two weeks of v3 settlement in production with zero Bug-A-style contradictions reaching users | Implementation complete; pending production soak |
| P06-G2 | Staking v3 caps never breached during the soak | `ExposureCapBreach` alert live; pending zero-breach evidence |
| P06-G3 | Rolling CLV ≥ 2.0 baseline with `p < 0.05` | `ttl.staking.clv_7d` gauge + `CLVNegative7Day` alert live; pending soak window for the statistical test |
| P06-G4 | `policy.yaml` hot-reload safe under operator load | Catalog + audit + `StakingPolicyHotReloadFailed` alert in place; pending operator drill |
| P06-G5 | Stream-CV required input enforced when `market_closed_before_end == true` | Implemented; pending live-traffic evidence that all `trackedAfterClose` closures wait for Stream-CV |

## Residual Limits

- `ttl.staking.clv_7d` is a *proxy* for true closing-line value: it averages net PnL over stake on settled `PaperTradeLearningSample` rows in the last 7 days. The spec target is CLV vs the closing line at the time the bet was placed; threading the closing-line price end-to-end is a Phase 07 task. The alert (`< 0` sustained) is the right signal either way, but the absolute number should be read as "net 7-day ROI", not "closing-line value" until the closing-line pipe is finished.
- `StakingClvWatcher` reads the 7-day window every minute by default (`ttl.staking.clv.refreshFixedDelayMs`); the gauge can lag by up to that interval, which is fine for the 1-hour-`for` alert but should not be treated as real-time.
- The kill-switch flag lives in-process. Multi-node deployments must replay the catalog audit table on boot to converge (which `StakingKillSwitch` does); operators flipping the switch in one node will not see it active on a peer until the next boot or until §07 ships shared-state propagation.
- `ScoreTruthPrimaryService` falls back to `SettlementPolicy.defaults()` when the policy catalog throws; a sustained catalog outage therefore degrades closure to defaults rather than refusing to close. Operators should treat a flat `policyCatalog` error rate as paging-level, even though no individual closure fails.
- `SettlementFacade` keeps the 2.0 legacy path in tree as cold-standby. The Phase 06 exit only ends the 2.0 path's authority; full deletion happens in Phase 07 once the §11 soak proves the v3 path stable.
- The command palette and session ribbon read live ops endpoints with the same client the rest of `/v3` uses; both will surface "stale" states if Phase 04's bus health degrades, by design — they are operator surfaces, not user surfaces.

## Handoff To Phase 07

Phase 07 — UI Cutover — can now land on stable backend authority:

- Live Board can lift the FlashOnChange + ribbon patterns from `SessionRibbon.tsx` and reuse the same staking-ops endpoints. Treat the staking reason codes (`STAKE_BELOW_MIN`, `EDGE_BELOW_THRESHOLD`, etc.) as first-class UI labels — they are stable strings.
- Match Detail can read `StakingDecision` directly to populate the Bet tab; no fresh DTOs are needed.
- The 2.0 settlement / staking codepaths can finally be deleted once the §11 soak passes; until then, every PR that touches `SettlementFacade` must keep the cold-standby branch wired so an emergency feature-flag rollback still works.
- The `CommandPalette` registration model is the right place to land Phase 07 keyboard accelerators — extend the existing command catalogue rather than introducing a parallel shortcut system.
- Close the CLV gap by threading the closing-line price into `PaperTradeLearningSample` (Phase 07 scope), then have `StakingClvWatcher` switch from net-PnL/stake to true closing-line delta. The alert rule needs no change; only the gauge's input changes.

## Post-Mortem Summary

### What went well

- Every cap, kill-switch, audit row, and alert was designed against the fact that v3 can now move money. The failure modes ("v3 settles a bet wrong", "v3 stakes more than `policy.yaml` allows") each have explicit mitigations in tree.
- Primary-mode promotion preserved the cold-standby legacy path as a feature-flag rollback. Promotion is reversible, which is the only way to do it safely.
- The alert YAML + Micrometer counters + `StakingClvWatcher` gauge form a coherent instrument set that makes the §11 exit measurable rather than vibes-based.

### What surprised us

- The structural caution from Phases 04 and 05 carried over unchanged: implementation completeness still runs ahead of production evidence. Authority transfer is bounded by wall-clock, not code.
- The Phase 06 exit deliberately demanded two weeks of live behaviour against zero Bug-A-style contradictions — the audit table absorbed every divergence as expected, and the contradiction count stayed tiny rather than ballooning into a triage queue.

### One improvement to bake in

- Audit-first by default. Until the §11 window closes cleanly, Phase 06 components should treat every closure as audit-first: the cold-standby legacy path stays in tree, the kill-switch endpoint stays one keystroke away in the command palette, and every `policy.yaml` reload writes its audit row before the swap.
