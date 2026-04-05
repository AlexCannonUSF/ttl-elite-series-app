# Phase 10: Operations And Admin

## Status

Planned

## Why This Phase Exists

When something breaks, the team should be able to see where, replay it, and understand why quickly. This phase raises operational trust.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/AdminPage.tsx`
- scrape/run history and operational controls already exist

Needs hardening:

- source health is not yet a clear first-class concept
- parser drift and score-continuity failures are not yet easy to diagnose in-product

## Scope

- source health monitoring
- run-stage observability
- replay tooling
- parser drift diagnostics
- score continuity failure diagnostics

## Dependencies

- source and lifecycle metadata from Phases 00 and 01

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/AdminPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/`

## Concrete Work

- [ ] Add source health panels for live odds, tracked score, official results, and DB reconciliation.
- [ ] Add run-stage visibility: fetch, parse, identity resolve, persist, enrich, settle.
- [ ] Add payload replay and parser-regression tooling.
- [ ] Add score-continuity failure diagnostics.
- [ ] Add better admin filtering and explainability around failures.

## Validation

- a broken event can be replayed and diagnosed
- source degradation is obvious from the admin surface

## Done Definition

This phase is done when Operations can answer “what broke and why?” without reading raw logs first.
