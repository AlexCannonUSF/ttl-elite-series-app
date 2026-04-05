# Phase 05: Players Intelligence

## Status

Planned

## Why This Phase Exists

The player surfaces should work like a scouting database. The user should be able to click a player from anywhere and immediately understand form, ratings, tendencies, and identity quality.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/PlayersPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/PlayerDetailPage.tsx`
- Elo/Glicko snapshot infrastructure in the backend

Needs hardening:

- player views still lean heavily on leaderboard framing
- identity confidence and alias clarity are not surfaced enough
- richer splits and trend charts are still missing

## Scope

- richer search and filters
- rating and uncertainty visibility
- recent form and pressure/state splits
- alias confidence and sportsbook naming context
- player trend charts

## Dependencies

- consistent player identity resolution
- clickable links from Live Studio and Matchup Lab

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/PlayersPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/PlayerDetailPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/PlayerController.java`

## Concrete Work

- [ ] Redesign the player list around search, filters, and preset views.
- [ ] Promote Elo, Glicko, RD, and volatility to first-class stats.
- [ ] Add favorite/dog and live-state splits where support is sufficient.
- [ ] Add alias confidence / sportsbook-name resolution visibility.
- [ ] Add charts for rating path, recent form, and opponent strength context.
- [ ] Make all player names across the app route here.

## Validation

- a user can land on a player page and get enough context without returning to the live board

## Done Definition

This phase is done when player pages feel like intelligence surfaces instead of leaderboard drilldowns.
