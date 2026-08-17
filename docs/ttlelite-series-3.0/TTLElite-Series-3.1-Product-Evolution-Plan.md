# TTLElite Series 3.1 — Product Evolution, Research, and Replay Plan

**Status:** Final product scope for implementation planning

**Date:** 2026-08-17

**Builds on:** `TTLElite-Series-3.0-UI-Redesign-Spec.md`
**Evidence baseline:** `reports/2026-08-15-r1-final-run-next-model-research.md`

## 1. Product outcome

TTLElite 3.1 will be a local-first table-tennis betting intelligence suite, not a sportsbook and not a generic admin dashboard.

It will combine:

- the speed and visual hierarchy of a live sportsbook;
- the price comparison of an odds screen;
- the history and accountability of a bet tracker;
- the reproducibility of an experiment tracker;
- the point-in-time rigor of a replay engine;
- the transparency of a model research laboratory.

The product will let a user watch live matches and understand a decision quickly. It will let an administrator inspect every run, model call, policy decision, factor, price, result, and data-quality dependency without losing the selected context.

No part of this release places a real wager. Hard Rock is the user's executable reference book because the user is in Florida. Other sportsbooks are informational market references unless the user separately has lawful access to them.

## 2. Locked product decisions

### 2.1 Parallel model and policy evaluation

The application will separate **model versions** from **betting policies**. A model answers who is more likely to win and with what probability. A policy decides whether that prediction is actionable at the available price.

Each live decision opportunity supports up to five simultaneous model lanes:

1. **Champion** — the approved model used for the official simulation.
2. **Shadow A** — the leading candidate model.
3. **Shadow B** — a deliberately different candidate or ablation.
4. **Shadow C** — optional.
5. **Shadow D** — optional.

Three model lanes are shown by default. Five is the normal live cap because it covers meaningful alternatives without multiplying compute, storage, and operator noise. Replay may evaluate larger candidate batches asynchronously. Every lane receives the same timestamp-valid feature and market snapshot, remains frozen for the run, and is scored against the same eventual outcome. Multiple lanes on one match are correlated evaluations, not independent samples.

The application will also avoid loosening one official paper policy merely to manufacture samples. Each frozen model call can be evaluated through multiple simultaneous virtual policy portfolios.

Three default policy portfolios ship first:

1. **Champion Strict** — the current approved, conservative paper policy.
2. **Challenger Balanced** — moderately wider gates for controlled policy research.
3. **Discovery** — bounded, fixed-dollar, stratified exploration designed to fill evidence gaps.

The engine supports up to eight active policy portfolios per run. The UI shows three by default and lets an administrator add, clone, disable, or compare challengers. Increasing the number of virtual policies does not create new statistical observations; every portfolio must retain the same underlying match/call identity.

Three benchmarks run beside the portfolios and are never mislabeled as approved policies:

1. **All Model Leans** — hypothetical $1 on the model's more likely winner for every eligible frozen call.
2. **Hard Rock Baseline** — hypothetical $1 on Hard Rock's no-vig favorite at the captured Hard Rock price.
3. **Market Consensus Baseline** — hypothetical $1 on the weighted multi-book no-vig consensus favorite at the Hard Rock price when Hard Rock offers that side.

The architecture supports additional research benchmarks without adding them to the normal user view.

### 2.2 Market scope

- Hard Rock remains the primary displayed and simulated executable price.
- DraftKings, FanDuel, BetMGM, Caesars, Fanatics, bet365, Pinnacle, Circa, or other available sources can contribute informational reference prices.
- Reference books must be clearly marked `REFERENCE`, `UNAVAILABLE IN FLORIDA`, `STALE`, or `SOURCE DELAYED` when applicable.
- New book data must come from an authorized API, licensed feed, permitted integration, or user-supplied import. The plan does not assume unapproved scraping.
- Every source retains its own observation timestamp, event identity, market identity, and freshness state.

### 2.3 Visual direction

The interface will use a sportsbook/trading hybrid:

- sportsbook-like live hierarchy, compact rows, scores, and price tiles;
- odds-screen-style comparison and movement;
- trading-terminal-style filters, saved views, history, and drill-down;
- experiment-tracker-style run comparison and artifact identity;
- TTLElite branding rather than copying another company's logos, trade dress, or promotional language.

## 3. Product principles

1. **Decision first.** The first screen answers who, price, trust, score, and state.
2. **Progressive disclosure.** Technical depth is always reachable but never forced into the primary scan path.
3. **Every number is accountable.** Every metric exposes its definition, denominator, cohort, time window, source, freshness, and caveat.
4. **Every visual is interactive.** A chart must filter, compare, annotate, or drill into underlying matches. Decorative charts are not included.
5. **One frozen opportunity, many accountable evaluations.** Model lanes share an immutable opportunity/snapshot identity; policies and market benchmarks retain the exact model-call identity they evaluated.
6. **Point-in-time or not a backtest.** Historical replay must expose only information available at the simulated time.
7. **Runs are immutable experiments.** Every run preserves data, model, feature schema, calibration, policy, code, and result identity.
8. **Unknown is a valid answer.** Thin evidence, stale feeds, unresolved matches, and unavailable benchmarks remain visible.
9. **No silent filter changes.** The current cohort is always visible and encoded in the URL.
10. **No automatic learning during a live run.** A run measures a frozen candidate; changes are promoted between runs.

## 4. Existing foundation and material gaps

### 4.1 Foundation already available

The current application already has:

