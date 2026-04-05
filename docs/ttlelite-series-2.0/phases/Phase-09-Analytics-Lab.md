# Phase 09: Analytics Lab

## Status

Planned

## Why This Phase Exists

Analytics should support model decisions and release confidence, not just expose a registry dump.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/AnalyticsPage.tsx`
- model registry and training/reporting flows exist in the backend

Needs hardening:

- analytics still leans too heavily on raw registry listings
- calibration and trigger attribution need stronger visualization and structure

## Scope

- calibration
- profitability
- trigger attribution
- challenger vs active model comparison
- drift and validation clarity

## Dependencies

- telemetry from earlier phases
- model discipline from Phase 06

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/AnalyticsPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`

## Concrete Work

- [ ] Add calibration curves and reliability views.
- [ ] Add ROI and win-rate splits by trigger and regime.
- [ ] Add expected vs realized edge views.
- [ ] Reframe registry details around active vs challenger models.
- [ ] Make training failures and readiness more readable.

## Validation

- the page helps answer whether the active model is trustworthy and what should be changed next

## Done Definition

This phase is done when Analytics Lab feels like a model-quality control room rather than a supporting diagnostics page.
