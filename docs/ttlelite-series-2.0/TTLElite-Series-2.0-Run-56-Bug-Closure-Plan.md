# TTLElite Series 2.0 Run 56 Bug Closure Plan

## Current Implementation Status

- Phase 1 is now implemented in code.
- Non-feed-identity archive/database settlements are blocked when candidate selection is ambiguous.
- Feed-identity settlement now refuses conflicting duplicate candidates instead of silently picking one.
- Strong late live-score leaders now block later-date archive auto-settlement when the archive winner conflicts.
- Replay coverage now includes same-day duplicate official results and contradictory later-date archive candidates.

## Purpose

This document captures what the current live run is teaching us, which bugs look real versus cosmetic, and the exact closure plan needed to finish the remaining 2.0 reliability work.

This is intentionally narrower and more operational than the broader 2.0 planning set. It is meant to turn the current run into an actionable bug-fix sequence.

## Run Snapshot

Observed initially at approximately `2026-04-04 23:55 EDT`, then re-checked through approximately `2026-04-05 00:02 EDT`.

- Active session: `56`
- Initial snapshot:
  - total bets: `16`
  - wins: `6`
  - losses: `1`
  - open bets: `9`
  - last sync at: `2026-04-04T23:54:17.533094`
- Later watch snapshot:
  - total bets: `17`
  - wins: `7`
  - losses: `1`
  - open bets: `9`
  - last sync at: `2026-04-05T00:01:19.033256`

Current integrity snapshot from `/api/live-studio/integrity`:

- `trackedObservations=121`
- `boardObservations=14`
- `scoreFeedObservations=107`
- `trackedAfterCloseObservations=0`
- `scoreBackedSettlements=0`
- `targetedCompletionSettlements=0`
- `officialResultSettlements=6` at first read, `7` on mid-watch, `8` on later watch
- `databaseSettlements=0`
- `heuristicSettlements=0`
- `voidedSettlements=0`

## Most Important Finding

The current run is still relying almost entirely on `OFFICIAL_RESULT` settlement instead of score-backed settlement.

That alone is not automatically wrong, but in this run it correlates with a stronger problem:

**the official-result resolver appears able to attach a same-player historical match that is not the actual live match we observed.**

That is now the main remaining launch-risk bug.

## Evidence From Run 56

### 1. Official result is dominating settlement

From the live endpoints during this run:

- initial settled rows checked: `7`
- later settled rows checked: `8`
- rows settled via `OFFICIAL_RESULT`: `100%`
- rows settled via score-backed live path: `0`

This means the score continuity work is collecting observations, but the settlement engine is still not converting enough of those observations into trusted direct settlements.

### 2. The current completed-match log is useful, but not enough by itself for forensics

Cross-checking:

- `/api/live-studio/settled-tape?limit=100`
- `/api/live-studio/completed-matches?days=10&limit=400`

At one earlier point in the run, one control row did line up cleanly:

- Bet `1050`
- `resultMatchId=70057`
- `/api/live-studio/completed-matches?days=10&limit=500` showed:
  - `winnerName=Arkadiusz Mugowski`
  - `pickStatus=WON`

However, during later watch passes the UI-oriented completed-match log no longer gave us a reliable enough surface to prove result-match absence for every settlement.

That means:

- the completed-match log is a good operator view
- it is not a sufficient forensic truth source on its own
- it should support diagnosis, not define it

Implication:

- we should not call a settlement wrong only because a `resultMatchId` is absent from the current completed-match endpoint window
- the stronger evidence comes from the live score timeline plus the archive-candidate resolver behavior

### 3. Settled rows that still deserve investigation

- Bet `1052` -> `resultMatchId=65225`
- Bet `1051` -> `resultMatchId=65474`
- Bet `1048` -> `resultMatchId=65240`
- Bet `1047` -> `resultMatchId=65970`
- Bet `1046` -> `resultMatchId=66910`
- Bet `1053` -> `resultMatchId=61052`

Rows that currently look more plausible from timeline direction and should not be over-labeled without deeper proof:

- Bet `1049`
  - picked side: `Damian Fira`
  - last observed score: `1-2 (7-10)`
  - this is directionally consistent with a `WON` result for player 2

### 4. One suspicious row strongly suggests wrong-match attachment

Example:

- Bet `1046`
- Event: `M. Czernik vs. M. Marchlewski`
- Picked side: `Mateusz Czernik`
- Final status: `WON`
- Stored `winnerPlayerId=229`
- Stored `resultMatchId=66910`
- Last observed timeline score: `0-2 (6-10)`

Timeline evidence for bet `1046` shows:

- player1 = `Mateusz Czernik`
- player2 = `Marcin Marchlewski`
- score feed progressed through:
  - `0-0`
  - `0-1`
  - `0-2`
- no tracked completion signal
- no score-backed settlement

A status of `WON` for player1 is very hard to reconcile with the final observed live score trajectory unless the official result matched a different same-player historical match.

### 5. Another suspicious row has the same pattern

Example:

- Bet `1051`
- Event: `P. Staniszewski vs. K. Makowski`
- Picked side: `Kacper Makowski`
- Status: `WON`
- Last observed score: `2-1 (8-5)`
- `resultMatchId=65474`

The same shape repeats:

- official result selected
- live score not obviously final for the winning side
- same-player same-day archive ambiguity remains possible

### 6. A new suspicious row appeared during the extended watch

Example:

- Bet `1053`
- Event: `Karol Sulkowski vs. Arkadiusz Mugowski`
- Picked side: `Karol Sulkowski`
- Status: `WON`
- Last observed score: `1-2 (7-7)`
- `resultMatchId=61052`

This row matters because it arrived after the first review pass and followed the same broader pattern:

- still no score-backed settlement
- settlement fell through to `OFFICIAL_RESULT`
- live score was not in a clearly terminal, high-confidence state for the picked side

That makes the problem look systemic rather than isolated.

### 7. The current run is not using tracked-after-close logic at all

This run currently shows:

- `trackedAfterCloseObservations=0`
- `targetedCompletionSettlements=0`

That means the current problem is not primarily “after-close score disappeared.”
It is currently “visible/live match observed, but settlement still resolves through the weaker archive path.”

### 8. Positive control: live tracking labels stayed healthy during the watch

During the extended watch:

- visible scoring rows stayed in `OPEN_SCORE_VISIBLE`
- upcoming rows stayed in `OPEN_PENDING_SCORE`
- no false `trackedAfterClose` labels appeared

That is important because it narrows the remaining bug cluster:

- open-state labeling improved
- live observation improved
- settlement truth is still the weak link

## Root Cause Hypothesis

The primary likely bug is in official-result candidate resolution, not in the basic score parser or the open-bet tracking-state logic.

### Strong evidence for that conclusion

The score timelines for suspicious bets are internally coherent:

- player order is stable
- score orientation looks consistent
- phases advance normally
- open-state labels stayed sane during the watch

The weaker part is the archive/result attachment logic.

## Code Paths Most Likely Involved

### Official result resolution fallback

`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`

- `resolveOfficialResultSettlementCandidate(...)`
- `findOfficialResultSettlementCandidate(...)`
- `resolveFeedIdentitySettlementCandidate(...)`
- `selectBestSettlementCandidate(...)`

### Why this path is risky today

When feed identity is not available on the archive side, settlement falls back to:

- player pair match
- date window
- loose candidate scoring

Current candidate selection score is too thin:

- absolute date distance
- simple before/after penalty
- tie-break by later id

It does **not** currently incorporate:

- sportsbook scheduled start time proximity
- last observed live phase
- last observed score orientation
- contradiction between archive winner and last observed live state
- confidence degradation when multiple same-player same-day matches exist
- “do not auto-settle” handling when ambiguity is high

### Specific code weakness

`selectBestSettlementCandidate(...)` currently chooses the best candidate mostly by date proximity:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`

That is not enough when the same players can appear more than once on the same day.

## Bug Taxonomy

### Bug A: Official-result same-player collision

Symptoms:

- settlement source = `OFFICIAL_RESULT`
- result match id does not align with the recent completed log
- status may contradict the last observed live score direction

Impact:

- highest severity
- directly corrupts win/loss truth
- contaminates adaptive learning
- contaminates trigger ROI and calibration

### Bug A1: Official-result is overused even when live score evidence is rich

Symptoms:

- timeline data is dense
- score-backed settlements remain at `0`
- archive is still the dominant successful settlement source

Impact:

- pushes the system into the weakest available truth path too often
- raises wrong-match risk even when live evidence was available

### Bug B: Score-backed settlement is underutilized

Symptoms:

- many rich score observations
- zero score-backed settlements in this run

Impact:

- pushes too much responsibility onto weaker archive matching
- delays settlement
- increases wrong-match risk

### Bug C: No contradiction guard before official-result settlement

Symptoms:

- live timeline can imply one side is losing badly
- archive can still settle the opposite side without operator warning

Impact:

- silent wrong settlement
- hard to detect until after the fact

### Bug D: No ambiguity score / review queue for archive matches

Symptoms:

- same-player same-day pairings are auto-resolved as if unambiguous

Impact:

- overconfident settlement behavior

### Bug E: Learning system will trust bad settlements

Symptoms:

- trigger ROI, calibration, adaptive regime tuning, and reliability stats all consume these outcomes

Impact:

- one bad settlement is not just one bad result
- it distorts the whole value engine

### Bug F: Forensic operator surfaces are still too shallow

Symptoms:

- completed-match log is capped and UI-oriented
- candidate archive evidence is not exposed per settlement

Impact:

- slows debugging
- makes it harder to distinguish a truly bad settlement from a merely suspicious one

## Closure Plan

### Phase 1: Stop wrong official-result settlements

Goal:

- make wrong-match official settlements impossible before doing anything else

Tasks:

1. Introduce an archive-settlement ambiguity score
2. Refuse automatic `OFFICIAL_RESULT` settlement when:
   - same-player pair has multiple candidates in the valid window
   - no feed identity match exists
   - no trustworthy time alignment exists
3. Add a new settlement state/reason:
   - `AWAITING_OFFICIAL_DISAMBIGUATION`
4. Add a contradiction guard:
   - if archive winner materially conflicts with the late live score trajectory, do not auto-settle
5. Log candidate ranking details for every archive settlement

Primary files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/PaperTradeBet.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/dto/PaperTradeBetDto.java`

