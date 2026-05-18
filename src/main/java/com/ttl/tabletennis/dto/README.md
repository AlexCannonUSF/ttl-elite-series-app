# DTO Package

This package contains contracts returned by controllers and shared between backend and frontend.

## Main DTO families

- live studio:
  - `PaperTradeBetDto`
  - `PaperTradingSessionDto`
  - `PaperTradingSyncResultDto`
  - `TrackedMatchObservationDto`
  - `LiveStudioIntegrityDto`
  - `CompletedMatchLogDto`
- analytics/modeling:
  - `MatchupAnalysisDto`
  - `MatchupFeatureVectorDto`
  - `ModelRegistryEntryDto`
  - `ModelTrainingReportDto`
  - `AdaptiveRegimeProfileDto`
  - `ValueOpportunityDto`
- player/rating/admin:
  - `PlayerDto`
  - `PlayerAliasDto`
  - `PlayerStatisticsDto`
  - `RatingSnapshotDto`

## Rule

These are API contracts. Rename or reshape them deliberately because the frontend mirrors many of them in `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/types/api.ts`.
