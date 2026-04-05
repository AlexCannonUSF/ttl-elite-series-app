# Phase 04: Matchup Lab 2.0

## Status

Planned

## Why This Phase Exists

The matchup page should help decide whether to act, not just dump features. This phase makes the matchup experience explain the case for and against a pick with proper sample-size context.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/MatchupPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeatureService.java`

Needs hardening:

- significance/sample-size communication is still too light
- player lookup and alias handling need to be more forgiving
- live context is not yet woven cleanly into the experience

## Scope

- player search and alias handling
- sample-size-aware feature explanations
- fair line and confidence band presentation
- live context when relevant
- stronger `why bet` and `why not bet` narrative

## Dependencies

- stronger player identity handling
- Phase 02 information architecture labels and conventions

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/MatchupPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeatureService.java`

## Concrete Work

- [ ] Improve sportsbook-name and alias search tolerance.
- [ ] Make both players link to Player Detail.
- [ ] Add clear sample-size labels beside H2H and form metrics.
- [ ] Add a stronger fair-line and confidence display.
- [ ] Add a `why not bet this?` explanation block.
- [ ] Surface current live context if the matchup is active/tracked now.

## Validation

- the user can use the page to support or reject a pick confidently
- low-sample features do not look as authoritative as high-sample features

## Done Definition

This phase is done when the matchup page functions as a decision-inspection tool rather than a feature dump.
