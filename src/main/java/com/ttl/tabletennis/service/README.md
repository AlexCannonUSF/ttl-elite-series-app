# Service Package

This is the highest-leverage package in the backend. Most business logic lives here.

## Core workflow owners

- `PaperTradingService`
  - live session, open bets, tracked observations, settlement, integrity
- `PaperTradingShadowService`
  - upserts the 3.0 shadow mirrors for sessions and bets with no behavior change
- `SettlementDiffLogService`
  - writes identity/shadow settlement comparison rows into `settlement_diff_log`
  - now also records score-truth contradiction blocks from the new settlement package
- `SettlementFacade`
  - 3.0 seam that delegates to the current legacy settlement cascade and records identity and score-truth shadow-diff rows with no outcome change
- `ScoreTruthAdvisoryService`
  - feature-flagged Phase 03 bridge that uses the active score-truth policy, persists manual-review advisories, and parks `HoldOpen` bets as `PENDING_EVIDENCE` with TTL polling while keeping 2.0 settlement authoritative
- `StaleLiveRecoveryService`
  - Phase 03 recovery loop for stale live-score bets; emits `stale.live.detected`, escalates targeted feeds from the active policy, and records score-truth advisory outcomes without mutating settlement
- `OddsValueEngineService`
  - live board rows, score snapshots, value opportunities
- `PredictionFacade`
  - 3.0 seam that delegates to the current prediction stack without changing outputs
- `PredictionModelService`
  - model training, prediction snapshots, adaptive regime tuning
- `FeatureService`
  - matchup feature vectors and support-depth metadata
- `PlayerIdentityService`
  - canonical player resolution and alias control

## Support workflow owners

- `TtSeriesEloSyncService`
  - pulls ranking page ratings
- `Glicko2RatingService`
  - rebuilds/tunes Glicko snapshots
- `MatchResultBackfillService`
  - structured result backfill
- `StatisticsService`
  - aggregate stats used by product and modeling
- `MatchupAnalysisService`
  - analysis DTOs for matchup surfaces

## Schedulers

- `OddsValueScheduler`
- `PaperTradingScheduler`
- `TtSeriesEloSyncScheduler`
- `Glicko2Scheduler`

## When debugging

- live/settlement bug: start with `SettlementFacade` and `PaperTradingService`
- bad recommendation: start with `OddsValueEngineService`
- model weirdness: start with `PredictionFacade`, `PredictionModelService`, and `FeatureService`
