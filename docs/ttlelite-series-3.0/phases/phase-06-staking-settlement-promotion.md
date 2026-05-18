# Phase 06 — Staking v3 + Settlement Promotion
_Target duration: 2–3 weeks · Blast radius: HIGH (first phase that changes user-visible behaviour) · Reversibility: flag-off_

## Purpose
This is the watershed. `StakingPolicy` v3 goes live with fractional Kelly + portfolio caps + correlation caps + drawdown stop. The `SettlementEngine` gets promoted from advisory to **primary** — it closes bets, not the 2.0 path. Stream-CV becomes a **required** input for any match where the market closes before the match ends. Bug A is closed.

## Entry criteria
- Phase 05 soak passed with blender Variant A stable.
- Score Truth Engine advisory has been green for ≥ 14 days with override rate ≤ 0.2 %.
- Operator has rehearsed the kill-switch + rollback at least twice on staging.
- Release ticket for this phase is drafted with an explicit "who pages whom" escalation matrix.

## Deliverables
1. `StakingPolicy` v3 live per Prediction Engine Spec §9 and §11.
2. `policy.yaml` loader with hot-reload and audit; kill-switch endpoints for staking + settlement.
3. `SettlementEngine` becomes the primary settlement path; 2.0 `PaperTradingService.settleBet*` is wired as a cold-standby only reachable by feature flag.
4. Stream-CV becomes required for matches where `market_closed_before_end == true`; absence triggers `SCORE_BACKED_ONLY` policy.
5. Session ribbon + command palette in v3 UI.
6. Alerts: `ExposureCapBreach`, `StakingPolicyHotReloadFailed`, `CLVNegative7Day`.

## Work breakdown
- Promotion is a two-step toggle: first flip `features.score-truth=primary` on staging for 7 days, observe, then flip prod. Same for `features.stake-policy-v3`.
- Staking audit writes to `staking_audit` with per-bet caps applied, caps breached (should be zero), and Kelly raw vs. Kelly used.
- The kill-switch endpoints require operator identity + reason; every flip is logged immutably.
- The session ribbon shows live P&L, CLV, exposure utilisation, drawdown state, and the current staking factor.

## Exit criteria
- Release Gate Checklist §8 fully ticked.
- 14-day primary soak with zero Bug-A-style contradictions reaching users.
- Zero exposure cap violations.
- CLV non-inferior to 2.0 baseline (p < 0.05 via paired bootstrap).
- Rollback exercised once in staging during the soak.

## Risks
- **Staking caps incorrectly computed cause surprise "no bets" days.** Mitigation: `/v3/ops/staking` shows the caps applied per bet in real time; operator can loosen portfolio cap via hot-reload without a deploy.
- **SettlementEngine produces a hang on an edge case unseen in shadow.** Mitigation: `HoldOpen` + TTL + operator visibility; settle-freeze kill-switch ready.
- **Stream-CV unavailability forces too many bets into `SCORE_BACKED_ONLY`.** Mitigation: operator dashboard with coverage %; temporary threshold relaxation via policy.yaml.

## Rollback
- `features.score-truth=advisory` — demotes the engine back to advisory; 2.0 path reclaims settlement.
- `features.stake-policy-v3=off` — reverts to 2.0 staking.
- Kill-switches are available regardless of flag state.

## Operator runbook
- `./scripts/deploy-phase-06.sh staging`
- `./scripts/promote-settlement-engine.sh --window 7d` — guided promotion with checks.
- `./scripts/staking-sim.sh --days 30` — replays the last 30 days with v3 staking to show expected exposure profile.
- `./scripts/rollback-phase-06.sh staging` — demotes settlement and staking together.