- live and scheduled market rows;
- Hard Rock odds and two-sided no-vig probability;
- odds snapshots and market overround;
- model fair odds and calibrated probability;
- recent-10 and recent-50 form;
- H2H and opponent-adjusted signals;
- Elo, Glicko, TrueSkill2, Weng-Lin, and ensemble ratings;
- model feature contributions;
- paper sessions, paper bets, all-call model calls, and decision samples;
- completed match tracking and trusted terminal-score settlement;
- viewer review and contradiction history;
- versioned model, feature-schema, calibration, policy, and code identity;
- run history summaries;
- all-call live-run analytics;
- skipped-decision counterfactual reporting;
- feed, ingestion, stream, settlement, and scraper operations pages;
- legacy player list and player detail surfaces.

### 4.2 Gaps that block the desired product

- Historical run cards summarize paper bets but do not expose the complete all-call analytics for any selected run.
- Detailed analytics are primarily bound to the active session.
- There is no generalized run/date/model/policy cohort query.
- There is no multi-run comparison or shared-intersection comparison.
- There is no historical event-time replay runner.
- Policy variants are not all evaluated against the same frozen call as first-class portfolios.
- Candidate model artifacts are not evaluated as synchronized shadow lanes against one frozen decision opportunity.
- The v3 user workspace has no player database routes.
- The player entity currently stores identity names but not optional sourced profile facts.
- The user view has too much technical prose and too many large cards for rapid live scanning.
- Admin navigation exposes too many operational destinations at the same hierarchy level.
- Metric definitions and trigger explanations are scattered rather than canonical.
- Charts do not share a persistent global cohort or universal drill-down contract.
- Multi-book source, freshness, dispersion, and consensus are not represented as a unified market object.

## 5. Final information architecture

### 5.1 Role gateway

The no-login role gateway remains:

- **User Intelligence**
- **Admin Research & Operations**

Role selection changes presentation and controls, not the underlying evidence.

### 5.2 User navigation

1. **Live** — current live and upcoming matches.
2. **Simulation** — current virtual portfolios and open positions.
3. **Results** — settled calls, portfolio results, and historical scorecards.
4. **Players** — searchable player database and profiles.
5. **Watchlist** — saved players, matches, and price/model alerts. This can ship after the first four but belongs in the final structure.

### 5.3 Admin navigation

1. **Command** — overall posture, active run, important alerts, and next actions.
2. **Runs** — run history, detail, selection, comparison, and annotations.
3. **Replay Lab** — historical time-machine simulations and policy/model comparison.
4. **Model Lab** — artifacts, calibration, factors, hypotheses, and promotion gates.
5. **Data & Operations** — players, identity, feeds, ingest, streams, scraper, settlement, and review.

The current Feeds, Ingest, Streams, Settlement, Review, and Scraper routes remain available as subsections and command-palette destinations, not six persistent top-level tabs.

## 6. Global context and navigation contract

Admin research pages share a sticky **Cohort Bar**:

- Runs: one, multiple, all, or saved collection
- Date/time range
- Model version/family
- Model lane: Champion or selected shadow candidates
- Policy portfolio
- Benchmark
- Prematch/live capture
- Match state
- Trigger/factor family
- Model-probability range
- offered-odds range
- model-market gap range
- agreement/reliability range
- paper/discovery/model-only state
- settlement source/confidence
- player/opponent

### Required behavior

- Filters are encoded in the URL.
- Opening a chart point, player, match, or detailed dashboard preserves the active cohort and time range.
- Browser back returns to the exact scroll, sort, filters, and selection state.
- A visible `Reset cohort` action is always available.
- Active filters collapse into readable chips on narrow screens.
- Users can save, name, duplicate, and delete views locally.
- Every table supports search, sort, column selection, density selection, pinned columns, and CSV export of the current filtered cohort.
- A breadcrumb always shows `Workspace > Page > Run/Cohort > Match/Player`.

This follows proven experiment-tracking and dashboard drill-down patterns: run metadata and artifacts remain attached to an experiment, while time and filter variables carry into detailed views.

## 7. User experience specification

### 7.1 Live lobby

The live page uses a dense two-pane layout:

- **Market board:** flexible main pane.
- **Match drawer:** fixed 420–520 px right pane on desktop; bottom sheet on mobile.

The old large hero is replaced by a compact status ticker:

`12 LIVE · 4 CHAMPION PICKS · 3 DISCOVERY · 18 AWAITING · MODEL 56.2% TODAY · FEED 2s`

#### Match row

Each row shows only scan-critical information:

- live/scheduled/suspended/final state;
- scheduled time or live phase;
- current sets and points when known;
- both players;
- Hard Rock odds for both sides;
- model fair price for both sides;
- model probability for the leaned side;
- book no-vig probability;
- model-minus-market gap;
- price movement arrow and last change age;
- one trust label;
- Champion, Challenger, Discovery, Model Only, or Pass state.

Rows remain sorted live first, then chronologically. User picks can be pinned within their state without breaking time ordering.

#### Row interactions

- Click anywhere: open match drawer.
- Click a price: open the Market section and freeze that quote for inspection; no bet is placed.
- Click trust: open a short evidence popover.
- Click portfolio badge: explain which gates passed or failed.
- Hover/focus movement arrow: show previous price, new price, timestamp, and source freshness.
- Star: add match or player to Watchlist.

### 7.2 Match drawer: the three-question read

The first drawer screen answers:

1. **Call** — who the model believes is more likely to win.
2. **Price** — whether the Hard Rock offer is better or worse than our fair price.
3. **Trust** — how much relevant, verified evidence supports the call.

It contains:

- live scoreboard and server when trustworthy;
- current model probability and interval;
- Hard Rock raw price, implied probability, hold, and no-vig price;
- multi-book no-vig consensus and dispersion;
- price target: the Hard Rock price at which expected value reaches the selected threshold;
- Champion/Challenger/Discovery decisions;
- one-sentence bettor read;
- one-sentence strongest risk;
- feed freshness and market status.

### 7.3 Match sections

The drawer or full match page contains:

1. **Overview** — Call, Price, Trust, score, portfolios, and summary.
2. **Live Story** — score, model, and market movement through time.
3. **Matchup** — form, ratings, H2H, schedule, and opponent quality.
4. **Market** — Hard Rock plus reference books, no-vig consensus, hold, dispersion, and movement.
5. **Why** — all material factor contributions and counterarguments.
6. **Pipeline** — capture, policy, score tracking, settlement, and learning state.

Raw payloads and artifact checksums are admin-only.

### 7.4 Live Story chart

One chart offers three synchronized modes rather than mixing incompatible scales:

- **Probability:** model, Hard Rock no-vig, and market consensus probability.
- **Price:** American or decimal odds by source.
- **Gap:** model minus Hard Rock and model minus consensus.

Annotations:

- every score or set change;
- market suspension/reopen;
- model call freeze;
- each portfolio decision;
- manual viewer grade;
- settlement.

Interactions:

- Drag to scrub time.
- Hover/tap to see score, all prices, model state, freshness, and gates at that moment.
- Click an annotation to open its decision receipt.
- Toggle series from the legend.
- `Reset zoom` always visible after zooming.

### 7.5 Why the model leans

Show a two-sided contribution bridge:

- left pushes toward Player 1;
- right pushes toward Player 2;
- bar length shows contribution magnitude;
- opacity or pattern shows evidence maturity;
- each factor is clickable.

Clicking a factor opens:

- plain-English definition;
- technical name;
- current values for both players;
- how the values were calculated;
- data timestamp and sample size;
- the direction of influence;
- historical directional accuracy and calibration for the active cohort;
- caveat and known failure modes;
- link to the factor's admin evidence page.

The interface never shows only the top trigger when other material factors disagree.

### 7.6 What would change the decision

Each match computes a sensitivity explanation without mutating the model:

- price required to reach minimum EV;
- probability required to pass;
- rating agreement required to pass;
- maximum allowable interval width;
- gate currently closest to passing;
- whether the decision would change under each active Challenger policy.

Example:

> Champion passes because the model is 5.8 percentage points above the market. Balanced would monitor at +105. Discovery includes this matchup in the high-disagreement research bucket at a fixed $1 virtual stake.

### 7.7 User Simulation page

Primary selectors:

- Active run / selected historical run
- Today / 7 days / 30 days / all time / custom
- Portfolio or benchmark

Headline cards:

- bankroll and P&L;
- record and accuracy;
- ROI and uncertainty interval;
- CLV and coverage;
- open exposure;
- current drawdown;
- settled/awaiting/voided;
- sample-confidence label.

Default portfolio tabs:

- Champion
- Balanced
- Discovery
- All Calls
- Hard Rock
- Consensus

Charts:

- equity curve;
- drawdown and recovery;
- open/settled exposure timeline;
- rolling accuracy and calibration;
- price/CLV distribution.

Each chart click filters the result tape below it.

### 7.8 User Results page

The default tape uses sportsbook-history language:

`WIN · Krystian Gaik · model 61.8% · HR -110 · final 3–1 · +$0.91`

Filters:

- portfolio;
- result;
- date/run;
- player;
- odds/probability band;
- trigger;
- prematch/live;
- paper/model-only;
- trusted/manual/unresolved settlement.

Clicking a result opens the immutable decision receipt showing what was known then, not current recomputed data.

## 8. Player database specification

### 8.1 Routes

- `/user/players`
- `/user/players/:playerId`
- `/admin/data/players`
- `/admin/data/players/:playerId`

The user and admin views share the same sourced facts. Admin adds identity, provenance, repair, and recomputation controls.

### 8.2 Player Explorer

Search and filters:

- name and alias;
- minimum match count;
- recent-10/recent-50 win rate;
- rating band and rating system;
- rating uncertainty;
- current form/streak;
- schedule strength;
- data freshness;
- alias/identity health;
- active today/upcoming/live;
- model call history;
- portfolio performance when selected.

Default columns:

- player;
- matches;
- recent 10;
- recent 50;
- all-time record;
- Elo;
- Glicko and RD;
- TS2 and uncertainty;
- schedule strength;
- rating trend;
- last played;
- next/live match;
- identity/data-quality state.

All ranking and sorting shows minimum-sample context. A thin 4–0 record cannot silently outrank a mature 120–60 record.

### 8.3 Player profile

Header:

- canonical name;
- optional sourced country, hand, birth year, and external IDs when legitimately available;
- current ratings and uncertainty;
- recent form strip;
- live/upcoming match;
- follow/watch action;
- last data refresh and identity confidence.

Tabs:

