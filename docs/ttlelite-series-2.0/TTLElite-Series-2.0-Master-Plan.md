# TTLElite Series 2.0 Master Plan

## Executive Summary

TTLElite Series 2.0 is not just a UI refresh.

It is a reliability-first rebuild of the app into a **live sportsbook studio** that can:

- price and rank live table-tennis opportunities
- keep following score after a market stops being priceable
- settle every bet with explicit evidence and reason codes
- learn from sessions without mixing short-term reporting with long-term model state
- make player research, matchup inspection, and trigger analytics feel coherent and premium

The refined 2.0 thesis is simple:

**A match can remain observable after it stops being bettable.**

That thesis drives the rest of the architecture.

## Research-Backed Constraints

### Table-tennis scoring

Official table-tennis scoring remains the baseline assumption for TTL unless a source proves otherwise:

- games are played to 11 points
- a game must be won by 2 points
- matches are won by taking a majority of games in an odd-number format

This matters because 2.0 should never embed settlement logic that assumes a match must stay visible until a sportsbook displays a final result.

### Hard Rock live behavior

The sportsbook exposes live odds and live match data, but those are not guaranteed to remain visible in the same way for the full duration of a match. Markets can suspend, hide, or disappear even while the sporting event continues. That is normal sportsbook behavior, not necessarily a feed failure.

The product implication is:

- `priceability` is a market-state concern
- `observability` is a match-state concern
- 2.0 must model those separately

### Public reference anchors

The planning assumptions above are consistent with:

