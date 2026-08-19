# Domain Package

This package contains the long-lived persisted entities.

## Main entity groups

- historical match graph:
  - `Match`
  - `Player`
  - `PlayerAlias`
  - `RatingSnapshot`
- live trading:
  - `PaperTradeSession`
  - `PaperTradeBet`
  - `PaperTradeSessionShadow`
  - `PaperTradeBetShadow`
  - `SettlementDiffLog`
  - `TrackedMatchObservation`
  - `PaperTradeDecisionSample`
  - `PaperTradeLearningSample`
- model/value persistence:
  - `PredictionModelRegistryEntry`
  - `ValueOpportunity`
  - `OddsQuote`
- scrape telemetry:
  - `ScrapeRun`
  - `ScrapeError`

## Most important relationships

- `Match` links players and result history
- `PlayerAlias` links sportsbook/source names back to canonical `Player`
- `PaperTradeBet` is the main live settlement record
- `PaperTradeBet.STATUS_PENDING_EVIDENCE` parks `HoldOpen` score-truth bets with TTL/poll metadata until evidence or operator review resolves them
- `PaperTradeBetShadow` and `PaperTradeSessionShadow` are 3.0 mirror tables for no-behavior-change shadow verification
- `SettlementDiffLog` is the 3.0 settlement shadow-diff audit trail
- `SettlementPolicyAuditRecord` records Score Truth policy loads, hot reloads, and rejected reload attempts
- `TrackedMatchObservation` is the timeline/evidence store for live tracking
