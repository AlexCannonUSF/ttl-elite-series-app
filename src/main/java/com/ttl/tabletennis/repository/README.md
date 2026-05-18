# Repository Package

This package owns Spring Data persistence access.

## Main repository groups

- historical data:
  - `MatchRepository`
  - `PlayerRepository`
  - `PlayerAliasRepository`
  - `RatingSnapshotRepository`
- live trading:
  - `PaperTradeSessionRepository`
  - `PaperTradeBetRepository`
  - `PaperTradeSessionShadowRepository`
  - `PaperTradeBetShadowRepository`
  - `SettlementDiffLogRepository`
  - `TrackedMatchObservationRepository`
  - `PaperTradeDecisionSampleRepository`
  - `PaperTradeLearningSampleRepository`
- model/value outputs:
  - `PredictionModelRegistryRepository`
  - `ValueOpportunityRepository`
  - `OddsQuoteRepository`
- scrape telemetry:
  - `ScrapeRunRepository`
  - `ScrapeErrorRepository`

## Rule

Keep query logic here. Keep settlement, pricing, and workflow decisions in `service`.
