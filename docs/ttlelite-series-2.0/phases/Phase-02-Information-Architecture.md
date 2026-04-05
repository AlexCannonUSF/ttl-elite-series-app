# Phase 02: Information Architecture

## Status

Planned

## Why This Phase Exists

The app already contains useful information. The problem is organization. This phase gives the product a stable top-level structure and prevents session, rolling, and learned-global ideas from blending together.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/app/router.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/components/AppShell.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`

Needs hardening:

- naming still mixes legacy and 2.0 framing
- reporting windows are not yet consistently labeled across pages
- the app still feels closer to an operations console than a designed product

## Scope

- lock top-level navigation
- standardize page structure and status chips
- standardize time displays and reporting windows
- add a shared explanation pattern for important metrics

## Dependencies

- none strictly technical, but it should follow Phase 01 conceptually so the information hierarchy reflects the new live model

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/app/router.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/components/AppShell.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/lib/format.ts`
- shared components under `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/components/`

## Concrete Work

- [ ] Lock final labels:
  - `Live Studio`
  - `Matchup Lab`
  - `Players Intelligence`
  - `Analytics Lab`
  - `Operations`
- [ ] Standardize `Current Session`, `Rolling`, and `All-Time Learned` labels.
- [ ] Standardize timezone and timestamp labeling.
- [ ] Add a reusable metric help pattern.
- [ ] Reduce duplicated phrasing and repeated cards where possible.

## Validation

- a user can tell what page they are on and what kind of information it contains quickly
- session-only and long-run metrics are visually distinct

## Done Definition

This phase is done when the app’s top-level navigation and metric windows are stable, obvious, and consistent across the product.
