# Phase 07 Performance Audit
Generated: 2026-07-29T17:57:47.069Z
## Budgets vs measured
| Metric | Budget | Measured | Status |
| --- | ---: | ---: | :---: |
| Initial JS bundle (gz) | 450 KB | 111.65 KB (24.8% of budget) | PASS |
| Initial CSS bundle (gz) | (no spec budget) | 7.81 KB | INFO |
| LCP (median of 3) | 2000 ms | 374 ms | PASS |
| TTI proxy (domInteractive, median of 3) | 1500 ms | 62 ms | PASS |
| FCP (median of 3) | (informational) | 374 ms | INFO |
## Initial-payload composition
| Asset | bytes | gz bytes |
| --- | ---: | ---: |
| `assets/index-CmO2AJk3.js` | 358199 | 114325 |
| `assets/index-CHbgC9F8.css` | 39112 | 7994 |
| **Total JS gz** |  | **114325 (111.65 KB)** |
| **Total CSS gz** |  | **7994 (7.81 KB)** |
## All built assets (gz desc)
| Asset | bytes | gz bytes |
| --- | ---: | ---: |
| `index-CmO2AJk3.js` | 358199 | 114325 |
| `LiveBoardRoute-HgldDc3t.js` | 189563 | 61073 |
| `index-CHbgC9F8.css` | 39112 | 7994 |
| `MatchDetailRoute-G-KrVxSW.js` | 27731 | 7935 |
| `ReviewRoute-DcPZKOpb.js` | 24350 | 7863 |
| `OpsConsoleRoute-DKxoSvfE.js` | 11681 | 4070 |
| `ScrapeRoute-Bi8Vnd6l.js` | 12941 | 3978 |
| `OpsDiffsRoute-Z2ldxEkb.js` | 12161 | 3830 |
| `OpsFeedsRoute-DOT5uSoH.js` | 11860 | 3762 |
| `MlQualityRoute-DYwelIBu.js` | 10508 | 3565 |
| `OpsStreamsRoute-nrHX3MC5.js` | 11202 | 3456 |
| `OpsIngestRoute-CYtfGFHI.js` | 9872 | 3054 |
| `api-CYqFZpRN.js` | 1124 | 516 |
| `api-wfhLC4dg.js` | 1432 | 428 |
| `index.html` | 563 | 348 |
| `api-C9DPuymP.js` | 467 | 324 |
| `shield-alert-BK3m2Fkp.js` | 354 | 262 |
| `radio-BCTtLDkC.js` | 375 | 251 |
| `database-zap-CBcw6n-d.js` | 340 | 246 |
| `triangle-alert-B4zriUSs.js` | 266 | 215 |
| `timer-reset-0m9swzOm.js` | 270 | 210 |
| `chart-column-C7iQDJBS.js` | 252 | 195 |
| `chevron-right-BmxUsWsD.js` | 213 | 178 |
| `circle-check-kCRY5Ykn.js` | 174 | 166 |
| `clock-3-BrmhLISS.js` | 165 | 165 |
## Notes
- Initial JS gz size sums every `<script>` and `<link rel="modulepreload">` referenced from `index.html`; this is what the browser is forced to fetch and parse before the first interactive paint.
- LCP is captured via a `PerformanceObserver` with `buffered: true` against the served `/v3/` Home route in headless Chrome at viewport 1440×1100; samples are run from a fresh context each time and reported as median.
- TTI proxy uses `PerformanceNavigationTiming.domInteractive`; this matches the spec's "page TTI" gate at §1.16 and §8 because the v3 shell hydrates synchronously off the initial bundle.
- LCP and TTI run against `vite preview` on localhost; they are *local* numbers, matching the spec language ("page TTI < 1.5 s local"). Production CDN behaviour is measured separately once item 7 ships.
