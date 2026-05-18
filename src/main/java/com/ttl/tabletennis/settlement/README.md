# Settlement Package

This package is the 3.0 score-truth landing zone.

It exists to pull settlement logic out of the giant live trading service and into a smaller, testable set of contracts and pure decision services.

## Current contents

- `SettlementEvidence`
  - immutable bundle of everything the score-truth engine can see for one bet
- `AmbiguityScorer`
  - deterministic same-pair / same-window ambiguity scoring before auto-settlement
- `ContradictionGuard`
  - detects winner disagreement, score regression, and phase inversion before auto-settlement
- `SettlementEvidenceBuilder`
  - maps the current bet + tracked observation timeline into the immutable score-truth bundle used by shadow evaluation
- `BetSettlementPolicyCatalog`
  - loads `bet_settlement_policy.yaml`, keeps the last good policy hot-reloaded, and writes reload/failure audit rows
- `IdentityLock`
  - placement-time identity anchor used to keep later evidence tied to the right match
- `TrackedEventId`
  - stable event identity wrapper for the evidence bundle
- `Contradiction` / `ContradictionKind`
  - disagreements surfaced before we auto-settle
- `CoverageState`
  - whether the bundle currently looks fully covered, partially covered, or dark
- `observation/*`
  - score-bearing observations from sportsbook, mirror, and Stream-CV sources
  - also includes T4 official/database observation adapters for contradiction analysis
- `recovery/StaleLiveRecoveryService`
  - Phase 03 loop for stale live-score bets; emits detection events, escalates targeted feeds, and re-runs score-truth advisory decisions

## Next planned work

- `SettlementEngine`
- persistence + API readers for score-truth evidence
