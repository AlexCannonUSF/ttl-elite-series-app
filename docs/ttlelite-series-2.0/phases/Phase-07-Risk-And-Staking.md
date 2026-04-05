# Phase 07: Risk And Staking

## Status

Planned

## Why This Phase Exists

A good model still needs good portfolio behavior. This phase shifts the app from raw-edge chasing toward cleaner risk-adjusted decision sizing.

## Current State

Foundation already landed:

- stake logic and paper-trading policy already exist in `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`

Needs hardening:

- strategy labels are broader than the actual policy intent
- stake rationale is not yet fully inspectable in the UI
- exposure controls need to be more explicit

## Scope

- risk-adjusted EV
- clearer strategy policy sets
- concentration and exposure controls
- stake rationale visibility

## Dependencies

- Phase 06 pricing discipline
- Phase 03 Live Studio UX for explainability

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`

## Concrete Work

- [ ] Reframe strategy modes into explicit policy sets.
- [ ] Use uncertainty, calibration, and data quality alongside edge for stake sizing.
- [ ] Add exposure caps by bankroll, player, and trigger family.
- [ ] Add stronger longshot and stale-data penalties.
- [ ] Expose stake rationale in product terms.

## Validation

- similar edges can produce different stakes for understandable reasons
- overexposure is visible before it becomes a session problem

## Done Definition

This phase is done when stake sizes and selection discipline are explainable and safer without becoming unnecessarily timid.
