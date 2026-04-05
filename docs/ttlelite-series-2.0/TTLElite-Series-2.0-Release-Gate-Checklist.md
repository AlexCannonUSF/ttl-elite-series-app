# TTLElite Series 2.0 Release Gate Checklist

This is the practical release gate for TTLElite Series 2.0.

Use this document when we want to answer one question clearly:

**Is the app reliable enough to run live without babysitting the core score/settlement loop?**

This checklist is intentionally concrete. It is tied to:

- repeatable scripts
- specific test suites
- specific runtime endpoints
- exact product surfaces that should be checked before launch

---

## 1. Release Goal

TTLElite Series 2.0 is ready for launch only when all of these are true:

- clean reset creates one fresh active session
- live score updates continue cleanly while a match is visible
- visible live scoring is not mislabeled as after-close tracking
- when markets disappear, tracked-after-close behavior remains explainable
- settlement source and reason are preserved and inspectable
- current-session reporting is clearly separated from rolling / learned behavior
- Live Studio, Matchup Lab, Players Intelligence, Analytics Lab, and Operations read like one product

---

## 2. Fast Operator Checks

### 2.0 One-Command Gate

Run:

```bash
bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/release_gate.sh
```

Optional full gate with settlement watch:

```bash
TTL_RUN_SETTLEMENT_WATCH=true bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/release_gate.sh
```

Useful variants:

```bash
TTL_RUN_SUPPORT_TESTS=false bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/release_gate.sh
TTL_RUN_SETTLEMENT_WATCH=true TTL_WATCH_LOOPS=18 TTL_WATCH_SLEEP_SECONDS=20 bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/release_gate.sh
TTL_RUN_SETTLEMENT_WATCH=true TTL_TARGET_BET_ID=1036 bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/release_gate.sh
```

Use this script when we want one repeatable release gate instead of running the smoke, build, and backend checks manually.

### 2.1 Live Studio Smoke

Run:

```bash
bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_studio_smoke.sh
```

This must pass.

It verifies:

- clear-history reset works
- `/api/live-studio/session` points at the fresh session
- `/api/live-studio/integrity` starts at zero
- one sync runs on the same session
- visible upcoming bets remain:
  - `OPEN_PENDING_SCORE`
  - `trackedAfterClose=false`

### 2.2 Settlement Watch

Run:

```bash
bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_settlement_watch.sh
```

Useful variants:

```bash
TTL_TARGET_BET_ID=1036 bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_settlement_watch.sh
TTL_TARGET_SIDE_NAME="Jakub Michalski" bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_settlement_watch.sh
TTL_WATCH_LOOPS=12 TTL_WATCH_SLEEP_SECONDS=10 bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_settlement_watch.sh
```

This check is successful when we can observe at least one of the following behaving correctly:

- visible score progression with no false `trackedAfterClose`
- transition from visible score to after-close tracking
- a real settlement with a sensible `settlementSource` and `settlementReason`

---

## 3. Required Backend Test Suites

### 3.1 Launch-Critical Core

Run:

```bash
cd /Users/alexcannon/Downloads/TTLEliteSeries
./mvnw -Dtest=PaperTradingServiceTests,OddsValueEngineServiceTests,PredictionModelServiceTests,FeatureServiceTests test
```

This is the minimum core gate.

It covers:

- score continuity
- settlement integrity
- selection/staking controls
- live odds recommendation behavior
- feature significance / shrinkage
- walk-forward model validation behavior

### 3.2 Additional Support Suites

Run when we want broader confidence:

```bash
cd /Users/alexcannon/Downloads/TTLEliteSeries
./mvnw -Dtest=HardRockOddsScraperTests,ScrapeMetricsTests,PlayerIdentityServiceTests,TtSeriesEloSyncServiceTests test
```

This adds confidence around:

- upstream parsing
- scrape metrics
- player identity / alias handling
- ranking sync behavior

---

## 4. Required Frontend Validation

Run:

```bash
cd /Users/alexcannon/Downloads/TTLEliteSeries/web
npm run build
```

This must pass.

Current expectation:

- route splitting remains healthy
- no oversized Vite chunk warning should reappear

If the build passes but route-level behavior looks wrong, manually visit the main surfaces:

- `/`
- `/dashboard`
- `/matchup`
- `/players`
- `/analytics`
- `/admin`

---

## 5. Runtime Endpoint Checklist

During a live validation run, inspect these endpoints directly:

### 5.1 Session

```bash
curl -s http://localhost:8080/api/live-studio/session | jq
```

Look for:

- one active session
- correct `sessionId`
- sensible `decisionTelemetry`
- sensible `exposureMetrics`
- current-session-only values, not mixed windows

### 5.2 Integrity

