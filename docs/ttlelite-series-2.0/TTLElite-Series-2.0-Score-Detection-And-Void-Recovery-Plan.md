# TTLElite Series 2.0 Score Detection And Void Recovery Plan

## Purpose

This document is the current recovery plan for the `void spike` problem.

It is narrower than the full 2.0 master plan and broader than the earlier run-specific settlement postmortem.

Its job is to answer four questions clearly:

1. What is happening in the current run?
2. What score/result detection paths already exist in the code?
3. What real outside alternatives exist right now?
4. What exact implementation order gets us from `void-heavy and brittle` to `settlement-truth-first and resilient`?

## Current Diagnosis

The current problem is not just one bad timeout.

It is a combination of:

- incomplete finish-signal coverage from the current primary feed
- over-reliance on a single upstream live-score source
- no second-class-but-fast public score source to rescue disappeared matches
- official archive latency and ambiguity
- bet identity drift risk when same players appear again
- void logic that eventually gives up even after rich partial score history exists

## Evidence From The Current Local Run

This section is based on a safe read-only inspection of the current H2 data while the run stayed alive.

### Current session state

- Session id: `59`
- Created at: `2026-04-05 01:09:40`
- Total bets: `22`
- Losses: `3`
- Voids: `6`
- Current bankroll: `523.54`

### Settlement mix is currently weak

Closed-bet reasons in session `59`:

- `VOIDED_MISSING_BOARD_TIMEOUT`: `6`
- `SETTLED_FROM_LAST_SCORE_HEURISTIC`: `3`
- `OFFICIAL_RESULT`: `0`
- `DATABASE_RESULT`: `0`
- `TARGETED_COMPLETION`: `0`
- `SCORE_BACKED`: `0`

This means the current session is mostly falling through the entire stack and ending in either:

- a heuristic finish guess, or
- a timeout void

That is the opposite of the intended 2.0 source hierarchy.

### The voided bets were not empty

The voided bets had real score timelines. Example patterns from `TRACKED_MATCH_OBSERVATION`:

- `0-2 (10-10)` `LIVE_MID`
- `0-2 (6-8)` `LIVE_MID`
- `0-2 (3-8)` `LIVE_MID`
- `2-1 (8-5)` `LIVE_MID`
- `2-2 (7-7)` `LIVE_LATE`

So these were not “never observed” matches.

They were matches with substantial live progression that never received a trustworthy finish signal.

### The current session has no after-close completion evidence at all

Observation breakdown for session `59`:

- `SCORE_FEED`, `tracked_after_close=false`, `displayed=true`, `resulted=false`, `match_completed=false`: `301`
- `MARKET_BOARD`, `tracked_after_close=false`, `displayed=true`, `resulted=false`, `match_completed=false`: `22`

There are currently:

- `0` observations with `tracked_after_close=true`
- `0` observations with `resulted=true`
- `0` observations with `match_completed=true`

That is a critical finding.

It means the current live stack is not seeing any true after-close completion evidence in this run.

### We already have a stale-open graveyard forming

Open bets in session `59` with `missing_board_count >= 20`: `8`

Examples:

- `1079` `Filip Kujawa vs. Karol Sulkowski` -> `2-2 (7-7)` `LIVE_LATE` `missing_board_count=115`
- `1077` `Patryk Lewandowski vs. Kuzmicz, Jakub` -> `1-2 (11-12)` `LIVE_MID` `missing_board_count=114`
- `1076` `Amirreza Abbasi vs. Maciej Nowalinski` -> `1-2 (4-9)` `LIVE_MID` `missing_board_count=92`
- `1078` `Jakub Glanowski vs. K. Makowski` -> `2-0 (10-10)` `LIVE_MID` `missing_board_count=86`

This is important because it predicts more future voids unless the source ladder changes.

### Identity drift is happening

At least one bet accumulated more than one `sourceFeedEventId` over time:

- Bet `1070` had `2` distinct feed ids in `TRACKED_MATCH_OBSERVATION`

