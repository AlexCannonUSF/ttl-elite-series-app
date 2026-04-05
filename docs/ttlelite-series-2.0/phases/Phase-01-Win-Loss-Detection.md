# Phase 01: Win/Loss Detection 2.0

## Status

Planned

## Why This Phase Exists

The app needs to settle bets from evidence, not disappearance. This phase turns settlement into a lifecycle-driven, timeline-backed system.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/PaperTradeBet.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/TrackedMatchObservation.java`

Existing scaffolding includes:

- tracked observations
- `externalEventId` on bets
- `settlementReason`
- `settlementSource`

Needs hardening:

- lifecycle state is not yet a fully explicit persisted state machine
- heuristics still carry too much responsibility
- replay coverage is not yet broad enough for the historic blind spots

## Scope

- add an explicit lifecycle state model
- make settlement consume observation timelines first
- formalize source ranking and fallback policy
- keep voiding rare, deliberate, and explainable

## Dependencies

- Phase 00 score-continuity reliability
- stable event identity and source metadata

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/PaperTradeBet.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/service/PaperTradingServiceTests.java`

## Concrete Work

- [ ] Add explicit lifecycle states for tracked matches and/or tracked bets.
- [ ] Move settlement policy to timeline-first reasoning.
- [ ] Replace free-form settlement drift with a stable reason-code set.
- [ ] Add clearer distinction between:
  - no board row
  - no score update yet
  - likely finished but unconfirmed
  - authoritative result
  - timeout void
- [ ] Build replay tests for real failure patterns:
  - row disappears at `2-0`
  - row disappears at `2-2`
  - hidden/suspended during late set
  - duplicate-name collisions
  - DB confirmation arriving after live disappearance
- [ ] Add settlement audit helpers for UI and diagnostics.

## Validation

- every settled bet has a source and reason code
- replay fixtures produce stable expected outcomes
- void rate caused by lost visibility drops materially

## Done Definition

This phase is done when every win, loss, push, and void is inspectable, reason-coded, and backed by either tracked score evidence, official confirmation, DB reconciliation, or an explicitly labeled fallback.
