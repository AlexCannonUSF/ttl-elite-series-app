# Phase 08: Session Learning And Telemetry

## Status

Planned

## Why This Phase Exists

The app should learn from real runs, but the user should still be able to review a single session cleanly. This phase formalizes that split.

## Current State

Foundation already landed:

- paper-trade sessions, bets, learning samples, and tracked observations already exist in the backend
- the live page already exposes some current-session telemetry

Needs hardening:

- considered-but-not-bet opportunities are not yet treated as first-class learning data
- session and long-run learning are still not uniformly separated across the product

## Scope

- persist considered opportunities
- persist score observation timelines through settlement
- persist settlement provenance and confidence
- keep session-only reporting separate from inherited learning state

## Dependencies

- Phases 00 and 01
- Phase 07 if stake outcomes are to be analyzed cleanly

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`

## Concrete Work

- [ ] Persist considered opportunities, not just placed bets.
- [ ] Persist score timelines for all tracked open bets.
- [ ] Persist settlement provenance and source confidence.
- [ ] Separate session-only and learned-global DTO/reporting windows.
- [ ] Add adaptive-learning audit trails.
- [ ] Add per-trigger and per-regime performance ledgers.

## Validation

- one session can be audited without confusion
- global learned behavior can be inspected without contaminating the session view

## Done Definition

This phase is done when the app can answer both “what happened in this run?” and “what did the system learn overall?” without mixing the two.
