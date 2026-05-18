# Config Package

This package owns Spring and runtime wiring.

## Files

- `FeatureFlagCatalog`
  - reads the canonical top-level `features.yaml` file and exposes rollout states to runtime code
- `CorrelationIdFilter`
  - seeds `X-Correlation-Id` for every HTTP request and mirrors it back in the response
- `AsyncConfig`
  - async executor setup
- `WebConfig`
  - CORS and MVC wiring
- `StartupBrowserLauncher`
  - browser/dev-server startup behavior

## Related startup file

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/TtlEliteSeriesApplication.java`
  - datasource defaults and startup jobs live there rather than in this package