1. **Overview** — recent form, current ratings, strengths, uncertainty, and next match.
2. **Results** — complete paginated match log with opponent, result, score, competition, date, and data source.
3. **Ratings** — Elo, Glicko, TS2, Weng-Lin, uncertainty bands, and change annotations.
4. **Matchups** — searchable opponent table and H2H explorer.
5. **Model History** — every frozen model call involving this player, model probability, market probability, result, and contribution.
6. **Market History** — pregame and live pricing history when available.
7. **Data Quality** — admin-visible aliases, source IDs, merge history, missing fields, and conflicting identities.

### 8.4 Player comparison

Any two players can be compared from search, match drawer, or profile.

Comparison contains:

- recent 10/50 and all-time;
- opponent-adjusted form;
- schedule strength;
- rating systems and uncertainty;
- H2H timeline;
- common-opponent performance;
- set-margin distribution;
- rating trajectories on the same date scale;
- model and market history in their direct matchups;
- data depth and freshness.

Radar charts are optional and secondary because different metrics and uncertainty are difficult to compare responsibly on one normalized polygon.

### 8.5 Player data extensions

Do not overload the canonical `players` row with unsourced guesses. Add sourced, optional facts with provenance:

- `player_profile_fact` — field, value, source, source ID, observed time, confidence, active flag;
- `player_external_identity` — source-specific ID and canonical mapping;
- `player_merge_audit` — source/target player, reason, operator, time;
- `player_metric_daily` — date-bounded rating/form summary for fast charts;
- `player_opponent_aggregate` — refreshable matchup aggregates.

## 9. Multi-book market intelligence

### 9.1 Market source model

Every quote stores:

- source/book ID;
- book display name;
- source type: executable, reference, exchange, consensus;
- event and market canonical IDs;
- side;
- raw odds;
- implied probability;
- paired-market no-vig probability;
- market overround;
- observed time;
- received time;
- freshness and latency;
- market status;
- source event ID;
- ingestion provenance.

### 9.2 Consensus methods

The admin can compare preregistered consensus definitions:

- median no-vig probability;
- equal-weighted mean;
- configurable sharp-book weighted mean;
- trimmed mean excluding stale and extreme quotes;
- Hard Rock-only baseline.

The selected production reference method is versioned. Changing weights creates a new consensus version and never rewrites an old run.

Display:

- center consensus probability;
- range across books;
- standard deviation or robust dispersion;
- number of current contributing books;
- oldest and newest quote age;
- books excluded and why.

### 9.3 Odds ladder

The Market section shows:

| Book | P1 | P2 | Hold | P1 no-vig | Freshness | Availability |
|---|---:|---:|---:|---:|---:|---|

Behavior:

- Hard Rock is pinned first.
- Best price is highlighted informationally.
- Price movement flashes and fades.
- Stale quotes are dimmed and excluded from consensus after the configured TTL.
- Clicking a book opens its price history.
- Reference-only books never use a `Bet` button.

### 9.4 Data-acquisition gate

Before adding a sportsbook source, document:

- permitted access method;
- update cadence;
- market coverage;
- event-ID stability;
- historical availability;
- rate limits and cost;
- jurisdiction/display restrictions;
- retention terms.

The UI and data model can be multi-book-ready before all feeds are purchased or connected.

## 10. Model-lane, portfolio, and counterfactual engine

### 10.1 Evaluation flow

For every immutable decision opportunity:

1. Freeze feature values, prices, score state, identity resolution, and timestamps once.
2. Run the Champion and every enabled shadow model artifact against that identical snapshot.
3. Persist a separate immutable model call for each lane, linked by one opportunity ID.
4. Evaluate the configured policy portfolios independently for each permitted model lane.
5. Evaluate all enabled benchmarks once for the shared opportunity.
6. Persist gate-by-gate outcomes and proposed stake.
7. Never allow one model lane or portfolio's exposure to change another portfolio's decision unless that relationship is explicitly part of the experiment.
8. Settle every linked call, portfolio, and benchmark from the same trusted terminal outcome.

The normal user view shows only the Champion model unless the user explicitly opens `Compare models`. Admin views show all lanes. The official simulation remains Champion + Champion Strict; shadow combinations are research results and are never blended into that record.

### 10.2 Model lane definition

Each model lane versions:

- lane role: Champion or Shadow;
- model artifact and family;
- feature schema and transformation checksum;
- calibration artifact;
- training cutoff and evaluation eligibility;
- runtime resource budget and timeout;
- active dates;
- parent candidate and experiment hypothesis;
- promotion status;
- checksum and notes.

A candidate that times out or lacks a required feature records `NOT_EVALUATED`; it does not silently inherit the Champion prediction. Model comparisons report opportunity overlap and shared-evaluable cohorts.

### 10.3 Portfolio definition

Each portfolio versions:

- name and purpose;
- parent portfolio/version;
- model artifact selector;
- minimum probability;
- minimum edge/EV;
- model-market directional and absolute gap guards;
- odds range;
- rating agreement;
- uncertainty width;
- trigger/factor exclusions;
- capture-state rules;
- stake rule;
- exposure and concurrency limits;
- exploration bucket and quota rules;
- active dates;
- checksum and notes.

### 10.4 Discovery sampling

Discovery uses fixed $1 virtual stakes and predefined strata, not unrestricted leniency.

Candidate strata:

- probability band;
- model-market gap band;
- rating agreement;
- trigger family;
- odds/favorite band;
- prematch/live capture;
- player side/order;
- data depth;
- closest failed Champion gate.

The sampler balances underrepresented strata and records inclusion probability. This permits bias-aware analysis later.

### 10.5 Policy matrix

For any run or call, show:

