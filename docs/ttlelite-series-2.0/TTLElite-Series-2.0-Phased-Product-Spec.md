# TTLElite Series 2.0 Phased Product Spec

## Product Summary

TTLElite Series 2.0 is a live sportsbook studio for TTL table tennis.

It should combine:

- live odds discovery
- uninterrupted score tracking
- risk-aware recommendation generation
- paper-trading telemetry
- player research
- matchup inspection
- calibration and trigger analytics
- observability and replay tools

into one coherent product.

## Primary User Promise

The user should be able to trust three things:

1. the app can keep following a match even after betting visibility changes
2. the app can explain why a bet was or was not settled
3. the app can explain why a pick exists and whether it deserves action

## Product Windows

Every product surface should explicitly distinguish:

- `Current Session`
- `Rolling`
- `All-Time Learned`

That is a global requirement, not a page-specific preference.

## Phase 0: Contract Lock And Planning Sync

### Outcome

The team shares one vocabulary and one release order.

### Scope

- settle naming
- settle windows
- settle source hierarchy
- settle release criteria

### Dependencies

- none

### Acceptance criteria

- all 2.0 docs use the same terminology
- Live Studio, settlement source, and reporting window labels are stable
- release order starts with score continuity and settlement integrity

## Phase 1: Live Data Reliability

### Outcome

The system no longer relies on one feed concept to mean both `priceable market` and `trackable score`.

### Scope

- keep GraphQL events as primary live odds source
- harden tracked score polling by event id
- rework public-tree parsing for current Hard Rock response shapes
- persist score observations with timestamps and source metadata

### Existing foundation

- `LiveStudioController` exists
- `TrackedMatchObservation` exists
- `liveScoreSnapshotsForEventIds(...)` exists
- `fetchScoreboardByEventIds(...)` exists

### Dependencies

- stable event identity strategy
- source confidence strategy

### Acceptance criteria

- tracked matches continue to receive score updates after the market stops being displayed
- tracked score observations persist over time, not only as one latest value
- the system can show when a score came from board feed vs targeted score feed
- public-tree parsing failures are detectable as shape problems instead of generic empties

## Phase 2: Win/Loss Detection 2.0

### Outcome

Every settlement is reason-coded, auditable, and explainable.

### Scope

- lifecycle state machine
- timeline-first settlement
- confirmation layers beyond live board visibility
- labeled fallback and timeout behavior

### Existing foundation

- `PaperTradeBet` already stores `settlementReason` and `settlementSource`
- tracked observations already exist
- settlement heuristics already exist in `PaperTradingService`

### Dependencies

- Phase 1 score observations
- stable event identity

### Acceptance criteria

- each settled bet stores a settlement source and settlement reason
- heuristic settlement is visibly different from authoritative settlement
- voiding only occurs after stronger confirmation paths fail
- replay tests cover market-close-before-finish scenarios

## Phase 3: Live Studio API And Information Architecture

### Outcome

The app has a coherent product-state API and clean user mental model.

### Scope

- dedicated `Live Studio` product endpoints
- clean top-level navigation
- explicit time/reporting windows
- common metric explanations

### Existing foundation

- `/api/live-studio/*` endpoints exist
- `LiveOddsPage` already consumes them
- `/` already routes to the live experience

### Dependencies

- Phases 1 and 2 for meaningful integrity data

### Acceptance criteria

- the user can tell what is session-only vs rolling/global
- the user can tell what is live, open, tracked, unsettled, and stale without opening developer tools
- the API surface for live product state is separate from admin/analytics concerns

## Phase 4: Live Studio UX Redesign

### Outcome

Live Studio becomes the operational heart of the app.

### Scope

- session overview
- live board
- open positions
- settled tape
- score tracker
- integrity
- charts
- row expansion and timeline drilldown

### Existing foundation

- all major card groups already exist in some form on `LiveOddsPage`
- integrity metrics already exist in backend DTOs

### Dependencies

- Phases 1 through 3

### Acceptance criteria

- every open position shows last score, last update time, source label, and tracked-after-close state
- every settled position shows winner, final score, settlement source, and reason
- the user can expand a match row into a score/odds/settlement timeline without leaving the page
- charts default to current-session context unless clearly labeled otherwise

## Phase 5: Matchup Lab 2.0

### Outcome

The matchup page becomes a premium decision-inspection tool.

### Scope

- stronger search and alias handling
- significance-aware feature presentation
- clear fair line and confidence framing
- live context when a current event exists
- stronger `why bet` and `why not bet` narratives

### Existing foundation

- matchup page already exists
- feature vectors already expose sample-weight support signals

