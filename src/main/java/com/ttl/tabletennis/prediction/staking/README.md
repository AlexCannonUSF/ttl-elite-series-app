# Prediction Staking (Phase 06)

`StakingPolicy` converts the selected `Edge` side into either a stake in
units or an explicit `NO_BET` decision.

Current defaults match Prediction Engine Spec §9:

- Quarter Kelly: `fractionalKelly = 0.25`.
- Per-bet Kelly cap: `1.5` units.
- Portfolio cap: `5.0` open units.
- Per-event cap: `2.0` units.
- Per-player daily correlation cap: `1.5` units.
- Drawdown stop: if the rolling 50-bet ROI is below `-8%`, stake factor
  halves to `0.50`.
- Minimum executable stake: `0.1` units.

The policy is pure and audit-friendly: every cap or stop applied is returned
as a reason code on `StakingDecision`.

## Hot-reload + kill-switch (Phase 06 item 2)

`StakingPolicyCatalog` reads `policy.yaml#prediction.staking` on startup and
on a scheduled tick (`ttl.staking.policy.reloadFixedDelayMs`, 30 s default).
Every load, reload, default-fallback, and reload-failure is persisted to
`settlement_policy_audit` with `policy_name = "staking-v3"`, including the
before/after config diff in JSON. Operators force a reload via
`POST /api/v3/ops/staking/policy/reload`.

`StakingKillSwitch` is an in-process volatile flag. When active,
`StakingPolicy.decide(...)` short-circuits to `NO_BET` with the
`KILL_SWITCH_ACTIVE` reason code. Toggle via:

- `POST /api/v3/ops/staking/kill-switch/on`  body `{"triggeredBy": "...", "reason": "..."}`
- `POST /api/v3/ops/staking/kill-switch/off`
- `GET  /api/v3/ops/staking/kill-switch` — current status
- `GET  /api/v3/ops/staking/policy` — current snapshot

Activations and deactivations are themselves audited via the catalog with
statuses `KILL_SWITCH_ON` / `KILL_SWITCH_OFF`.
