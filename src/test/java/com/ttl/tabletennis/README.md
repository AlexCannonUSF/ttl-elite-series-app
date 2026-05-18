# Test Suite Guide

This folder contains the backend tests that anchor the main behavior of the system.

## Suite Map

- `service/PaperTradingServiceTests.java`
  - live session orchestration, settlement logic, replay edge cases, and shadow-table mirroring
- `service/OddsValueEngineServiceTests.java`
  - live board/value engine behavior
- `service/PredictionModelServiceTests.java`
  - model training, shrinkage, calibration, adaptive logic
- `service/FeatureServiceTests.java`
  - matchup features and significance/support outputs
- `service/PlayerIdentityServiceTests.java`
  - alias and canonical-player behavior
- `service/TtSeriesEloSyncServiceTests.java`
  - ranking sync behavior
- `service/Glicko2RatingServiceTests.java`
  - Glicko rating rebuild/tuning
- `scrape/HardRockOddsScraperTests.java`
  - sportsbook parsing/contracts
- `scrape/ScrapeMetricsTests.java`
  - scrape telemetry
- `analytics/Glicko2Tests.java`
  - rating engine math
- `util/MatchResultParserTests.java`
  - raw result parsing
- `util/NameUtilsTests.java`
  - name normalization helpers

## Most Important Regression Suites

When the change touches live trading or launch risk, start with:

```bash
./mvnw -Dtest=PaperTradingServiceTests,OddsValueEngineServiceTests,PredictionModelServiceTests,FeatureServiceTests test
```

When the change touches scrapers or data ingest, also include:

```bash
./mvnw -Dtest=HardRockOddsScraperTests,ScrapeMetricsTests,PlayerIdentityServiceTests,TtSeriesEloSyncServiceTests test
```
