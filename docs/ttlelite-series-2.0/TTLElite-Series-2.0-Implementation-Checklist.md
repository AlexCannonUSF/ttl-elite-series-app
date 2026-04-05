# TTLElite Series 2.0 Implementation Checklist

This checklist is organized in build order and grounded in the current codebase.

Each work item is tagged as one of:

- `Foundation landed`: scaffolding exists in code and should be hardened or extended
- `Needs hardening`: partially implemented but not yet reliable enough to count as complete
- `New work`: not materially implemented yet

## Phase 0: Planning Sync And Contract Lock

### Docs and program guardrails

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/README.md`
- `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Master-Plan.md`
- `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Implementation-Checklist.md`
- `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Phased-Product-Spec.md`
- `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Live-Data-Architecture.md`

Tasks:

- [ ] Lock terminology for:
  - `Live Studio`
  - settlement reason codes
  - settlement source codes
  - observation source hierarchy
  - `Current Session`, `Rolling`, `All-Time Learned`
- [ ] Add a simple done-definition section to each phase doc.
- [ ] Keep release order reliability-first.

## Phase 1: Live Data Reliability

### Scraper layer

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/scrape/HardRockOddsScraperTests.java`

Current state:

- `Foundation landed`
  - `fetch()` is the primary odds path
  - `fetchScoreboard()` exists
  - `fetchScoreboardByEventIds(...)` exists and is the right architectural direction
- `Needs hardening`
  - public-tree scoreboard parsing under `fetchOfficialPublicTreeScoreboard(...)`
  - scoreboard parsing under `parseCompetitionNodeArrayScoreboard(...)` still assumes event arrays and does not robustly model current `{ count: N }` competition nodes

Tasks:

- [ ] Split the scraper mentally and structurally into:
  - priceable market ingestion
  - tracked-score ingestion
  - source-health/discovery ingestion
- [ ] Audit `fetchScoreboardByEventIds(...)` to ensure event-id queries survive market closure states whenever upstream still exposes match state.
- [ ] Rework public-tree parsing so `events` nodes with `{ count: N }` are handled gracefully.
- [ ] Add explicit logging/health output when public-tree parsing fails because of response shape, not transport failure.
- [ ] Persist and expose `sourceFeedCode`, `sourceFeedEventId`, `matchCompleted`, set score, point score, and per-game scores from `matchState` whenever present.
- [ ] Add parser contract fixtures for:
  - GraphQL event payload with full `matchState`
  - GraphQL scoreboard-by-event-id payload
  - public-tree category/competition payload with `events.count`
  - hidden/suspended/resulted market cases

### Odds and score engine

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`

Current state:

- `Foundation landed`
  - `liveScoreSnapshots(...)`
  - `liveScoreSnapshotsForEventIds(...)`
- `Needs hardening`
  - explicit confidence ranking and health reporting across sources

Tasks:

- [ ] Add explicit source-rank metadata to live score snapshots.
- [ ] Add stale-source detection and timestamp aging to snapshot DTOs.
- [ ] Ensure tracked score polling is available even when a match is no longer currently recommended.
- [ ] Add a board-vs-score divergence signal for integrity reporting.

## Phase 2: Win/Loss Detection 2.0

### Paper-trading lifecycle and settlement

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/PaperTradeBet.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/PaperTradeSession.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/service/PaperTradingServiceTests.java`

Current state:

- `Foundation landed`
  - tracked observations are stored
  - `externalEventId` is stored on bets
  - `settlementReason` and `settlementSource` are stored
- `Needs hardening`
  - full lifecycle state machine still lives more in logic than in explicit persisted state
  - timeout and fallback policy still need clearer hierarchy and stronger replay coverage

Tasks:

- [ ] Introduce an explicit lifecycle enum for tracked match state instead of relying on scattered booleans/phase strings.
- [ ] Add explicit transitions for:
  - `DISCOVERED`
  - `PRICEABLE`
  - `LIVE_TRACKED`
  - `MARKET_CLOSED_SCORE_TRACKED`
  - `FINISHED_UNCONFIRMED`
  - `FINISHED_CONFIRMED`
  - `SETTLED`
  - `VOIDED`
