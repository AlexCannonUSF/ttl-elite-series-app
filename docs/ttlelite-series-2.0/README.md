# TTLElite Series 2.0 Planning Set

This directory is the working planning set for the TTLElite Series 2.0 upgrade.

If you are looking for source-code navigation instead of roadmap/planning docs, use the codebase atlas:

- [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/codebase-atlas/README.md`](../codebase-atlas/README.md)

The planning set now reflects three things at once:

- the refined 2.0 thesis
- the current codebase reality
- the remaining execution path from today's app to a reliable 2.0 release

## Core Thesis

**A match can remain observable after it stops being bettable.**

TTLElite 2.0 should stop treating sportsbook market visibility and live score visibility as the same thing. The product should run on three separate truths:

1. `Priceable live feed`
   - for live markets, odds, active recommendations, and market state
2. `Tracked score-continuity feed`
   - for continuing to follow score even after a market is suspended, hidden, or no longer displayed
3. `Official confirmation layer`
   - for result/archive/backfill validation when score continuity or market continuity alone is not enough

## How To Use This Folder

Read and work from these docs in this order:

1. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Master-Plan.md`
   - the refined 2.0 vision, research-backed assumptions, current-state readout, and release philosophy
2. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Implementation-Checklist.md`
   - the build-order checklist with concrete file/module touch points and current-state notes
3. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Phased-Product-Spec.md`
   - user outcomes, scope, dependencies, and acceptance criteria for each phase
4. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Live-Data-Architecture.md`
   - the system design for source hierarchy, score continuity, settlement, and Live Studio data flow
5. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Release-Gate-Checklist.md`
   - the concrete launch gate with scripts, test commands, endpoint checks, and stop-ship conditions
6. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Launch-Summary.md`
   - the short launch-state readout: what is proven, what remains optional, and what risks still deserve monitoring
7. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Score-Detection-And-Void-Recovery-Plan.md`
   - the current deep-dive recovery plan for score-detection coverage, identity stability, source expansion, and void reduction
8. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/TTLElite-Series-2.0-Run-56-Bug-Closure-Plan.md`
   - the earlier run-specific settlement-truth postmortem, still useful for archive ambiguity history
9. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/phases/`
   - broken-out phase notes that can be marked up and tracked individually during implementation
10. `/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/phases/README.md`
   - the working phase index and lightweight execution tracker

## What Changed In This Revision

This revision is tighter and more execution-oriented than the earlier 2.0 planning set.

It now explicitly captures:

- current backend work already landed, including:
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller/LiveStudioController.java`
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/domain/TrackedMatchObservation.java`
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`
- the current weakness in the Hard Rock public-tree scoreboard parsing path under:
  - `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`
- the need to harden targeted score tracking after market closure instead of only improving settlement heuristics
- the need to separate `Current Session`, `Rolling`, and `All-Time Learned` reporting windows everywhere in the product
- the need to improve model behavior by significance-aware shrinkage and regime-specific calibration rather than by adding more raw rules

## Planning Assumptions

These assumptions are used throughout the documents unless the implementation finds better evidence:

- The primary Hard Rock GraphQL events feed is still the strongest live odds source.
- The public tree is still useful, but should be treated mainly as discovery, health, and secondary evidence unless richer event-level score continuity is confirmed.
- Current 2.0 scaffolding exists, but it is not the same thing as a finished 2.0 product.
- Reliability work ships before UI polish.
- Current-session reporting must remain visually separate from inherited learned behavior.
- Model upgrades should be evolutionary, not destructive.

## External Research Anchors

These docs were sharpened using current official/public references plus the local codebase:

- ITTF handbook and official scoring rules for win-by-two / best-of-odd-game structure
- Hard Rock Bet help/news surfaces for live betting and live sportsbook product behavior
- current Hard Rock response behavior observed through the app's existing scraper paths

The planning docs intentionally use those sources to constrain assumptions, especially around:

- table-tennis scoring logic
- why market suspension should not be treated as score disappearance
- why a dedicated tracked-score architecture is necessary
