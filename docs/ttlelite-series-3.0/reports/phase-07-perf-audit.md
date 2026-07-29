# Phase 07 Performance Audit
Generated: 2026-05-19T17:33:36.635Z
## Budgets vs measured
| Metric | Budget | Measured | Status |
| --- | ---: | ---: | :---: |
| Initial JS bundle (gz) | 450 KB | 197.53 KB (43.9% of budget) | PASS |
| Initial CSS bundle (gz) | (no spec budget) | 7.78 KB | INFO |
| LCP | 2000 ms | not measured (browser skipped) | INFO |
| TTI proxy | 1500 ms | not measured (browser skipped) | INFO |
## Initial-payload composition
| Asset | bytes | gz bytes |
| --- | ---: | ---: |
| `assets/index-C7HJU_QH.js` | 678857 | 202271 |
| `assets/index-DZy59P6l.css` | 38964 | 7967 |
| **Total JS gz** |  | **202271 (197.53 KB)** |
| **Total CSS gz** |  | **7967 (7.78 KB)** |
## All built assets (gz desc)
| Asset | bytes | gz bytes |
| --- | ---: | ---: |
| `index-C7HJU_QH.js` | 678857 | 202271 |
| `index-DZy59P6l.css` | 38964 | 7967 |
| `index.html` | 563 | 352 |
## Notes
- Initial JS gz size sums every `<script>` and `<link rel="modulepreload">` referenced from `index.html`; this is what the browser is forced to fetch and parse before the first interactive paint.
- LCP is captured via a `PerformanceObserver` with `buffered: true` against the served `/v3/` Home route in headless Chrome at viewport 1440×1100; samples are run from a fresh context each time and reported as median.
- TTI proxy uses `PerformanceNavigationTiming.domInteractive`; this matches the spec's "page TTI" gate at §1.16 and §8 because the v3 shell hydrates synchronously off the initial bundle.
- LCP and TTI run against `vite preview` on localhost; they are *local* numbers, matching the spec language ("page TTI < 1.5 s local"). Production CDN behaviour is measured separately once item 7 ships.