### Dependencies

- stronger player identity handling
- improved feature weighting language

### Acceptance criteria

- both player names are clickable to intelligence views
- users can inspect both upside and reasons for caution
- sample-size weakness is visible instead of hidden

## Phase 6: Players Intelligence

### Outcome

Player pages become full scouting and context surfaces.

### Scope

- search, filters, presets
- Elo/Glicko/RD/volatility
- form trends and strength of schedule
- favorite/dog and live-state splits
- alias confidence and sportsbook identity resolution
- charts and trend analysis

### Existing foundation

- players list and detail pages already exist
- Elo/Glicko sync and snapshots already exist

### Dependencies

- consistent player identity and alias handling

### Acceptance criteria

- a user can click any player name in the product and land on a useful intelligence page
- the page can stand alone without needing the live board for context

## Phase 7: Prediction, Pricing, And Calibration

### Outcome

The decision engine becomes more disciplined, state-aware, and mathematically defensible.

### Scope

- significance-aware shrinkage
- prematch vs live regime separation
- stronger live-state features
- time-aware validation
- calibration by regime and price bucket

### Existing foundation

- feature engineering service exists
- multiple model families exist
- live learning and calibration ideas already exist

### Dependencies

- stable data capture from Phases 1 and 2

### Acceptance criteria

- low-sample H2H and form signals are materially shrunk toward priors
- live-state probabilities behave differently from prematch probabilities in a traceable way
- walk-forward or chronological evaluation is available for model assessment

## Phase 8: Risk, Staking, And Selection

### Outcome

The app becomes more selective and more profit-oriented without becoming reckless.

### Scope

- risk-adjusted EV
- exposure caps
- stake rationale
- strategy policy sets
- longshot and stale-data penalties

### Dependencies

- improved pricing and calibration

### Acceptance criteria

- stake size is explainable in product terms
- overexposure is visible before it becomes a session problem
- strategy choices produce visibly different pick behavior

## Phase 9: Session Learning And Telemetry

### Outcome

The app learns from real sessions while keeping current-session reporting clean.

### Scope

- capture all considered opportunities
- capture score timelines
- capture settlement provenance
- capture adaptive tuning deltas
- keep session and inherited learning separate

### Dependencies

- Phases 1, 2, 7, and 8

### Acceptance criteria

- the user can audit one session cleanly
- the user can also see global learning state
- the two reporting windows are never conflated visually or semantically

## Phase 10: Analytics Lab

### Outcome

Analytics becomes a control room for model quality, calibration, and trigger performance.

### Scope

- calibration curves
- ROI by trigger family
- bankroll by strategy
- feature reliability
- drift and challenger-vs-active comparisons
- cleaner model registry and training readouts

### Dependencies

- captured telemetry from earlier phases

### Acceptance criteria

- active vs challenger state is obvious
- calibration and profitability are readable to a non-developer user
- model registry is useful instead of noisy

## Phase 11: Operations And Observability

### Outcome

The app becomes diagnosable and supportable under real feed drift and live-session failures.

### Scope

- source health panels
- stage-level run visibility
- parser drift diagnostics
- replay tools
- continuity failure diagnostics

### Dependencies

- earlier phases for useful integrity/state metadata

### Acceptance criteria

- the user can tell whether a source or parser is degraded
- a broken event can be replayed and diagnosed
- operations reads like observability, not clutter

## Phase 12: Release Readiness

### Outcome

2.0 ships only when the live reliability thesis is proven.

### Scope

- live reliability validation
- settlement auditability validation
- session/reporting separation validation
- product usability validation

### Acceptance criteria

- real cases confirm score continuity through market closure
- most settlements are authoritative or clearly labeled fallback
- the user can understand Live Studio without needing Admin to interpret it

## Cross-Phase UX Requirements

- the live workflow should feel premium and calm, not noisy
- every important metric with non-obvious meaning should have a short explanation
- all player and matchup names that matter should be clickable
- the most important state should be visible before the user reaches tables
- ambiguity should be surfaced, not hidden

## Cross-Phase Data Requirements

- tracked events need stable identity keys
- score observations need timestamps and source provenance
- settlement events need reason/source codes
- adaptive tuning changes need auditability
- session-only and rolling/global windows need separate DTO support

## Global Release Criteria

TTLElite 2.0 is launch-ready only when:

- score continuity survives market closure reliably
- settlement is explainable and inspectable
- current-session reporting is trustworthy
- player and matchup drilldowns feel complete enough to use in decision-making
- model output quality is calibrated enough to defend publicly inside the product