| Model lane | Policy | Decision | Failed gate | Stake | Result | P&L | Would change at |
|---|---|---|---:|---|---:|---|

This replaces vague “more lenient” comparisons with exact policy behavior.

## 11. Admin Runs workspace

### 11.1 Run Explorer

Each row includes:

- run ID, label, type, state, dates, and duration;
- model, feature schema, calibration, policy set, and code revision;
- calls, resolved calls, and settlement coverage;
- all-call accuracy, Brier, log loss, and ECE;
- Hard Rock and consensus benchmark deltas;
- Champion/Balanced/Discovery record, ROI, CLV, and exposure;
- artifact integrity and telemetry completeness;
- sample-confidence label;
- annotations and promotion state.

Interactions:

- Row click opens run detail.
- Checkboxes select runs for comparison.
- Column header sorts.
- Metric cell click opens the exact supporting cohort.
- Version cell opens artifact/config diff.
- Warning opens the failed integrity gate.

### 11.2 Run Detail

Tabs:

1. **Summary** — run conclusion, identity, coverage, and portfolio scorecards.
2. **Timeline** — calls, accuracy, P&L, calibration, and incidents by time.
3. **Portfolios** — policy comparison and gate funnel.
4. **Calibration** — reliability curves and probability buckets.
5. **Market** — model versus Hard Rock/consensus and CLV.
6. **Signals** — trigger and factor evidence.
7. **Matches** — complete call ledger.
8. **Data Quality** — missing fields, freshness, settlement, identity, and leakage audit.
9. **Configuration** — immutable model/policy/data/code receipts.
10. **Notes** — human annotations and conclusions.

### 11.3 Run Compare

Compare two to eight runs or models.

Modes:

- natural cohort for each run;
- shared intersection cohort;
- fixed historical replay cohort;
- matched probability/odds cohort.

Views:

- metric comparison table;
- parameter diff;
- calibration overlay;
- cumulative return overlay;
- trigger/factor heatmap;
- disagreement matrix;
- player and matchup concentration;
- daily/session stability;
- failure and coverage comparison.

No “winner” badge appears unless the comparison meets registered minimum sample and uncertainty gates.

## 12. Historical Replay Lab

### 12.1 Replay builder

Inputs:

- historical start and end time;
- event competitions and players;
- model artifacts to compare;
- policy portfolios to compare;
- initial bankroll;
- capture rule, such as final quote five minutes before scheduled start;
- live-first inclusion and entry-time rule;
- source books and consensus definition;
- execution book, normally Hard Rock;
- maximum quote age;
- settlement policy;
- playback speed;
- deterministic seed where sampling exists.

### 12.2 Point-in-time integrity

At simulated time `t`, the engine may access only:

- matches completed before `t`;
- rating snapshots effective at or before `t`;
- player aliases/identities known at or before `t`;
- market quotes observed and received before `t`;
- scores observed before `t`;
- model and policy artifacts created and registered before the replay or explicitly supplied as retrospective research candidates.

It may not access:

- final result before terminal time;
- closing price before it occurs;
- ratings rebuilt from future matches;
- corrected identities that would not have been knowable unless the replay explicitly tests a modern-data retrospective scenario.

Two modes are labeled separately:

- **Historical-as-known** — reproduces what the system could know then.
- **Modern-model retrospective** — applies a current candidate to historical point-in-time features. It remains out-of-sample only if the candidate's training cutoff precedes the replay period.

### 12.3 Replay output

Every replay becomes an immutable run with:

- type `HISTORICAL_REPLAY`;
- definition and checksum;
- dataset high-watermarks;
- source coverage report;
- model/policy versions;
- all calls and portfolio decisions;
- deterministic event log;
- final scorecards;
- leakage audit;
- reproducibility status.

It uses the same Run Detail and Compare experience as live runs.

### 12.4 Replay playback

The operator can:

- play at 1×, 10×, 60×, or maximum speed;
- pause at every call, policy placement, market move, start, or settlement;
- scrub to a time;
- inspect the active board as it looked then;
- compare what each model/policy knew and decided;
- branch the replay from a selected time with a cloned policy scenario.

Branching creates a new run; it does not modify the parent.

## 13. Model Lab

### 13.1 Artifact registry

Every candidate shows:

- model family/version;
- Champion/Shadow lane and synchronized opportunity coverage;
- feature schema and checksum;
- training/calibration/validation windows;
- dataset checksum and sample counts;
- parameters and learned weights;
- calibration method;
- benchmark availability;
- swap-invariance result;
- validation and future-run metrics;
- promotion gates and failure reasons;
- related live and replay runs;
- human notes.

### 13.2 Experiment collections

Runs can be grouped into a named experiment, for example:

`Symmetric market candidate · R2.1 vs R3 · Aug 2026`

An experiment records:

- hypothesis;
- registered primary metric;
- secondary metrics;
- minimum sample;
- candidate models and policies;
- evaluation cohort;
- start/end or stop rule;
- conclusion;
- decision and author/time.

### 13.3 Parameter Scenario Studio

Scenario Studio is explicitly counterfactual:

- clone a portfolio or model configuration;
- change one or more parameters;
- show a diff before saving;
- run against selected frozen calls or a Replay Lab cohort;
- compare results;
- save as research candidate;
- promote only through the normal approval gates.

It never edits an active run.

## 14. Chart and metric interaction standard

### 14.1 Every metric

Hover/focus opens a concise definition. Clicking opens a detail sheet containing:

- plain-language meaning;
- exact formula;
- numerator and denominator;
- included/excluded outcomes;
- current cohort and time window;
- sample size and effective sample size;
- uncertainty method;
- benchmark;
- last refresh;
- source fields;
- caveats;
- `View underlying matches` action.

### 14.2 Every chart

Every chart must define:

- the question it answers;
- axes and units;
- included cohort;
- sample count;
- uncertainty where relevant;
- legend toggles;
- click/drill behavior;
- empty, loading, stale, and insufficient-data states;
- accessible tabular alternative;
- export image/data actions where useful.

### 14.3 Canonical chart catalog

| Chart | Question | Click behavior |
|---|---|---|
| Equity curve | How did the portfolio grow or fall? | Filter matches to selected interval |
| Drawdown | How deep and long were losses? | Open drawdown episode and calls |
| Reliability curve | Did stated probabilities occur that often? | Filter to probability bin |
| Edge realization | Did larger claimed edges perform better? | Filter to edge bucket |
| Model-market quadrant | When did model and market agree or conflict? | Open selected quadrant calls |
| Trigger heatmap | Which signals worked across runs/cohorts? | Filter to trigger + run cell |
| Factor bridge | What pushed this call? | Open factor definition/evidence |
| Odds movement | How did price change? | Freeze quote snapshot |
| Rating trajectory | How did player strength/uncertainty change? | Open matches around date |
| H2H timeline | What happened in direct meetings? | Open match receipt |
| Data funnel | Where did calls lose eligibility or truth? | Open blocked stage |
| Portfolio gate funnel | Why did policies act differently? | Filter calls by failed gate |
| Parameter waterfall | What changed between versions? | Open exact config diff |

## 15. Canonical explanation and glossary system

Add a shared metric/factor registry consumed by both backend and frontend.

Each definition includes:

- stable key;
- user label;
- admin/technical label;
- one-sentence definition;
- expanded explanation;
- formula or algorithm version;
- directionality;
- unit and normal range;
- data inputs;
- freshness requirement;
- minimum useful sample;
- known caveats;
- related metrics;
- help link/version.

Initial required definitions:

- raw implied probability;
- no-vig probability;
- market hold/overround;
- fair odds;
- model-market gap;
- expected value;
- closing-line value;
- accuracy;
- Brier score;
- log loss;
- ECE/calibration gap;
- Wilson and cluster-bootstrap interval;
- effective sample size;
- Elo;
- Glicko rating/RD/volatility;
- TrueSkill2;
- Weng-Lin;
- rater ensemble and consensus;
- recent form;
- opponent-adjusted form;
- schedule strength;
- H2H decay;
- rating agreement;
- uncertainty width;
- signal quality;
- every policy gate and decision reason.

## 16. Data and API architecture

### 16.1 New or generalized persisted concepts

- `run_portfolio_definition`
- `run_portfolio_decision`
- `run_model_lane_definition`
- `decision_opportunity`
- `model_call_opportunity_link`
- `run_benchmark_evaluation`
- `run_annotation`
- `experiment_collection`
- `experiment_run_link`
- `replay_definition`
- `replay_event_log`
- `market_book`
- generalized canonical `market_quote`
- `market_consensus_snapshot`
- `metric_definition`
- `player_profile_fact`
- `player_external_identity`
- `player_merge_audit`
- daily player metric aggregates

Existing paper-session, model-call, bet, decision-sample, odds-snapshot, tracked-observation, result, and settlement tables remain the source foundation and should be extended/migrated rather than duplicated without reason.

### 16.2 Research query contract

All research endpoints accept a common cohort object:

- run IDs;
- portfolio IDs;
- date/time range;
- model/policy versions;
- call state;
- probability/odds/edge/gap bands;
- player/opponent;
- trigger/factor;
- capture and settlement state;
- paging, sorting, and selected columns.

Proposed endpoints:

- `GET /api/v3/research/runs`
- `GET /api/v3/research/runs/{runId}`
- `GET /api/v3/research/runs/{runId}/calls`
- `POST /api/v3/research/scorecard/query`
- `POST /api/v3/research/compare`
- `POST /api/v3/research/export`
- `POST /api/v3/replay/definitions`
- `POST /api/v3/replay/{id}/start`
- `GET /api/v3/replay/{id}`
- `POST /api/v3/replay/{id}/branch`
- `GET /api/v3/players`
- `GET /api/v3/players/{id}`
- `GET /api/v3/players/{id}/matches`
- `GET /api/v3/players/{id}/ratings`
- `GET /api/v3/players/{id}/model-calls`
- `GET /api/v3/market/{eventId}`

Large result sets use cursor pagination. Expensive comparison/replay jobs are asynchronous and expose progress, cancellation, and immutable completed output.

### 16.3 Metric computation rules

- One event/call/outcome counts once per model artifact and capture rule.
- Multiple model lanes on the same opportunity are paired evaluations and never counted as independent matches.
- Polling evaluations never inflate statistical sample size.
- Paper policy, discovery, all-call, and benchmarks remain separate cohorts.
- Unresolved and non-binary results never become losses.
- Price metrics require timestamp-valid quotes.
- CLV exposes source, close definition, and coverage.
- Aggregate metrics show both raw and effective/cluster-aware samples.
- Run comparison exposes cohort overlap.
- Every cached aggregate is reproducible from immutable base rows and a metric version.

## 17. Visual system

### 17.1 User theme

