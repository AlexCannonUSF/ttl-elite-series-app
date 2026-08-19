# Backend Package Guide

This folder contains the backend application. Use this page as the local map once you are already inside the Java source tree.

Each major package in this tree now also has its own local `README.md` so you can keep drilling down without losing context.

## Package Map

- `controller`
  - HTTP API entrypoints
  - maps requests into service calls and DTO responses
- `service`
  - primary business logic and orchestration
  - the most important package in the backend
- `settlement`
  - 3.0 score-truth contracts and the future pure settlement engine seam
- `domain`
  - JPA entities and persisted state
- `repository`
  - Spring Data repositories
- `dto`
  - contracts returned by controllers or passed between services
- `scrape`
  - external scraping/parsing logic for TT Series and Hard Rock
- `cv`
  - placeholder Stream-CV scaffold for the 3.0 stream-routing, fetch, and frame-sampling path
- `analytics`
  - rating and recommendation support algorithms
- `config`
  - async, web, and startup behaviors
- `exception`
  - API error handling
- `mapper`
  - entity-to-DTO and related mapping helpers
- `model`
  - supporting in-memory models
- `projection`
  - repository projections
- `request`
  - validated request payloads
- `util`
  - reusable helpers that do not own workflows
- `ui/fx`
  - JavaFX launcher surface kept separate from the web product

## Highest-Leverage Classes

- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/TtlEliteSeriesApplication.java`](TtlEliteSeriesApplication.java)
  - application startup, datasource defaults, auto-run boot hooks
- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`](service/PaperTradingService.java)
  - live session orchestration, tracked observations, settlement
- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`](service/OddsValueEngineService.java)
  - live board and value opportunity generation
- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`](service/PredictionModelService.java)
  - model training, prediction snapshots, adaptive regime tuning
- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeatureService.java`](service/FeatureService.java)
  - matchup feature vectors and support-depth/significance data
- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/HardRockOddsScraper.java`](scrape/HardRockOddsScraper.java)
  - sportsbook integration and live score/odds extraction
- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape/TtSeriesScraper.java`](scrape/TtSeriesScraper.java)
  - historical ingest and scrape telemetry

## Where To Start

- Wrong settlement or live score issue:
  - [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`](service/PaperTradingService.java)
  - [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/settlement`](settlement)
- Bad live recommendation:
  - [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`](service/OddsValueEngineService.java)
  - [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`](service/PredictionModelService.java)
- Alias or player identity problem:
  - [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PlayerIdentityService.java`](service/PlayerIdentityService.java)
- Scrape/source problem:
  - [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape`](scrape)
- API surface question:
  - [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller`](controller)
  - [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/codebase-atlas/generated/endpoint-map.md`](../../../docs/codebase-atlas/generated/endpoint-map.md)
