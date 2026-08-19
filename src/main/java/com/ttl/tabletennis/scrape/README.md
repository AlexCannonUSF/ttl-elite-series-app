# Scrape Package

This package owns external-source fetching and parsing.

## Source connectors

- `TtSeriesScraper`
  - historical scrape runs and recent official result refresh
- `TtSeriesPageParser`
  - raw page parsing into structured matches
- `HardRockOddsScraper`
  - live odds, board rows, score snapshots, and targeted event-id fetches
- `FeedClient` / `IngestEvent` / `FeedHealth`
  - 3.0 feed contract seam for adapters, provenance, and feed-health reporting
- `HardRockFeedClient`
  - 3.0 adapter that wraps Hard Rock board and scoreboard scraping behind the feed contract
- `HardRockTargetedPoller`
  - HR-TGT adapter that polls locked Hard Rock event IDs after market closure and emits score observations
- `HardRockTreeDiscovery`
  - HR-TREE discovery adapter that sweeps the public tree for identity seeding without producing market odds snapshots
- `TtSeriesFeedClient`
  - 3.0 adapter that wraps TT Series official ledger lookups and refresh helpers behind the feed contract
- `BetsApiFeedClient`
  - optional licensed mirror adapter for table-tennis score/result observations
- `AiScoreFeedClient`
  - public mirror adapter for redundant live score/result observations
- `ItftWttFeedClient`
  - official historical ranking feed adapter for ITTF/WTT-style ranking payloads
- `AbstractJsonMirrorFeedClient`
  - shared parser and feed-health wrapper for JSON mirror adapters

## Support classes

- `ParsedMatch`
  - parsed match record from TT Series content
- `MirrorObservationPayload`
  - normalized score-observation payload emitted by mirror clients
- `ItftWttHistoricalPayload`
  - normalized ranking/historical payload emitted by the ITTF/WTT adapter
- `ProgressTracker`
  - scrape progress accounting

## Highest-risk file

- `HardRockOddsScraper`
  - this is the source-contract hotspot for live board and score continuity behavior