- charcoal/near-black canvas;
- deep emerald surfaces;
- warm cream primary text;
- restrained gold for selected prices/targets;
- green/red only for directional change and outcomes;
- rectangular 6–10 px corners for odds and rows;
- 14–18 px corners for panels;
- mono/tabular numerals for scores, prices, probability, time, and P&L;
- compact 48–72 px desktop match rows;
- minimal glass blur;
- no promotional sportsbook banners.

### 17.2 Admin theme

- dark graphite/navy canvas;
- neutral dense panels;
- blue/cyan research accents;
- less card nesting and more connected workspace surfaces;
- persistent filter bar and resizable tables;
- comparison colors remain consistent across every chart;
- operations warning colors distinct from betting outcomes.

### 17.3 Responsive behavior

- Desktop: board + drawer, multi-column research workspace.
- Tablet: board with overlay drawer, collapsible filter bar.
- Mobile: sportsbook-style match list, bottom-sheet detail, sticky section tabs.
- Dense tables become card rows only when semantic columns cannot fit; horizontal scroll is allowed for true comparison grids.

### 17.4 Accessibility

- WCAG 2.2 AA;
- color never the only state indicator;
- keyboard navigation and visible focus;
- reduced motion;
- screen-reader summaries for rapid price changes;
- accessible table alternative for every chart;
- user-configurable odds format;
- timestamps display timezone.

## 18. Alerts and watchlists

Watch targets:

- player appears on board;
- match becomes live;
- Hard Rock reaches target price;
- model-market gap crosses a threshold;
- Champion/Challenger/Discovery state changes;
- material model probability change;
- market consensus dispersion spikes;
- feed becomes stale;
- match settles or enters review.

Each alert records the exact triggering snapshot and links to the preserved context. Initial delivery is in-app/browser only; external notifications are a later opt-in.

## 19. Data quality and responsible presentation

- Display source freshness beside live scores and odds.
- Distinguish `market closed`, `market suspended`, `feed missing`, and `match complete`.
- Never infer terminal truth only because betting closed.
- Make missing and conflicting player identities visible.
- Label simulated returns as hypothetical.
- Never describe a low-sample positive ROI as proven.
- Show uncertainty and sample maturity beside performance.
- Keep stake controls virtual and conservative.
- Include responsible-gambling language appropriate for a betting analysis tool.
- Do not add urgency patterns such as countdown pressure, confetti, loss chasing, or promotional boosts.

## 20. Implementation phases

### Phase 0 — Contract and design freeze

- Approve this information architecture.
- Freeze canonical metric/factor definitions.
- Produce desktop/mobile wireframes for Live, Match, Simulation, Runs, Run Detail, Replay, and Player Profile.
- Define the shared cohort object.
- Define portfolio and benchmark identities.

**Done:** no unresolved page, term, metric, or navigation ownership questions.

### Phase 1 — Research data foundation

- Generalize run analytics by run IDs and dates.
- Add synchronized decision opportunities and Champion/Shadow model-lane identity.
- Add portfolio definitions/decisions and benchmarks.
- Add annotations and experiment collections.
- Add reusable scorecard/comparison queries.
- Add cohort-overlap and telemetry-completeness fields.

**Done:** any historical run and selected run set returns complete all-call, portfolio, market, signal, and integrity analytics.

### Phase 2 — Runs workspace

- Build Run Explorer, Detail, Compare, filters, saved views, and exports.
- Make every metric/chart drillable.
- Separate all-call, portfolio, and learning-eligible cohorts.

**Done:** the final R1 run can be reconstructed in the UI without offline SQL, and two runs can be fairly compared.

### Phase 3 — Parallel model and portfolio engine

- Ship one Champion and two default Shadow model lanes, with support for five simultaneous live lanes.
- Ship Champion Strict, Challenger Balanced, Discovery, All Calls, Hard Rock, and Consensus evaluations.
- Add policy matrix and stratified discovery sampling.
- Preserve one opportunity identity and immutable per-model call identities across all evaluations.

**Done:** one live run reports synchronized model lanes, all portfolios, and all benchmarks without cross-contamination or sample inflation.

### Phase 4 — Multi-book market layer

- Add source adapter contract and market identity normalization.
- Add authorized reference sources as available.
- Add consensus, dispersion, freshness, odds ladder, and history.

**Done:** Hard Rock is clearly executable; reference lines and consensus are timestamped, comparable, and never mislabeled.

### Phase 5 — User sportsbook/trading experience

- Rebuild Live board, ticker, right drawer, Live Story, Why, sensitivity, Simulation, and Results.
- Remove user-facing operational data and raw artifact text.

**Done:** a user can understand a match in seconds and reach full evidence in one or two explicit clicks.

### Phase 6 — Player Intelligence

- Migrate legacy player capabilities into v3.
- Add Player Explorer, Profile, Compare, Model History, and Data Quality.
- Add sourced optional profile facts and identity provenance.

**Done:** every player or opponent mentioned anywhere is clickable into a complete, trustworthy profile.

### Phase 7 — Replay Lab

- Add point-in-time event engine, definitions, playback, branching, leakage audit, and immutable replay runs.

**Done:** an administrator can replay a historical date range and compare multiple model/policy variants reproducibly.

### Phase 8 — Model Lab and scenario studio

- Add experiment collections, artifact comparison, config diff, hypotheses, and promotion gates.

**Done:** every model change is connected to evidence, a frozen candidate, comparison runs, and an explicit decision.

### Phase 9 — Operations consolidation and polish

