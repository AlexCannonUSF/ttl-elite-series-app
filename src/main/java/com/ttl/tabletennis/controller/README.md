# Controller Package

This package is the backend HTTP surface.

## What lives here

- `LiveStudioController`
  - current live product API
- `V3OpsController`
  - V3 operations read surfaces such as feed health
- `AnalyticsController`
  - matchup, model, and value analytics API
- `AdminController`
  - maintenance, aliases, ratings, model training, and manual controls
- `ScrapeController`
  - scraper runs, errors, metrics, and dry-run tooling
- `PlayerController`
  - player list/search/create
- `MatchController`
  - recent matches and head-to-head lookups
- `StatisticsController`
  - derived player/head-to-head stats

## Rule

Controllers should stay thin:

1. accept request params/payloads
2. call the owning service
3. return DTOs or simple responses

If controller code starts owning workflow logic, move it into `service`.