```bash
curl -s http://localhost:8080/api/live-studio/integrity | jq
```

Look for:

- `trackedAfterCloseObservations`
- `targetedCompletionSettlements`
- `officialResultSettlements`
- `databaseSettlements`
- `heuristicSettlements`
- `voidedSettlements`

The goal is not “zero after-close behavior.”  
The goal is “the counters make sense and match observed runtime behavior.”

### 5.3 Open Bets

```bash
curl -s http://localhost:8080/api/live-studio/open-bets | jq
```

Look for:

- visible prematch rows:
  - `trackingState=OPEN_PENDING_SCORE`
  - `trackedAfterClose=false`
- visible live rows:
  - `trackingState=OPEN_SCORE_VISIBLE`
- after-close rows:
  - only when feed evidence supports it

### 5.4 Settled Tape

```bash
curl -s 'http://localhost:8080/api/live-studio/settled-tape?limit=10' | jq
```

Look for:

- non-empty rows after real settlements
- sensible:
  - `settlementSource`
  - `settlementReason`
  - `lastObservedScore`
  - `settledAt`

### 5.5 Match Timeline

```bash
curl -s 'http://localhost:8080/api/live-studio/match/<eventKey>/timeline' | jq
```

Use this when a row looks suspicious.

Look for:

- observation continuity
- source changes
- after-close evidence
- `matchCompleted` or `resulted` progression

---

## 6. Product Surface Acceptance

These are the human-facing acceptance checks.

### 6.1 Live Studio

Must be able to answer quickly:

- how many open bets exist
- which matches are live
- whether a bet is visible-score or after-close tracked
- why a bet is still open
- what the score continuity health looks like
- what the portfolio exposure looks like

### 6.2 Matchup Lab

Must clearly show:

- rating baseline
- signal reliability
- feature significance
- strongest/weakest support layers
- a usable decision lens

### 6.3 Players Intelligence

Must clearly show:

- trusted sample versus thin sample
- alias coverage / identity confidence
- rating stability / uncertainty
- useful scouting context, not just leaderboard rank

### 6.4 Analytics Lab

Must clearly show:

- champion model quality
- session ROI
- trigger ROI
- regime behavior
- calibration context
- adaptive tuning context

### 6.5 Operations

Must clearly show:

- feed health
- fallback reliance
- pipeline behavior
- scrape history / errors
- enough information to debug without reading logs first

---

## 7. Known Strong Signals Before Launch

These are the strongest “we’re in good shape” signs:

- `live_studio_smoke.sh` passes
- `live_settlement_watch.sh` shows clean score progression and/or a clean settlement
- `PaperTradingServiceTests` passes
- `OddsValueEngineServiceTests` passes
- `PredictionModelServiceTests` passes
- `FeatureServiceTests` passes
- frontend build passes cleanly
- reset/session/integrity state stays coherent across repeated runs

---

## 8. Known Stop-Ship Conditions

Do not treat the build as launch-ready if any of these appear:

- reset creates a fresh session but `/api/live-studio/session` points at another session
- visible upcoming rows are labeled `trackedAfterClose=true`
- visible live rows are treated as after-close without supporting feed evidence
- timeline evidence contradicts settlement source/reason
- settlement counters drift away from what the tape shows
- live board reads trigger retention/write side effects
- heuristic or DB fallback is doing most of the actual settlement work during normal live operation
- current-session metrics and rolling/learned metrics are visually mixed

---

## 9. Current Recommended Launch Sequence

Use this order:

1. Start the app with launch-like settings
2. Run:

```bash
bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_studio_smoke.sh
```

3. Run the core backend gate:

```bash
cd /Users/alexcannon/Downloads/TTLEliteSeries
./mvnw -Dtest=PaperTradingServiceTests,OddsValueEngineServiceTests,PredictionModelServiceTests,FeatureServiceTests test
```

4. Run frontend build:

```bash
cd /Users/alexcannon/Downloads/TTLEliteSeries/web
npm run build
```

5. Run a live watch:

```bash
bash /Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_settlement_watch.sh
```

6. Inspect:

- `/api/live-studio/session`
- `/api/live-studio/open-bets`
- `/api/live-studio/settled-tape`
- `/api/live-studio/integrity`

7. Sanity-check the UI in:

- `Live Studio`
- `Matchup Lab`
- `Players Intelligence`
- `Analytics Lab`
- `Operations`

---

## 10. Exit Condition

We can call TTLElite Series 2.0 launch-ready when:

- the smoke script passes
- the settlement-watch script shows clean live behavior
- the core backend suites pass
- the frontend build passes
- runtime session/integrity behavior stays coherent
- at least one real settlement is observed with a sensible source/reason path
- none of the stop-ship conditions appear