- [ ] Make settlement consume the observation timeline first, not a single latest row.
- [ ] Separate these outcomes clearly in code and UI:
  - no board row
  - no tracked score update yet
  - likely finished but unconfirmed
  - confirmed official result
  - timeout void
- [ ] Replace free-form settlement reason growth with a stable reason-code set.
- [ ] Build replay tests for real failure modes:
  - board row disappears at `2-0`
  - board row disappears at `2-2`
  - market disappears during deuce-like point state
  - event disappears and later reappears
  - duplicate name collision
  - internal DB result arrives later than tracked score
- [ ] Add settlement audit methods that summarize how a winner was inferred and why it was trusted.

### Observation persistence

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/TrackedMatchObservation.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/TrackedMatchObservationRepository.java`

Current state:

- `Foundation landed`
  - observations persist score snapshots and source metadata
- `Needs hardening`
  - may need companion tracked-event state entity for latest lifecycle/health state

Tasks:

- [ ] Add a `TrackedEventState` concept if lifecycle logic becomes too expensive to infer from snapshots.
- [ ] Add event-level stale tracking fields:
  - last board observation time
  - last score observation time
  - last authoritative confirmation time
- [ ] Add stronger dedupe keys and alias-safe identity metadata.

## Phase 3: Live Studio API Contract

### Dedicated product APIs

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/LiveStudioController.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/dto/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/lib/api.ts`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/types/api.ts`

Current state:

- `Foundation landed`
  - `/api/live-studio/*` exists with board/session/open-bets/settled-tape/completed-matches/integrity/timeline/sync/reset
- `Needs hardening`
  - contract still needs to fully distinguish current-session metrics from rolling/all-time learned metrics
  - timeline payload is present but not yet a first-class UI feature

Tasks:

- [ ] Split session DTOs into explicit windows where needed:
  - session-only
  - rolling
  - learned-global
- [ ] Add score source confidence and staleness age everywhere it matters.
- [ ] Add integrity counts for:
  - authoritative score settlements
  - official result settlements
  - DB reconciliations
  - heuristic fallbacks
  - timeout voids
  - tracked-after-close open positions
- [ ] Keep analytics/admin endpoints diagnostic only; avoid mixing them into product-state APIs.

## Phase 4: Live Studio Frontend

### Routing and navigation

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/app/router.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/components/AppShell.tsx`

Current state:

- `Foundation landed`
  - `/` routes to `LiveOddsPage`
  - Live Studio naming already started

Tasks:

- [ ] Lock final top-level navigation labels:
  - `Live Studio`
  - `Matchup Lab`
  - `Players Intelligence`
  - `Analytics Lab`
  - `Operations`
- [ ] Keep any legacy route names as aliases only.

### Live Studio page

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`

Current state:

- `Foundation landed`
  - page already consumes live-studio endpoints
  - session cards, integrity card, open ledger, settled tape exist
- `Needs hardening`
  - row timeline interaction is missing
  - current-session vs learned-global windows still need cleaner visual discipline
- `New work`
  - full studio-grade drilldown and score-tracker experience

Tasks:

- [ ] Reframe the page into consistent sections:
  - session overview
  - live board
  - open positions
  - settled tape
  - score tracker
  - integrity
  - charts
- [ ] Add row-level expansion using `/api/live-studio/match/{eventKey}/timeline`.
- [ ] Add visible badges for:
  - `OUR PICK`
  - `TRACKED AFTER CLOSE`
  - `SCORE CONFIRMED`
  - `OFFICIAL RESULT`
  - `HEURISTIC FALLBACK`
- [ ] Show last score update timestamp and source confidence on open positions.
- [ ] Make matchups and player names clickable into deeper surfaces.
- [ ] Keep “why unsettled?” visible on open bets.
- [ ] Make charts session-only by default on the live page and label any rolling/global views clearly.

## Phase 5: Matchup Lab 2.0

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/MatchupPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeatureService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/AnalyticsController.java`

Current state:

- `Foundation landed`
  - matchup page exists
  - feature vectors already expose sample-weight fields
- `Needs hardening`
  - significance explanations and “why not bet” framing are still thin

Tasks:

- [ ] Make player search robust to sportsbook-style aliases and reversed names.
- [ ] Add clear sample-size labels beside H2H and form features.
- [ ] Add a “why not bet this?” block next to “why bet this?”.
- [ ] Add stronger fair-line and confidence-band visualization.
- [ ] Make both players link into Player Detail.
- [ ] Surface live-context data when a current tracked event exists.

## Phase 6: Players Intelligence

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/PlayersPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/PlayerDetailPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/PlayerController.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/TtSeriesEloSyncService.java`

Tasks:

- [ ] Redesign the players list around search, filters, and useful presets.
- [ ] Promote Elo/Glicko/RD/volatility as first-class intelligence, not buried stats.
- [ ] Add recent-form and pressure-state splits.
- [ ] Add alias confidence / sportsbook name resolution visibility.
- [ ] Add charts for rating path, form versus opponent quality, and style tendencies.
- [ ] Ensure every player mention across the app can route here.

## Phase 7: Prediction, Pricing, And Calibration

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeatureService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/service/`

Current state:

- `Foundation landed`
  - feature service already computes stabilized recent form/H2H/opponent/schedule signals
  - prediction service already supports multiple model families and live learning concepts
- `Needs hardening`
  - more explicit prematch vs live regime handling
  - better calibration buckets and time-aware validation
  - more disciplined use of live-state variables

Tasks:

- [ ] Shrink low-sample features harder and more transparently.
- [ ] Separate prematch and live-state model behavior.
- [ ] Add live-state features for:
  - set differential
  - point differential
  - pressure state
  - late-stage proximity to closeout
  - source confidence / staleness penalty where relevant
- [ ] Add walk-forward or time-aware validation instead of treating all splits as stationary.
- [ ] Add calibration buckets by:
  - favorite/dog
  - live early/mid/late
  - plus-money / heavy favorite
- [ ] Add challenger-vs-active reporting usable by Analytics Lab.

## Phase 8: Risk, Staking, And Selection

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`

Tasks:

- [ ] Rework strategy profiles into explicit policy sets rather than rough labels.
- [ ] Use risk-adjusted EV instead of raw edge alone.
- [ ] Add concentration guards:
  - max per player
  - max per trigger family
  - max simultaneous exposure
  - max bankroll at risk
- [ ] Expose stake rationale in the UI.
- [ ] Add stronger longshot and stale-data penalties.

## Phase 9: Session Learning And Telemetry

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`

Tasks:

- [ ] Persist all considered opportunities, not only placed bets.
- [ ] Persist score observations for every tracked match through settlement.
- [ ] Persist all settlement evidence and source confidence.
- [ ] Keep current-session views separate from inherited learned adjustments.
- [ ] Add per-trigger and per-regime ledgers.
- [ ] Add adaptive-learning audit trails.

## Phase 10: Analytics Lab

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/AnalyticsPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/AdminController.java`

Tasks:

- [ ] Rebuild analytics around calibration, profitability, trigger attribution, and model comparison.
- [ ] Keep registry details, but stop letting the page read like a raw model dump.
- [ ] Add charts for:
  - reliability curve
  - ROI by trigger family
  - bankroll by strategy
  - drift since training
  - expected vs realized edge
- [ ] Add active vs challenger model framing.
- [ ] Make training failures readable to non-developers.

## Phase 11: Operations And Observability

Files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/AdminPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/`

Tasks:

- [ ] Add source health panels for:
  - live odds feed
  - tracked score feed
  - official result/archive source
  - DB reconciliation/backfill
- [ ] Add stage-level run visibility:
  - fetch
  - parse
  - identity resolve
  - persist
  - enrich
  - settle
- [ ] Add replay tooling for broken events.
- [ ] Add response-shape diagnostics for upstream parser drift.

## Phase 12: Release Readiness

Tasks:

- [ ] Prove score continuity through real market-closure cases.
- [ ] Prove settlement reason/source auditability.
- [ ] Prove session-vs-rolling-vs-global reporting separation.
- [ ] Prove Live Studio usability without needing admin context.
- [ ] Only then treat UI polish as release-level work.

## First Three Execution Slices

If we are sequencing the next real coding work, the best order is:

1. `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
   - harden tracked score continuity and public-tree resilience
2. `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
   - finish lifecycle state and settlement auditability
3. `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`
   - expose timeline/tracker/integrity clearly to the user