That means an older open bet can get reattached to a later same-player match.

This is a stop-ship class issue for settlement truth.

### Official/database coverage is missing for most voided bets

For `5` of the `6` voided bets, the local DB had:

- no feed-identity match
- no completed same-pair match after placement

For the remaining one:

- there were multiple same-day player-pair matches, so archive/database matching was ambiguous

That means the current void problem is mostly:

- source coverage failure first
- ambiguity second

not just one bad resolver branch.

## What The Code Already Does Today

The current code already has a larger score/result architecture than the UI alone suggests.

### Existing internal score/result paths

1. `Hard Rock GraphQL market feed`
   - file: `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
   - purpose: priceable board rows

2. `Hard Rock GraphQL scoreboard feed`
   - file: `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
   - purpose: live score rows beyond visible odds rows

3. `Hard Rock targeted GraphQL scoreboard by event ids`
   - file: `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
   - method: `fetchScoreboardByEventIds(...)`
   - purpose: keep polling tracked matches by sportsbook event id

4. `Hard Rock public tree scoreboard`
   - file: `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
   - purpose: secondary discovery and public score surface
   - current weakness: many TTL competition nodes are `count-only`

5. `Tracked observation timeline`
   - files:
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/TrackedMatchObservation.java`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
   - purpose: persist observation history, not just latest row

6. `Official TT Series recent-result refresh`
   - file: `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/TtSeriesScraper.java`
   - method: `refreshRecentOfficialResults(...)`
   - purpose: scrape recent official result posts into `MATCHES`

7. `Database result confirmation`
   - files:
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/repository/MatchRepository.java`
     - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
   - purpose: confirm finished matches already in the local DB

8. `Last-score heuristic fallback`
   - file: `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
   - reasons:
     - `SETTLED_FROM_LAST_SCORE_HEURISTIC`
     - `SETTLED_FROM_NEAR_FINISH_LAST_SCORE`
     - `SETTLED_FROM_STALE_ONBOARD_SCORE`

9. `Timeout void`
   - file: `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
   - reason: `VOIDED_MISSING_BOARD_TIMEOUT`

### What is missing in practice

The current session shows that these paths exist in code but are not producing enough decisive finish evidence.

The stack is especially weak when:

- the match disappears before `matchCompleted/resulted`
- no official TT Series post exists yet
- the same players can appear multiple times in a day
- the current row matcher drifts onto a later match

## External Score And Result Alternatives Verified During Research

These are real currently available public surfaces worth incorporating into the architecture.

### Tier 1: Official / primary / highest trust

