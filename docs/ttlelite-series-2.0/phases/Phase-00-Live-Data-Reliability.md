# Phase 00: Live Data Reliability

## Status

Planned

## Why This Phase Exists

TTLElite 2.0 fails if score tracking is still effectively tied to visible sportsbook rows. This phase separates `priceable market data` from `tracked score continuity` and hardens the upstream ingestion contract.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
  - `fetch()`
  - `fetchScoreboard()`
  - `fetchScoreboardByEventIds(...)`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`
  - `liveScoreSnapshots(...)`
  - `liveScoreSnapshotsForEventIds(...)`

Needs hardening:

- current public-tree parsing is still brittle against `events: { count: N }`
- source confidence and source health are still too implicit
- scoreboard continuity after market hide/closure needs stronger proof and diagnostics

## Scope

- keep GraphQL events as the primary odds source
- keep targeted scoreboard polling by event id as the key score-continuity mechanism
- rework public-tree parsing into a discovery/health/secondary-evidence role
- make source provenance and confidence visible to downstream services

## Dependencies

- stable event identity normalization
- parser fixtures for current upstream shapes

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/scrape/HardRockOddsScraperTests.java`

## Concrete Work

- [ ] Audit GraphQL scoreboard-by-event-id behavior across open, suspended, hidden, and resulted event states.
- [ ] Rework public-tree scoreboard parsing to tolerate `events.count` shapes without silently returning empty.
- [ ] Add explicit diagnostics for parser-shape failures versus network failures.
- [ ] Expose source type, source confidence, and last observation age in live score snapshots.
- [ ] Parse and retain richer `matchState` evidence: set score, point score, per-game scores, `matchCompleted`, `sourceFeedCode`, and `sourceFeedEventId`.
- [ ] Add test fixtures for current GraphQL and public-tree response shapes.

## Validation

- targeted polling can still return score snapshots when priceable rows are gone
- parser tests fail loudly if upstream shape drifts
- source ranking is visible in DTOs, not hidden in service logic

## Done Definition

This phase is done when tracked matches can continue receiving score observations after the sportsbook stops pricing them, and the app can explain which upstream source produced each score update.
