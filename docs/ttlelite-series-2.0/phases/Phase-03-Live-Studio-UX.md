# Phase 03: Live Studio UX

## Status

Planned

## Why This Phase Exists

Live Studio should be the center of the product. It should make the current session, tracked matches, open positions, and settlement integrity understandable without forcing the user to piece together meaning from multiple tables.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/LiveStudioController.java`

Needs hardening:

- timeline data exists but is not yet a first-class interaction
- score-tracked-after-close state needs stronger visual treatment
- current-session vs learned-global data still needs cleaner discipline on the page

## Scope

- session overview
- live board
- open positions
- settled tape
- score tracker
- integrity
- charts
- row drilldowns and timeline views

## Dependencies

- Phases 00 through 02

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/lib/api.ts`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/types/api.ts`

## Concrete Work

- [ ] Rebuild the page into clear zones instead of a stack of adjacent blocks.
- [ ] Add row-level timeline expansion using `/api/live-studio/match/{eventKey}/timeline`.
- [ ] Add strong status badges:
  - `OUR PICK`
  - `TRACKED AFTER CLOSE`
  - `SCORE CONFIRMED`
  - `OFFICIAL RESULT`
  - `HEURISTIC FALLBACK`
- [ ] Show last score update time and score source confidence on open positions.
- [ ] Make player names and matchup names clickable from the live board and ledgers.
- [ ] Make “why unsettled?” visible for open positions.
- [ ] Keep current-session charts clearly separated from rolling/global context.

## Validation

- the user can inspect one tracked match from board row to settlement evidence without leaving Live Studio
- the page makes tracked-after-close behavior obvious

## Done Definition

This phase is done when Live Studio feels like a coherent trading workspace instead of a collection of independent tables.