1. `Hard Rock GraphQL events feed`
   - current internal primary sportsbook score source
   - link: [https://app.hardrock.bet/java-graphql/graphql?type=events](https://app.hardrock.bet/java-graphql/graphql?type=events)

2. `TT Series official result posts`
   - examples:
     - [https://www.tt-series.com/1909-result-1-04-2026-night-tournament-osp/](https://www.tt-series.com/1909-result-1-04-2026-night-tournament-osp/)
     - [https://www.tt-series.com/category/turnieje/](https://www.tt-series.com/category/turnieje/)
   - current role: official archive confirmation
   - important nuance: some posts appear as schedule/result shells before full results are usable

3. `TT Series official dates schedule`
   - link: [https://www.tt-series.com/dates/](https://www.tt-series.com/dates/)
   - role: schedule sanity and tournament timing context

4. `TT Series official player pages`
   - search evidence shows dedicated player history pages such as:
   - [https://www.tt-series.com/player/?player=Kujawa+Filip](https://www.tt-series.com/player/?player=Kujawa+Filip)
   - role: official per-player recent-match history
   - value: stronger than post-only scraping for same-day player lookups

5. `TT Series official H2H pages`
   - search evidence shows dedicated H2H pages such as:
   - [https://www.tt-series.com/h2h/?player_a=Karol+Sulkowski&player_b=Filip+Kujawa](https://www.tt-series.com/h2h/?player_a=Karol+Sulkowski&player_b=Filip+Kujawa)
   - role: official head-to-head confirmation surface
   - value: lets us confirm same-pair results without relying only on archive post order

### Tier 2: Strong public mirror sources

6. `Sofascore TT Elite Series tournament and match pages`
   - tournament: [https://www.sofascore.com/table-tennis/tournament/poland/tt-elite-series/19041](https://www.sofascore.com/table-tennis/tournament/poland/tt-elite-series/19041)
   - match search evidence exists for current TT Elite pairings, for example:
     - `Lukasz Oracz vs Oliver Vincenec`
   - value:
     - live scores
     - set-by-set view
     - odds context
     - structured match pages

7. `AiScore TT Elite / head-to-head pages`
   - example:
     - [https://www.aiscore.com/head-to-head/table-tennis/filip-kujawa-vs-jakub-glanowski](https://www.aiscore.com/head-to-head/table-tennis/filip-kujawa-vs-jakub-glanowski)
   - value:
     - recent player-pair result history
     - backup public results surface

### Tier 3: Lower-trust public mirrors and sportsbook mirrors

8. `Stake TT Elite Series pages`
   - [https://stake.com/sports/table-tennis/international/tt-elite-series/](https://stake.com/sports/table-tennis/international/tt-elite-series/)
   - research confirmed the page exposes live set/point score examples and mentions live YouTube streaming
   - role: weak scoreboard mirror, not settlement truth by itself

9. `BsportsFan and similar score mirrors`
   - search evidence shows TT Elite Series result pages and match pages
   - role: low-trust tertiary corroboration only

## Core Bugs To Fix

### Bug 1: Completion blindness

We can watch a match deeply and still never see:

- `resulted=true`
- `matchCompleted=true`
- an official result row

That forces the system into heuristic or void.

### Bug 2: Identity drift

An open bet can absorb a different `externalEventId` or `sourceFeedEventId` later.

That means:

- same-player repeat matches can hijack older bets
- later observations can contaminate settlement truth

### Bug 3: Source monoculture

The system is still overly dependent on:

- one sportsbook feed
- one official archive style

When both are temporarily incomplete, voids spike.

### Bug 4: Official-result latency and ambiguity

The current TT Series post scraper is helpful, but it is:

- post-based
- sometimes shell-like before scores are ready
- not unique enough for same-player repeat days

### Bug 5: Stale open bets do not enter a stronger recovery workflow

Once a bet becomes a long-running stale open:

- it increments `missing_board_count`
- it may wait a long time
- it still often ends in void

There is no dedicated `stale live recovery` workflow with second-source escalation.

### Bug 6: Heuristic settlement is still too prominent when evidence is incomplete

Heuristic settlement exists for good reasons, but it should be:

- bounded
- explicitly second-rate
- excluded from learning unless later confirmed

### Bug 7: Void is treated as the end of recovery rather than the end of all evidence options

Right now void is still too close to:

- “we stopped seeing this in the main source”

instead of:

- “all stronger score/result evidence paths were exhausted”

## Target Architecture

We should move to a five-layer evidence model.

### Layer 1: Primary live sportsbook event evidence

Purpose:

- odds
- live scores
- event state
- match-state details

Sources:

- Hard Rock GraphQL event feed
- Hard Rock targeted event-id query

### Layer 2: Event identity continuity

Purpose:

- keep a bet attached to the same real-world match

Identifiers:

- sportsbook `externalEventId`
- `sourceFeedEventId`
- competition id
- normalized player pair
- start-time bucket

Key rule:

Once a bet has strong identity, later rows with a different identity do not overwrite it automatically.

### Layer 3: Official structured confirmation

Purpose:

- official result confirmation with better pair-level specificity

Sources:

- TT Series official player pages
- TT Series official H2H pages
- TT Series official result posts

### Layer 4: Public mirror confirmation

Purpose:

- live/result rescue when official structured confirmation is not yet available

Sources:

- Sofascore match/tournament pages
- AiScore match/H2H pages
- optional tertiary mirrors

### Layer 5: Heuristic and void as last resort only

Purpose:

- avoid indefinite hanging bets

Rules:

- heuristic only after stronger paths were explicitly attempted
- timeout void only after full recovery ladder is exhausted

## Implementation Plan

## Phase 1: Freeze Match Identity

Goal:

- stop old bets from drifting onto later matches

Changes:

1. Add an explicit `identity lock` state to `PaperTradeBet`
2. When a bet first gets both:
   - `externalEventId`
   - and/or `sourceFeedEventId`
   mark it as identity-locked
3. After identity lock:
   - do not overwrite `externalEventId`
   - do not overwrite `sourceFeedEventId`
   - do not overwrite `startTimeIso`
   unless a dedicated resolver proves same-event continuity
4. Tighten `findCurrentRowForBet(...)`
   - pair-only matching becomes lower confidence
   - once identity exists, loose pair matching cannot replace identity rows
5. Add diagnostics for `identity drift attempted`

Primary files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/PaperTradeBet.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/TrackedMatchObservation.java`

## Phase 2: Build A Multi-Source Score Recovery Ladder

Goal:

- do not rely on one sportsbook feed to finish the match

New service:

- `TrackedMatchRecoveryService`

Responsibilities:

1. Try current Hard Rock targeted event-id query
2. If no finish signal:
   - query broader Hard Rock scoreboard sweep
   - search by `sourceFeedEventId` across observed rows
3. If still incomplete:
   - query TT Series official player page
   - query TT Series official H2H page
4. If still incomplete:
   - query Sofascore match/tournament lookup
5. If still incomplete:
   - query AiScore H2H/match mirror

Important design choice:

This service should return an `evidence bundle`, not just one chosen winner.

Primary files:

- new service under `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/`
- new scraper helpers under `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/`

## Phase 3: Expand Official TT Series Scraping Beyond Result Posts

Goal:

- make the official layer more specific and less delayed

Add:

1. official player-page scraper
2. official H2H-page scraper
3. schedule-page scraper for dates/time slot context

Why:

- result posts alone are too coarse
- player/H2H pages give another official way to match same-day player pairs

Primary files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/TtSeriesScraper.java`
- likely split into smaller dedicated scrapers:
  - `TtSeriesResultPostScraper`
  - `TtSeriesPlayerHistoryScraper`
  - `TtSeriesH2HScraper`
  - `TtSeriesScheduleScraper`

## Phase 4: Introduce Public Mirror Score Sources

Goal:

- rescue matches that vanish from Hard Rock before completion

Add:

1. `SofascoreScoreMirror`
2. `AiScoreResultMirror`

Use cases:

- ongoing live set/point continuation
- recent final-result confirmation
- contradiction detection

Guardrails:

- public mirrors do not auto-settle by themselves when they contradict stronger evidence
- they do raise or lower confidence in other settlement candidates

## Phase 5: Replace Single-Candidate Settlement With Evidence Ranking

Goal:

- stop using one flat resolver branch for all cases

Introduce:

- `SettlementEvidence`
- `SettlementEvidenceBundle`
- `SettlementDecisionEngine`

Each evidence record should capture:

- source
- source tier
- source trust score
- observed-at time
- event ids
- player pair
- score
- completion flag
- contradiction flags
- ambiguity score

Decision outcomes:

- `AUTO_SETTLE_CONFIDENT`
- `HOLD_FOR_RECOVERY`
- `HOLD_FOR_REVIEW`
- `VOID_AFTER_EXHAUSTION`

## Phase 6: Add A Stale-Live Recovery State

Goal:

- stop sending stale rich-score bets straight toward void

New explicit state:

- `STALE_LIVE_RECOVERY`

Entry conditions:

- rich score history exists
- no decisive finish signal yet
- missing-board count exceeded early threshold

Behavior:

- trigger multi-source recovery ladder
- increase recheck cadence
- do not learn from unresolved rows
- do not void until recovery attempts are exhausted

## Phase 7: Redesign Void Policy

Goal:

- make void the final state of a documented exhaustion process

Void should require all of:

1. primary sportsbook score source exhausted
2. official TT Series player/H2H/post sources exhausted
3. mirror sources exhausted
4. no decisive score state
5. no trustworthy archive candidate
6. no identity-safe database result

Void reasons should be split:

- `VOID_NO_SOURCE_COVERAGE`
- `VOID_IDENTITY_AMBIGUOUS`
- `VOID_CONTRADICTORY_EVIDENCE`
- `VOID_EXHAUSTED_WITHOUT_FINISH`

## Phase 8: Strengthen Operator Forensics

Goal:

- make every void and every settlement explain itself

Add to Live Studio / Operations:

1. per-bet evidence timeline
2. last attempted recovery sources
3. contradiction banner
4. identity drift warning
5. void reason detail
6. score-source health counters:
   - percent of bets reaching completion signal by source
   - percent rescued by mirrors
   - percent voided after full exhaustion

## Phase 9: Protect Learning From Bad Labels

Goal:

- stop training on weakly resolved outcomes

Rules:

- do not learn from `VOID_*`
- do not learn from ambiguous official-result settlements
- learn from heuristics only if later confirmed
- prefer only:
  - `TARGETED_COMPLETION`
  - decisive scoreboard completion
  - official structured confirmation with low ambiguity

## Detailed Source Ranking

Recommended ranking for settlement truth:

1. Hard Rock targeted event with `matchCompleted/resulted`
2. Hard Rock targeted event with decisive final set score
3. Hard Rock broad event row with same locked identity and explicit completion
4. TT Series official player/H2H confirmation with unique same-day pairing
5. TT Series official result post with unique same-day pairing
6. Sofascore mirror corroborating official/player identity
7. AiScore mirror corroborating official/player identity
8. identity-safe database result
9. heuristic near-finish
10. timeout void

## What We Should Not Do

1. Do not just increase timeout minutes and hope.
2. Do not just relax official-result ambiguity rules.
3. Do not let pair-name fallback overwrite locked event identity.
4. Do not learn from void-heavy runs without source-quality screening.
5. Do not treat a result-post shell as a finished official result.

## Replay And Validation Matrix

We should add replay tests for:

1. hidden finish signal never arrives, but official player page confirms result
2. hidden finish signal never arrives, but Sofascore match page confirms result
3. same players play again later and old bet must not drift to new feed id
4. same-day duplicate official matches exist and settlement must stay unresolved
5. official result post exists but row has blank result cells
6. public mirror contradicts official source and bet must hold for review
7. rich stale late score enters `STALE_LIVE_RECOVERY` instead of voiding immediately
8. mirrored completion later confirms a previous heuristic settlement

## Success Criteria

We should consider this recovery complete when a fresh live session achieves all of:

1. `targeted completion + score-backed + official structured` together account for the large majority of settlements
2. timeout void rate drops materially below the current spike
3. stale open bets no longer accumulate huge `missing_board_count` without escalation
4. zero identity-drift incidents in tracked observations
5. ambiguous same-day pairings hold for recovery or review instead of mis-settling
6. adaptive learning excludes weakly resolved rows by default

## Recommended Execution Order

1. Freeze match identity
2. Build stale-live recovery state
3. Add TT Series player/H2H official scrapers
4. Add Sofascore mirror integration
5. Add evidence-bundle settlement engine
6. Redesign void policy
7. Add operator forensics and health counters
8. Tighten learning gates

## Immediate Recommendation

Before more model work, we should execute this plan in this order:

1. `Identity freeze`
2. `Official player/H2H expansion`
3. `Sofascore mirror rescue`
4. `Evidence-bundle settlement engine`

That is the shortest path to reducing voids without reintroducing wrong-winner bugs.