- Consolidate operational navigation.
- Add watchlists/alerts, command actions, performance tuning, a11y, visual regression, and long-run soak tests.

**Done:** the complete product is navigable, fast, accessible, and ready for a new long simulation.

## 21. Acceptance criteria

### User

- Live matches appear first and scheduled matches follow chronologically.
- Current score, Hard Rock price, fair price, no-vig market, model probability, trust, and portfolio state are visible without scrolling horizontally on common desktop widths.
- Every player and match is clickable.
- No user screen exposes raw payloads or unexplained technical identifiers by default.
- Any trigger or metric can be understood through one click.
- Simulation and Results can switch portfolios, runs, and dates without ambiguous metric windows.

### Admin

- Any run can be opened and fully audited.
- Multiple runs can be selected and compared.
- Natural and shared-intersection cohorts are available.
- Every chart drills to underlying calls while preserving filters.
- Every run exposes model, policy, dataset, code, market, and settlement identity.
- Champion and shadow models are compared on their shared-evaluable opportunity cohort.
- All-call, paper-policy, discovery, and learning-eligible samples are never mixed.
- Player identity and data quality are visible and repairable with audit history.

### Replay

- Repeating a replay with the same definition produces the same checksum and results.
- Leakage tests prove no future result, price, rating, or identity correction enters before simulated time.
- Replays use the same detail and comparison surfaces as live runs.

### Performance and reliability

- Initial user board usable within 1.5 seconds locally.
- 100-row live board updates without visible jank.
- Charts and tables remain usable with 100,000+ call rows through server-side paging/aggregation.
- No active filter or selection is lost on drill-down/back navigation.
- Stale and partial data states are explicit.

## 22. Explicit non-goals

- Real-money wagering or sportsbook account execution.
- Automatic bet placement.
- Claiming profitability from historical or low-sample results.
- In-session automatic model/weight changes.
- Unauthorized sportsbook scraping.
- Copying another sportsbook's brand assets or exact trade dress.
- Social feeds, public tipster leaderboards, parlays, promotions, casino content, or gamification in this release.
- Building every possible chart simply because data exists.

## 23. Research basis

Patterns incorporated into this plan:

- DraftKings: dynamic live prices and explicit live-price behavior — https://sportsbook.draftkings.com/help/how-to-bet-live-bets
- Hard Rock: fast live updates and play-by-play hierarchy — https://www.hardrock.bet/news/get-in-the-game-with-play-by-play-betting/
- OddsJam: multi-book odds screen, movement, saved filters, no-vig odds, market width, alerts, and weighted consensus — https://dev.oddsjam.com/odds-screen and https://oddsjam.com/betting-education/how-to-use-the-oddsjam-positive-ev-tool
- Unabated: vig-free reference line, source latency, best price, synthetic hold, and movement — https://unabated.com/articles/learn-about-the-game-odds-screen
- Pikkit: complete synced history, time/player/league/bet-type analytics, CLV, and live tracking — https://pikkit.com/ and https://pikkit.com/blog/what-does-pikkit-look-like
- Action Network and Bet Labs: customizable dashboards, alerts, systems, custom date ranges, and filterable historic results — https://www.actionnetwork.com/app and https://www.sportsinsights.com/get-bet-labs/
- OddsPortal: bookmaker-level historical movement and odds archive — https://www.oddsportal.com/results/
- ITTF and Sofascore: searchable player profiles, ranking history, recent results, side-by-side comparison, and categorized player statistics — https://www.results.ittf.link/players-profiles and https://www.sofascore.com/football/player/compare
- MLflow: runs organized with metrics, parameters, code versions, datasets, and artifacts — https://mlflow.org/docs/latest/ml/tracking/
- Grafana: persistent dashboard variables, chained filters, and context-preserving drill-down links — https://grafana.com/docs/grafana/latest/visualizations/dashboards/variables/ and https://grafana.com/docs/grafana/latest/visualizations/panels-visualizations/configure-data-links/
- Calibration-focused sports-model evaluation — https://arxiv.org/abs/2303.06021

These products are design references, not evidence that their betting claims are profitable.

## 24. Final product recommendation

The next build should begin with the research data foundation and Runs workspace, not a visual reskin. Once every run, call, portfolio, benchmark, and filter can be queried consistently, the new visual system can present trustworthy information instead of reorganizing incomplete contracts.

The default operating set should be:

- three simultaneous model lanes by default: one Champion and two Shadows;
- support for five live model lanes, with larger candidate batches handled in Replay;
- three policy portfolios: Champion, Balanced, Discovery;
- three benchmarks: All Model Leans, Hard Rock, Market Consensus;
- up to five additional admin-defined Challenger policies;
- Hard Rock as the executable price;
- other books as timestamped reference information;
- one shared call/outcome ledger;
- a point-in-time Replay Lab;
- a first-class Player Intelligence database;
- run and model comparison that always exposes sample, uncertainty, cohort, and data quality.

That creates a product capable of answering the questions that matter:

- Is the model predicting winners better than the market?
- Is it calibrated?
- Does any claimed edge survive Hard Rock's price and margin?
- Which policies select useful subsets without merely starving the sample?
- Which factors help across independent runs rather than one hot segment?
- What did the system know at the exact moment it decided?
- Can the result be reproduced on a historical date and a new future run?

If TTLElite can answer those questions clearly, quickly, and honestly, it becomes meaningfully different from both a sportsbook and a generic analytics dashboard.