Acceptance:

- no archive settlement occurs unless candidate confidence clears a strict threshold
- ambiguous archive candidates remain open or go to review, not auto-settled

### Phase 2: Promote score-backed settlement ahead of archive matching

Goal:

- settle more matches directly from observed score evidence

Tasks:

1. Revisit `determineWinnerFromScore(...)` thresholds for visible live matches
2. Expand decisive-score recognition when:
   - score is effectively final
   - phase is late
   - observation confidence is high
3. Promote targeted completion logic earlier and more often
4. Persist richer score detail where available and use it in final-state checks
5. Add a “score evidence quality” field per bet

Primary files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/TrackedMatchObservation.java`

Acceptance:

- score-backed settlements become a meaningful share of settled rows
- archive settlement becomes a fallback, not the dominant path

### Phase 3: Build forensic settlement diagnostics

Goal:

- make every suspect settlement explainable immediately

Tasks:

1. Add per-settlement evidence breakdown:
   - selected candidate match id
   - candidate date
   - player-set confidence
   - feed identity match or not
   - archive confidence
   - contradiction flags
2. Add a “Settlement Review” section in Live Studio or Operations
3. Flag suspicious settled rows automatically:
   - archive settlement not in recent completed matches
   - archive winner conflicts with late score direction
   - multiple same-day candidate matches existed

Primary files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/LiveOddsPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/AdminPage.tsx`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/dto/LiveStudioIntegrityDto.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`

Acceptance:

- operator can see why a settlement happened and whether it was high or low confidence

### Phase 4: Protect adaptive learning from bad settlements

Goal:

- bad settlement evidence must not poison the model

Tasks:

1. Do not feed ambiguous archive settlements into adaptive learning
2. Add a `learningEligible` flag to settlement evidence
3. Exclude low-confidence settlement classes from:
   - trigger ROI
   - calibration
   - regime tuning
4. Add metrics for:
   - trusted settled sample count
   - excluded settled sample count

Primary files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`

Acceptance:

- only trusted settlement classes influence model adaptation

### Phase 5: Improve forensic settlement tooling

Goal:

- make every suspicious settlement inspectable from one operator surface

Tasks:

1. Add a dedicated settlement-evidence endpoint by bet id
2. Expose:
   - chosen archive candidate
   - alternative candidates
   - candidate score/rank
   - feed identity match status
   - contradiction flags
3. Add a non-UI-capped operator view for recent result matches tied to current-session bets

Primary files:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/LiveStudioController.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/web/src/pages/AdminPage.tsx`

Acceptance:

- a suspicious settlement can be explained without shell access or DB inspection

## Test Plan For Closure

### Required replay tests

Add explicit tests for:

1. same-player same-day duplicate matches with different winners
2. archive candidate exists but conflicts with late score direction
3. feed identity absent, multiple player/date candidates exist
4. feed identity present, candidate disambiguates correctly
5. visible live score reaches effectively decisive state before archive appears
6. archive candidate older than current run date but still same player pair
7. live score strongly favors one side but archive candidate says the opposite
8. multiple same-day archive candidates exist and none has feed identity

Primary test file:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java/com/ttl/tabletennis/service/PaperTradingServiceTests.java`

### Required runtime checks

During live monitoring:

1. `/api/live-studio/integrity`
   - verify score-backed settlements increase
   - verify official-result share falls
2. `/api/live-studio/settled-tape`
   - verify no suspicious archive match ids are being attached
3. `/api/live-studio/completed-matches`
   - verify picked rows align with archive-attached result ids

## Recommended Execution Order

1. Fix `OFFICIAL_RESULT` ambiguity handling first
2. Expand score-backed settlement second
3. Add settlement diagnostics third
4. Protect adaptive learning fourth
5. Improve forensic tooling fifth

Do **not** keep tuning model weights until settlement truth is trustworthy.  
Otherwise we will just teach the system from bad labels.

## Practical Bottom Line

The current run suggests that TTLElite 2.0 is close, but not done:

- live observation is strong
- score continuity is materially improved
- session telemetry is strong
- UI is much better

But:

**win/loss truth is still not strong enough whenever the engine falls back to player/date official-result matching without strong identity evidence.**

That is the main remaining 2.0 bug cluster, and it should now be treated as the top execution priority.

One more important nuance from the extended watch:

- this run did **not** provide evidence that the open-bet tracking-state labels are still broken
- this run did provide repeated evidence that settlement routing is still too archive-heavy

So the next execution wave should focus on **settlement truth**, not another broad platform rewrite.
