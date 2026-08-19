# Phase 07 Performance Audit
Generated: 2026-08-08T04:30:05.281Z
## Budgets vs measured
| Metric | Budget | Measured | Status |
| --- | ---: | ---: | :---: |
| Initial JS bundle (gz) | 450 KB | 94.51 KB (21.0% of budget) | PASS |
| Initial CSS bundle (gz) | (no spec budget) | 10.96 KB | INFO |
| LCP (median of 3) | 2000 ms | 148 ms | PASS |
| TTI proxy (domInteractive, median of 3) | 1500 ms | 59 ms | PASS |
| FCP (median of 3) | (informational) | 148 ms | INFO |
## Initial-payload composition
| Asset | bytes | gz bytes |
| --- | ---: | ---: |
| `assets/index-7m3kFwYC.js` | 300068 | 96775 |
| `assets/index-By4nOVXE.css` | 59625 | 11228 |
| **Total JS gz** |  | **96775 (94.51 KB)** |
| **Total CSS gz** |  | **11228 (10.96 KB)** |
## All built assets (gz desc)
| Asset | bytes | gz bytes |
| --- | ---: | ---: |
| `index-7m3kFwYC.js` | 300068 | 96775 |
| `card-BDR_7XY1.js` | 51509 | 17214 |
| `index-By4nOVXE.css` | 59625 | 11228 |
| `ReviewRoute-DMccCcZO.js` | 34997 | 10394 |
| `MatchDetailRoute-DJolBF52.js` | 29287 | 8482 |
| `LiveBoardRoute-BycsDY_V.js` | 22871 | 7540 |
| `AdminHubRoute-BTDzTKKt.js` | 19530 | 6377 |
| `BettorMatchupPanel-DnbJpFoL.js` | 17499 | 4963 |
| `OpsConsoleRoute-BZiaUKd9.js` | 11730 | 4088 |
| `ScrapeRoute-CT7sd9-M.js` | 12972 | 3987 |
| `MlQualityRoute-C7rzjX-x.js` | 11664 | 3849 |
| `OpsDiffsRoute-Bjxjw2QE.js` | 12150 | 3828 |
| `OpsFeedsRoute-DIe4MrvD.js` | 11976 | 3704 |
| `OpsIngestRoute-COI8Szv-.js` | 11680 | 3620 |
| `OpsStreamsRoute-BP9wkrM3.js` | 11251 | 3465 |
| `api-DZ1QdZ6t.js` | 1472 | 586 |
| `api-B4Nn2Sql.js` | 1199 | 498 |
| `api-wfhLC4dg.js` | 1432 | 428 |
| `index.html` | 572 | 357 |
| `api-C9DPuymP.js` | 467 | 324 |
| `shield-alert-HmcUj-90.js` | 354 | 261 |
| `radio-Dzna87EH.js` | 375 | 250 |
| `database-zap-1SMUzGke.js` | 340 | 246 |
| `timer-reset-Bl_JR8Nl.js` | 270 | 207 |
| `dollar-sign-YxpIz-i7.js` | 220 | 189 |
| `circle-check-zFdmEwWP.js` | 174 | 164 |
| `clock-3-oB-yoFxb.js` | 165 | 164 |
| `target-CfvgKMl7.js` | 222 | 158 |
| `loader-circle-BRQK-jkZ.js` | 140 | 153 |
| `chevron-left-DyvOxNew.js` | 131 | 144 |
## Notes
- Initial JS gz size sums every `<script>` and `<link rel="modulepreload">` referenced from `index.html`; this is what the browser is forced to fetch and parse before the first interactive paint.
- LCP is captured via a `PerformanceObserver` with `buffered: true` against the served `/v3/` Home route in headless Chrome at viewport 1440×1100; samples are run from a fresh context each time and reported as median.
- TTI proxy uses `PerformanceNavigationTiming.domInteractive`; this matches the spec's "page TTI" gate at §1.16 and §8 because the v3 shell hydrates synchronously off the initial bundle.
- LCP and TTI run against `vite preview` on localhost; they are *local* numbers, matching the spec language ("page TTI < 1.5 s local"). Production CDN behaviour is measured separately once item 7 ships.