- the official [ITTF Handbook](https://db.ittf.com/sites/default/files/public/2021-08/2021ITTFHandbook_v2_clean_version_1.pdf), which states that a game is won at 11 points unless both players reach 10, in which case the winner must lead by 2
- Hard Rock Bet's [live betting guide](https://www.hardrock.bet/sportsbook/live-betting/), which frames live markets as a changing, real-time sportsbook surface rather than a guaranteed full-match tracker
- Hard Rock Bet's [table tennis page](https://www.hardrock.bet/sportsbook/table-tennis/), which emphasizes live table-tennis consumption through the sportsbook and streaming hub

### Local codebase reality

The current codebase already contains meaningful 2.0 scaffolding:

- `Live Studio` routing and controller structure exist:
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/LiveStudioController.java`
  - `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/app/router.tsx`
- tracked observations already exist as a persistence concept:
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/TrackedMatchObservation.java`
- targeted score polling already exists for open bets by external event id:
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
- settlement reason/source metadata already exists:
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/PaperTradeBet.java`
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`

That means 2.0 is not starting from zero.

It also means the docs need to distinguish between:

- `foundation already landed`
- `needs hardening`
- `new work`

## 2.0 Product Thesis

TTLElite 2.0 should stop acting like a collection of screens and start acting like a single product with three layers:

### 1. Data Layer

Responsible for:

- odds discovery
- score continuity
- match identity
- result confirmation
- run/session storage

### 2. Decision Layer

Responsible for:

- pricing
- confidence bands
- trigger logic
- strategy rules
- risk sizing
- adaptive learning

### 3. Experience Layer

Responsible for:

- Live Studio
- Matchup Lab
- Players Intelligence
- Analytics Lab
- Operations

The core architecture rule is:

- market visibility should not control whether we can continue following a match

## Current-State Assessment

### What is already working or partially landed

- Hard Rock GraphQL events are the main live odds source.
- Live Studio endpoints exist.
- Open bets can already retain `externalEventId` and score-source metadata.
- The paper-trading service already stores settlement reason/source and tracked observations.
- Targeted scoreboard polling by event id is already present in the service/scraper path.
- The frontend already has the beginnings of session-only integrity reporting.

### What is still weak or incomplete

- The public-tree scoreboard path still looks fragile against current `{ count: N }` competition shapes.
- Score continuity is improved, but not yet proven as a complete post-market-close observation architecture.
- Timeline data exists but is not yet central to the user workflow.
- Matchup, player intelligence, and analytics surfaces still feel closer to internal tooling than a polished trading studio.
- The model stack still needs stronger significance-aware weighting and regime-specific calibration.
- Session metrics, rolling metrics, and learned-global metrics are not yet uniformly separated across the product.

## Product Goals

### Goal 1: Score continuity independent from market visibility

TTLElite should continue to know what is happening in a tracked match after live betting stops being displayed.

### Goal 2: Settlement explainability

Every win, loss, push, or void must carry a reason code, a source label, and enough evidence to audit it later.

### Goal 3: Better pick discipline

The app should optimize for risk-adjusted profitability, not just raw edge count or raw pick count.

### Goal 4: One-glance clarity

The live product should make it obvious:

- what is live
- what is tracked
- what we picked
- what is open
- how the session is doing
- whether the system is healthy

### Goal 5: Premium research surfaces

Players and matchups should feel like intelligence tools, not just supporting tables.

## Design Principles

### Reliability before aesthetics

If score continuity is not trustworthy, Live Studio is not finished.

### Clarity before density

Users should understand state before reading details.

### Evidence before heuristics

The app should prefer tracked score and official confirmation over inferred completion.

### Bounded learning over unstable adaptation

Learning should be visible, gradual, and reversible.

### Premium feel without ambiguity

The UI should feel intentional and modern, but it should never hide uncertainty.

## 2.0 Workstreams

### A. Live Data Reliability

- Keep GraphQL events as the main odds source.
- Strengthen tracked-event score polling.
- Rework public-tree parsing for current competition/event shapes.
- Persist richer observation evidence, source confidence, and health signals.

### B. Win/Loss Detection 2.0

- Move settlement onto a lifecycle state machine.
- Make tracked observation timelines the primary settlement evidence store.
- Use official result and DB reconciliation as confirmation/backfill layers.
- Keep weak fallbacks explicit and rare.

### C. Live Studio Product UX

- Make Live Studio the default home.
- Reorganize it into session, board, positions, tape, tracker, integrity, and charts.
- Add row drilldowns, timeline visibility, and stronger status badges.

### D. Matchup And Player Intelligence

- Upgrade matchup inspection into a decision lab.
- Upgrade player pages into actual scouting/intelligence surfaces.
- Make player names clickable from anywhere they appear.

### E. Prediction, Calibration, And Risk

- Add significance-aware shrinkage.
- Separate prematch and live modeling behavior.
- Improve live-state features and calibration buckets.
- Use risk-adjusted EV and stronger staking/exposure controls.

### F. Session Learning And Analytics

- Persist all considered opportunities, score timelines, and settlement provenance.
- Separate current-session views from rolling/all-time learned views.
- Turn analytics into an actual model-quality lab.

### G. Operations And Observability

- Make source health visible.
- Improve parser diagnostics and run replay.
- Make broken matches easy to investigate.

## Navigation Model For 2.0

The product should standardize around:

- `Live Studio`
- `Matchup Lab`
- `Players Intelligence`
- `Analytics Lab`
- `Operations`

`Live Studio` should be the default landing experience.

## Reporting Windows

Every reporting surface should use one of these labels explicitly:

- `Current Session`
- `Rolling`
- `All-Time Learned`

No card should mix those windows without saying so.

## Release Order

### Release Gate 1

Live data reliability and score continuity.

### Release Gate 2

Settlement explainability and integrity reporting.

### Release Gate 3

Live Studio information architecture and session UX.

### Release Gate 4

Matchup and player intelligence surfaces.

### Release Gate 5

Model/risk upgrades and analytics maturity.

### Release Gate 6

Operations hardening and release readiness.

## Success Metrics

### Reliability

- percentage of open bets with uninterrupted tracked score after market closure
- percentage of settlements backed by tracked score or official confirmation
- void rate caused by observation failure
- duplicate-match tracking rate
- stale tracked-match rate

### Model Quality

- calibration by regime
- ROI by trigger family
- realized value versus expected value
- performance split by favorite/dog and live state
- false positive clusters by trigger family

### UX/Product Quality

- time to identify open exposure
- time to understand why a bet exists
- time to understand why a bet is unsettled
- time to audit a settled bet

## Non-Goals

- more picks at the expense of settlement quality
- UI polish that papers over ambiguity
- overfitting through fast online drift
- trusting one sportsbook response shape as permanent

## Decision Rule For 2.0

A change should ship only if it improves at least one of these without materially hurting the others:

- score continuity
- settlement explainability
- pick quality
- user clarity

## Current 2.0 Readiness Statement

The app is already beyond a prototype, but it is not yet at the fully trustworthy 2.0 line.

The strongest foundation is already present in code.

The remaining challenge is to harden and connect that foundation so the product behaves consistently under the exact failure mode that has caused the most user pain: **markets closing before score visibility is truly finished.**
