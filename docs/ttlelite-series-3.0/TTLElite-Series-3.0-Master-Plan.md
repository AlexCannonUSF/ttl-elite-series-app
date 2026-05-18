# TTLElite Series 3.0 — Master Plan

**Status:** Draft v1.0
**Owner:** Alex
**Prepared:** 2026-04-15
**Baseline:** 2.0 (Run-59 launch summary, ~90% complete, core foundation 97%)
**Scope:** Full product, architecture, and research plan for the next major release.
**Companion docs (to be created alongside this):**
- `TTLElite-Series-3.0-UI-Redesign-Spec.md`
- `TTLElite-Series-3.0-Score-Truth-Engine.md`
- `TTLElite-Series-3.0-Scraper-And-Data-Ingestion-Spec.md`
- `TTLElite-Series-3.0-Prediction-Engine-Spec.md`
- `TTLElite-Series-3.0-Implementation-Checklist.md`
- `TTLElite-Series-3.0-Release-Gate-Checklist.md`

---

## 0. Executive Summary

TTLElite Series 2.0 solved the *observability* problem: we now see what is happening on the board, we persist observations, we separate session/rolling/learned metrics, and we know when a bet is open vs tracked-after-close vs settled. What 2.0 did **not** fully solve is the *settlement truth* problem — when Hard Rock closes the market, score coverage collapses, and too many bets exit through `VOIDED_MISSING_BOARD_TIMEOUT` or `SETTLED_FROM_LAST_SCORE_HEURISTIC`. Run-59 shipped with zero `SCORE_BACKED`, zero `OFFICIAL_RESULT`, zero `TARGETED_COMPLETION`, and a 6/3 void/heuristic ratio (see `PaperTradingService.java:856–896`).

3.0 is organized around four vertical bets that, taken together, raise the product from "credible paper-trading tool" to "premium, watchable, sophisticated live trading platform":

1. **Settlement Truth Engine** — a multi-source, evidence-bundle settlement pipeline that makes live scoring survive market closure. The centerpiece is a Computer-Vision + Public-Mirror secondary pipeline that runs in parallel to the sportsbook feed, so a bet never loses its scoreline when the market closes.
2. **UI/UX re-platforming** — a migration from MUI v7 to a shadcn/ui + Tailwind + Radix + Tremor stack, reorganized into a three-pane "Live Studio" terminal with progressive disclosure, flash-on-change real-time cells, keyboard-first ops, and an optional dark "pro" theme. The goal is for the app to feel like Bloomberg Terminal × TradingView × Polymarket.
3. **Scraper & Data Ingestion hardening** — GraphQL-first, WebSocket-subscribed ingestion; identity locking; line-movement and closing-line capture; dedicated TT-Series player/H2H scrapers; Sofascore/AiScore/BetsAPI mirrors; and a new ingestion bus with dead-letter queue, replay, and provenance.
4. **Prediction Engine 3.0** — a layered model stack: Glicko2 + TrueSkill2 base rating → gradient-boosted margin model → point-by-point Markov live engine → calibration → fractional Kelly sizer → portfolio risk controller. Backtesting, walk-forward validation, CLV-first KPIs, and conformal prediction for bet sizing.

Everything below is organized so it can be broken into discrete phases and shipped incrementally without destabilizing live paper-trading.

---

## 1. Context: What 2.0 Left Us With

### 1.1 Shipped (the foundation we keep)

- Spring Boot 3.3.3 / Java 17 backend with H2 (local) / MySQL (prod) behind JPA.
- React 19 + Vite 7 + MUI v7 + TanStack Query + Recharts frontend, Live Studio as landing surface.
- Nine source paths already wired through `PaperTradingService`: Hard Rock GraphQL market, Hard Rock GraphQL scoreboard, Hard Rock targeted GraphQL by event IDs, Hard Rock public tree, `TrackedMatchObservation` timeline, TT-Series recent-result refresh, DB result confirmation, last-score heuristic, and voiding.
- `PaperTradeBet` lifecycle states (`OPEN_PENDING_SCORE`, `OPEN_SCORE_VISIBLE`, `MARKET_CLOSED_SCORE_TRACKED`, `FINISHED_UNCONFIRMED`, `FINISHED_CONFIRMED`, `SETTLED`, `VOIDED`), plus rich settlement reason codes (see `PaperTradingService.java:1244–1302`).
- Glicko2 rating service + scheduler, ITTF Elo sync cron, feature service with 15-minute cache, adaptive regime profiles.
- `release_gate.sh`, `live_studio_smoke.sh`, `live_settlement_watch.sh` operator scripts.

### 1.2 Residual problems 3.0 must close

From `TTLElite-Series-2.0-Score-Detection-And-Void-Recovery-Plan.md` and `Run-56-Bug-Closure-Plan.md`:

- Closed-bet coverage failure. Once Hard Rock closes the market, we often lose the score feed entirely — so a match that is still being played disappears from our point of view.
- Wrong-match attachment on official-result settlement for same-player same-day pairings (Bug A). No ambiguity score, no contradiction guard.
- Score-backed settlement is underutilized even when the timeline is rich (Bug B).
- No dedicated stale-live recovery workflow with multi-source escalation.
- Public-tree parser brittle against current `{ count: N }` shapes.
- Adaptive learning is at risk of being poisoned by heuristic-settled labels (Bug E).
- `LiveOddsPage.tsx` is a 2,512-line monolith — high density but poor information architecture, no persistent filter state, no design-system primitives (`web/src/components/` contains only `AppShell.tsx`).
- `PaperTradingService.java` is 5,653 lines and owns placement + pricing merge + settlement + integrity counters + session lifecycle. It is the single most fragile subsystem in the codebase.
- No line-movement capture, no CLV, no closing-line snapshots — so the most important sharp-betting KPI is absent.
- Prediction pipeline is family-based (multiple models under `PredictionModelService`), but lacks point-by-point live win-probability and a true portfolio/risk engine.

### 1.3 Non-goals in 3.0

- Real-money integration (paper-trading only).
- User authentication / multi-tenant. Local single-operator app stays local.
- Mobile-native app. Progressive-web-app only.
- Anything that requires bypassing a sportsbook's ToS aggressively (we remain a spectator of publicly visible data + publicly-streamed video).

---

## 2. Guiding Principles for 3.0

1. **Truth first, opinions second.** Never let a bet be "settled" without a trustworthy evidence bundle. Never let the model train on labels that came from weak evidence.
2. **Reliability is a feature.** Every data source has a health SLA, a fallback, and a circuit breaker. The user sees source health on the main console.
3. **Progressive disclosure.** The main console shows only decision-critical data; drill-downs live behind a click. No 2,500-line pages.
4. **Flash-on-change, not refresh-all.** UI must feel alive — cells flash on price/score change, sparklines animate, tickers scroll.
5. **Keyboard-first for power users.** Every core action has a shortcut (`/` search, `j/k` row nav, `b` place bet, `g s` settled tape, `g l` live, `?` help).
6. **Separation of metric windows.** Session, rolling (last-N-runs), and learned-global metrics are always labeled and never mixed in the same card.
7. **Observable, replayable, reversible.** Everything is logged to an event store; any session can be replayed deterministically; any settlement can be reversed.
8. **Code as platform.** Break `PaperTradingService` into bounded contexts. Introduce a design system. Stop piling onto monoliths.

---

## 3. Target Architecture (3.0)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       TTLElite Series 3.0 System Map                        │
└─────────────────────────────────────────────────────────────────────────────┘

INGESTION LAYER (all feed-health monitored, replayable)
 ├── HardRock GraphQL Market Feed         (WebSocket subscribe where possible)
 ├── HardRock Targeted Event Poller       (by externalEventId, post-close)
 ├── HardRock Public Tree Parser          (secondary, for discovery only)
 ├── Sofascore Live Mirror                (alt public live scoring)
 ├── AiScore Live Mirror                  (alt public live scoring)
 ├── BetsAPI / SportRadar (optional paid) (tier-2 licensed mirror)
 ├── TT-Series Tournament Post Scraper    (existing, retained)
 ├── TT-Series Player Page Scraper        (NEW — finer H2H evidence)
 ├── TT-Series H2H Page Scraper           (NEW — same-day disambiguation)
 ├── Stream CV Worker (per-match)         (NEW — YouTube/Twitch scoreboard OCR)
 └── ITTF / WTT Ranking Refresh           (existing)

           ↓   (all publish to)

INGESTION BUS (Spring @Async + in-process event bus → later Kafka-lite/Redis Streams)
 ├── Topics: odds.updated, score.observed, result.confirmed, identity.updated,
 │           market.closed, stream.frame, ingest.error
 ├── Provenance: every event carries {source, sourceVersion, observedAt,
 │                                     confidence, rawPayloadRef}
 ├── Dead-letter queue (failed parses replayable)
 └── Backpressure-aware with per-source rate limits

           ↓

DOMAIN LAYER
 ├── Identity Service             (player alias locking, same-day disambiguation)
 ├── Rating Service               (Glicko2 + TrueSkill2 dual, consensus rating)
 ├── Feature Service              (unchanged structure, new features)
 ├── Score Truth Engine (NEW)     (evidence bundle, contradiction guard,
 │                                  ambiguity score, confidence decay)
 ├── Prediction Engine            (layered: rater → GBM → Markov → calibrator)
 ├── Pricing & Value Engine       (devigging, edge, reliability band)
 ├── Risk & Sizing Engine         (fractional Kelly with portfolio caps)
 ├── Paper Trading Core           (placement + lifecycle, slimmed down)
 └── Learning Gate                (rejects low-confidence labels)

API LAYER (Spring REST + SSE for live pushes, later GraphQL)
 ├── /api/live-studio/*           (existing, extended)
 ├── /api/score-truth/*           (NEW — evidence bundles, timelines)
 ├── /api/models/*                (train, evaluate, compare)
 ├── /api/risk/*                  (portfolio exposure, caps)
 └── /api/ops/*                   (feed health, DLQ, replay)

FRONTEND (shadcn/ui + Tailwind + Radix + Tremor + Recharts/Lightweight-Charts)
 ├── Terminal Shell (3-pane workspace, tabs, command palette, keyboard nav)
 ├── Live Board                   (flash-on-change, ladder view optional)
 ├── Paper Trading Dashboard      (sparklines, equity, drawdown, CLV)
 ├── Score Truth Monitor (NEW)    (evidence bundle viewer per bet)
 ├── Matchup Lab                  (feature vectors, H2H, point-by-point)
 ├── Analytics Lab                (model registry, calibration, walk-forward)
 ├── Ops Console                  (feed health, DLQ, replay)
 └── Admin & Settings
```

---

## 4. Workstream A — UI/UX Re-Platforming

### 4.1 Problem statement

The current UI is functional but fights information density. `LiveOddsPage.tsx` at 2,512 lines tries to be a trading board, a paper-trading dashboard, a settlement telemetry console, and a diagnostics view all on one page. There are no reusable primitives (`web/src/components/` contains only `AppShell.tsx`). MUI v7 gives us polished components but a heavy bundle and a light-mode-only aesthetic that feels more "admin panel" than "trading terminal."

### 4.2 The 3.0 visual target

- **Terminal aesthetic, not SaaS.** Inspired by Bloomberg Terminal, TradingView, Polymarket, Kalshi, and Betfair Exchange. Dense where it must be, airy where it can be.
- **Dark-first with a light "daylight" mode.** Pro users live in dark; daylight is for demos/streaming. Use OKLCH color tokens via Radix Colors for perceptual consistency.
- **Typography.** Inter (UI), IBM Plex Mono or JetBrains Mono (numbers/tables), Bricolage Grotesque retained for display/brand.
- **Real-time cells.** Every changing number flashes (green up / red down) on update, sparkline in the corner, hover tooltip with last-30-min history.
- **Progressive disclosure.** Primary console shows only: live board, open bets, session P&L, top triggers, feed-health pill row. Everything else is one click away (command palette or right-rail drawer).

### 4.3 Stack decision

**Migrate from MUI v7 → shadcn/ui (+ Tailwind v4 + Radix primitives + Tremor charts).**

Rationale:

- **shadcn/ui + Radix** gives us source-owned components (no opinionated theme lock-in), accessibility baked in via Radix, and a component catalog designed for dense apps.
- **Tailwind v4** compiles fast, tokenizes design via CSS variables, and is trivially themable across light/dark.
- **Tremor** (built on Tailwind + Recharts) is purpose-built for KPI dashboards — KPI cards, donut, bar list, area charts, sparkbars — with minimal code.
- **TradingView Lightweight Charts** for equity/price/score progression (tick-level, performant, free).
- **AG Grid Community** or **TanStack Table + react-virtuoso** for the main live board (hundreds of rows at 1 Hz).
- **cmdk** for the command palette.
- **Framer Motion** for micro-interactions and flash-on-change.

Trade-offs:

- MUI is already there and familiar. The migration is a cost.
- Decision: migrate incrementally. Introduce shadcn/Tailwind alongside MUI via a `ui-v3/` directory. Ship one page at a time (Live Board first, Matchup Lab last). Delete MUI after the last page flips. Do not run both forever — set a hard cutover date at end of Phase 3.

### 4.4 Information architecture — the 3-pane Live Studio

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ▣ TTLElite  │ Live │ Matchup │ Analytics │ Ops │    🔔  ⌘K  👤  ☾       │  ← top bar
├──────────────────────────────────────────────────────────────────────────┤
│  ┌─ Feed Health ────────────────────────────────────────────────────┐    │
│  │ HR-MKT ●  HR-TGT ●  Sofa ●  AiScore ●  Stream-CV ●  Official ●  │    │
│  └──────────────────────────────────────────────────────────────────┘    │
│  ┌─ Live Board (virtualized, flash-on-change) ────────┐  ┌─ Right Rail ─┐│
│  │  Match  │Edge│Odds│Side│Model│Rel │ Score │ State  │  │ Selected Bet ││
│  │ ───────────────────────────────────────────────── │  │ (detail pane) ││
│  │  A v B  │+7% │1.85│ A  │ 0.62│Hi  │ 2-1   │ LIVE  │  │  Evidence     ││
│  │  C v D  │+4% │2.10│ D  │ 0.49│Med │ 1-0   │ LIVE  │  │  Bundle       ││
│  │  ...    │    │    │    │     │    │       │       │  │  Timeline     ││
│  └───────────────────────────────────────────────────┘  │  Model why    ││
│  ┌─ Session P&L ──┐ ┌─ Top Triggers ─┐ ┌─ Open Bets ──┐│  Point-by-Pt  ││
│  │ +$1,240 (+4.1%)│ │ VALUE_SPIKE 62%│ │ 12 open      ││  Sizing calc  ││
│  │ CLV +0.8% ▲    │ │ REGIME_FLIP 58%│ │ $3,200 risk  ││               ││
│  │ (sparkline)    │ │ (bar list)     │ │ (sparkline)  ││               ││
│  └────────────────┘ └────────────────┘ └──────────────┘│               ││
└──────────────────────────────────────────────────────────────────────────┘
```

- **Left pane (80% width): Live Board.** Virtualized rows, sortable, multi-column filters in a toolbar, column visibility toggle, saved views (e.g. "High Edge Only", "Regime:Aggressive").
- **Right Rail (320 px, collapsible): Selected Bet Drawer.** Shows the full evidence bundle (see §5.4), point-by-point model state, sizing rationale, raw sources, and historical H2H. Opens on row click. Keyboard: `Enter` to open, `Esc` to close.
- **Bottom ribbon: Session KPIs.** Fixed height. Sparklines only — no chart JS libraries on this row. Details live on the dedicated Paper Trading Dashboard page.

### 4.5 New/rebuilt pages

| Page | Purpose | Primary widgets |
|---|---|---|
| **Live Studio** (rebuilt) | Trading console | Feed-health row, live board, right rail, session ribbon |
| **Paper Trading** (new, split from LiveOdds) | Session history & performance | Equity curve (Lightweight Charts), drawdown heatmap, trigger ROI bar list, CLV chart, settled-tape table |
| **Score Truth Monitor** (new) | Evidence bundles, contradiction alerts | Timeline viewer, per-source confidence matrix, ambiguity-score histogram, contradiction feed |
| **Matchup Lab** (redesigned) | Pre-match analysis | Player comparison cards, feature radar, point-by-point Markov simulator, H2H matrix |
| **Analytics Lab** (redesigned) | Model + calibration | Model registry table, reliability diagrams, walk-forward PnL curves, regime profile explorer |
| **Players** (redesigned) | Player directory | TanStack Table with virtualization, filters (rating band, volume, alias status), mini rating trajectory per row |
| **Player Detail** (redesigned) | Deep scouting | Rating trajectory (dual Glicko2/TrueSkill2), recent form heatmap, streak strip, H2H lookup, strengths/weaknesses derived from model features |
| **Ops Console** (new) | System health & replay | Feed SLAs, DLQ browser, replay runner, scrape run explorer, error log |
| **Admin** (retained) | Maintenance | Scrape triggers, rating rebuild, migration tools |

### 4.6 Design-system primitives (to be created)

Create `web/src/ui/` with the following in Phase 1:

```
Button, IconButton, Badge (status: LIVE, PENDING, SETTLED, VOIDED, WON, LOST),
Card, Section, DataTable (TanStack + virtuoso), KPICard, SparkCard,
ChartCard (wraps Recharts/Lightweight-Charts with loading/error/empty),
ModalDialog, Drawer, Tabs, Select (with search), Combobox,
Toast + Toaster, Tooltip, Popover, CommandPalette, EmptyState,
ShortcutHint, ThemeToggle.
```

Every primitive ships with: variants, dark+light tokens, a Storybook entry, visual regression snapshot, and a11y notes.

### 4.7 Motion, real-time, keyboard

- **Flash-on-change** cell wrapper: `<FlashOnChange value={x}>` adds a 400 ms green/red tint when value changes, respects `prefers-reduced-motion`.
- **Ticker row** for settled bets (think subtle scrolling tape at the bottom, optional).
- **Command palette (`⌘K`/`Ctrl-K`)** — search players, open pages, place quick bet, jump to bet by ID, toggle theme.
- **Shortcuts** — see §2 principle 5. Bind in a `ShortcutProvider`.

### 4.8 Why not a thicker JS framework (Next.js, Remix)?

Current Vite + React Router 7 is adequate. SSR is not needed for a single-operator, local-first tool. Staying on Vite keeps cold-start <2 s.

### 4.9 Deliverables

- Design tokens file (`web/src/ui/tokens.ts`) with OKLCH palettes for dark/light.
- Storybook (`web/storybook/`) with all primitives.
- Rebuilt pages per table above.
- A hard MUI-cutover date and a Vite bundle size budget (≤ 450 KB gz initial).

---

## 5. Workstream B — Score Truth Engine

This is the single most important engineering investment in 3.0. It eliminates the "closed bets have no live scoring" class of bug.

### 5.1 The core model shift

Today, settlement branches are a chain of `if` statements in `PaperTradingService#settleOpenBets` (line 1009 onward). 3.0 introduces a first-class `SettlementEvidence` bundle. Each open bet maintains a running bundle that is always up-to-date, and settlement is a pure function `decide(bundle, policy) → Decision`.

```
SettlementEvidence {
  betId
  trackedEventId
  liveObservations[]       // from any live source: HR-MKT, HR-TGT, Sofa, AiScore, Stream-CV
  officialCandidates[]     // from TT-Series post, player page, H2H page, DB
  streamObservations[]     // CV-derived (§5.3)
  contradictions[]         // pairs of sources disagreeing
  ambiguityScore           // 0..1, higher = more same-day repeat risk
  identityLock             // frozen playerId pair + dateWindow
  lastMovementAt
  coverageState            // FULL | PARTIAL | DARK
  confidence               // aggregated Bayesian score across layers
}
```

A settlement decision carries the evidence bundle reference at the time of settlement. This closes Bug B (score-backed settlement underutilized) and Bug C (no contradiction guard) by making the evidence central rather than resolver-branch-local.

### 5.2 Multi-source ingestion (the primary fix)

Eight parallel score-observation sources, tiered by trust:

**Tier 1 — Sportsbook**
- **HR-MKT** (Hard Rock GraphQL market feed) — live while market is open. Current primary.
- **HR-TGT** (Hard Rock targeted poller by eventId) — continues after market closure if the event still exists server-side. 2.0's most important reliability layer; now becomes one of several.

**Tier 2 — Public Mirrors (NEW, high priority)**
- **Sofascore** — very wide TT Elite / TT Cup coverage, public web + mobile endpoints. Poll by fixture ID or player pair.
- **AiScore** — secondary mirror, good when Sofa misses a match.
- **BetsAPI** (optional paid) — licensed TT feed with WebSocket, covers most Polish + Czech TT studios. Consider ~$30-80/mo tier.
- **LiveScore** / **FlashScore** — lower priority, but add if BetsAPI isn't pulled in.

**Tier 3 — Stream CV (NEW, research + build)** — see §5.3.

**Tier 4 — Confirmation**
- TT-Series tournament post scraper (existing).
- TT-Series **player page** scraper (NEW) — per-player match ledger with timestamps, good for same-day disambiguation.
- TT-Series **H2H page** scraper (NEW) — resolves "same two players played twice today" cleanly.
- Internal match DB (existing, used for replay/reconciliation).

Every source implements a `ScoreObserver` interface:

```java
interface ScoreObserver {
  String sourceId();
  TrustTier tier();
  Flux<ScoreObservation> subscribe(TrackedEventId id);   // or scheduled poll
  HealthStatus health();
  BackoffPolicy backoff();
}
```

Each observation lands on the ingestion bus with provenance, and the Score Truth Engine updates the bundle.

### 5.3 Stream-CV pipeline (novel for 3.0)

TT Elite and TT Cup matches are regularly streamed publicly on YouTube and the operator's own stream. The scoreboard is a fixed overlay with deterministic geometry. We build a CV worker that reads the scoreboard independently of any sportsbook, guaranteeing score coverage even when every API goes dark.

Pipeline:

1. **Stream discovery.** When a tracked match begins, resolve its stream URL from TT-Series (most matches link their live stream). Fallback: a hand-maintained `stream_routes.yaml` mapping venue → channel.
2. **Frame sampler.** `yt-dlp --live-from-start` piped to `ffmpeg -vf fps=1/3 -vframes -1 -` yielding one frame every 3 s.
3. **Scoreboard ROI crop.** Per-channel template (stored as a tight rectangle). OpenCV template match on first 60 s to auto-detect ROI if geometry changes.
4. **OCR.** PaddleOCR (best Asian+Latin accuracy / speed / open-source) as primary; EasyOCR as fallback. Apply strict regex: `/^(\d{1,2})\s*[-:–]\s*(\d{1,2})(?:\s*\((\d{1,2})-(\d{1,2})\))?$/`. Results below 0.85 confidence are rejected.
5. **State machine.** 11-point TT scoring, best-of-N games, server alternation every 2 points (10-10 onward every point). Reject any transition that violates the rulebook. Require 3 consecutive confirmations before promoting a score change.
6. **VLM cross-check (optional, cost-aware).** On any contradiction or low-confidence frame, call a VLM (GPT-4V / Claude vision) with a cropped image and structured schema. Budget cap: N calls per match per hour.
7. **Emit.** `stream.frame` → ingestion bus as `ScoreObservation{source=STREAM_CV, confidence=ocr_conf * state_machine_prior}`.

Compute budget: a single Raspberry Pi-class worker can handle ~6 concurrent TT streams at 1 frame / 3 s with PaddleOCR CPU-only. At peak TT Elite concurrency (~8 studios), a small cloud worker (~$10/mo) handles everything. VLM fallback at 1 call per minute per stream is <$5/mo per active stream.

### 5.4 Identity locking + ambiguity score

To kill Bug A (wrong-match attachment on same-player same-day repeats):

- At placement, freeze `identityLock = {p1_id, p2_id, placementDateWindow, bookerEventId}`.
- During evidence ingestion, every candidate match goes through an `AmbiguityScorer`:
  - `0.0`: exactly one candidate with matching player pair within ±90 minutes → unambiguous.
  - `0.3–0.7`: multiple candidates within window → partial.
  - `>0.7`: more than one match today between same pair → ambiguous; require stricter tiebreakers (stream score, sportsbook eventId, point-in-time correlation).
- If `ambiguityScore > threshold && contradictingEvidence`, refuse auto-settlement, flag for manual review, and emit a UI banner in Score Truth Monitor.

### 5.5 Contradiction guard

Before any settlement, compare top-ranked evidence with last observed live score direction. If they disagree (e.g., live says "losing 0-2 at 6-10" but official says "won"), do not settle. Raise `ContradictionDetected` event and route to manual review. This is precisely what Run-56 Bug A needed.

### 5.6 Stale-live recovery workflow

When a bet has been open > `STALE_THRESHOLD_MINUTES` with no fresh observation:

1. Escalate tier: HR-MKT → HR-TGT → Sofa → AiScore → Stream-CV.
2. For each source, try 3 backoff-aware fetches.
3. If all sources dark, schedule an official-result recovery job in the background, keep the bet in `MARKET_CLOSED_SCORE_TRACKED` until `OFFICIAL_WINDOW_MINUTES` (e.g. 3 hours).
4. Only after the recovery window, and only with contradiction guard cleared, allow heuristic settle or void. Heuristic must emit a distinct `HEURISTIC_TAINTED` flag so the learning gate excludes it.

### 5.7 Learning gate

The prediction pipeline consumes labels. Add a `LearningGate` filter: only settlements with `evidence.confidence ≥ 0.9` and `contradictions.isEmpty()` flow into training. Everything else is shadowed (present in dashboards, absent from training sets). This fixes Bug E.

### 5.8 Service decomposition

Carve `PaperTradingService` (5,653 lines) into:

- `PlacementService` — recommendation ingestion + bet creation.
- `SessionService` — session lifecycle, counters, reset.
- `SettlementEngine` — pure settlement decisions from evidence bundles.
- `IntegrityService` — counter rollups for UI.
- `PaperTradingFacade` — orchestrator, thin.

Target each component ≤ 1,000 lines. Port existing tests first, then refactor under green.

### 5.9 New endpoints

- `GET /api/score-truth/bets/{betId}/evidence` — full bundle.
- `GET /api/score-truth/bets/{betId}/timeline` — merged observation timeline.
- `GET /api/score-truth/contradictions` — contradiction feed (paginated).
- `POST /api/score-truth/bets/{betId}/review` — manual settle/void with justification.
- `GET /api/ops/feeds/health` — per-source SLAs.
- `POST /api/ops/feeds/{source}/replay` — replay from DLQ.

---

## 6. Workstream C — Scraper & Data Ingestion Hardening

### 6.1 What stays

- `TtSeriesScraper` (1,306 lines): retained, with the retry + backoff already present. Add Prometheus-style metrics. Move to a scheduled workflow with explicit backfill window.
- `HardRockOddsScraper`: retain as fallback but demote from primary.

### 6.2 What changes

**GraphQL-first, WebSocket where possible.** The Hard Rock app is GraphQL under the hood. Rather than scraping HTML, 3.0 introduces `HardRockGraphQLClient` that issues the same GraphQL queries the web app does and, where Hard Rock exposes subscriptions, uses `wss://` instead of polling. Poll cadence on fallback: 1 s while market open, 3 s while tracked-after-close, 15 s for market discovery.

**Per-source client abstraction.** `FeedClient` interface with built-in exponential backoff, circuit breaker (Resilience4j), rate limiter, and a `FeedHealth` probe that publishes on a topic. UI consumes feed-health directly.

### 6.3 New scrapers

1. **TT-Series player page scraper** — `GET https://www.tt-series.com/player/{slug}/`. Parses the per-player match log. Cadence: on demand (when a tracked bet needs same-day disambiguation) + 1×/hour for active players.
2. **TT-Series H2H scraper** — `GET https://www.tt-series.com/h2h/{p1}/{p2}/`. Same-day disambiguation gold.
3. **Sofascore TT adapter** — reverse-engineer the public mobile-web JSON (no login). Map TT-Series player → Sofa player via fuzzy match + alias table.
4. **AiScore TT adapter** — similar approach.
5. **Stream-CV worker** — see §5.3.
6. **ITTF / WTT rankings scraper** — augment existing ITTF Elo sync with world-rankings and recent form delta.

### 6.4 Data we start capturing (gaps closed)

- **Odds snapshots every tick**, not just current. Store `OddsSnapshot(matchId, observedAt, side, price, bookerEventId)`; enables line-movement, steam-move detection, and CLV.
- **Closing Line Value**: capture the closing line per bet the moment the market closes. CLV becomes a first-class KPI.
- **Pre-match betting volume / move count** (when exposed).
- **Live point-by-point timeline** (from Stream-CV + mirrors).
- **Serve ownership** (derived from 11-point state machine in CV).
- **Timeout/medical-timeout signals** (from mirrors when present).
- **Venue/studio** and **table number** (from TT-Series pages).
- **Match start timestamp with timezone** (currently approximate).

### 6.5 Persistence & retention

- Add `OddsSnapshot`, `StreamFrameObservation`, `MirrorObservation`, `FeedHealthSample` tables.
- TTL retention policy: raw frames 7 days, parsed observations 90 days, derived features indefinite.
- Introduce `RawPayloadStore` (S3-compatible MinIO locally) referenced by `rawPayloadRef` on events.

### 6.6 Ingestion bus

- Phase 1: in-process `ApplicationEventPublisher` + DLQ in a JPA table.
- Phase 2: Redis Streams (lightweight, easy to run locally).
- Phase 3 (optional): Kafka / Redpanda when multi-host scale is needed.

### 6.7 Observability

- Every scraper emits `ScrapeRunStarted`, `ScrapeRunCompleted`, `ScrapeRunFailed` with timing, row counts, and DLQ pointers.
- `/api/ops/feeds/health` endpoint returns per-source: last-success timestamp, rolling-5-min success rate, current backoff, in-flight requests.
- UI shows feed-health pills on top ribbon (green/amber/red) and a feed-health page with sparklines.

---

## 7. Workstream D — Prediction Engine 3.0

### 7.1 Design goals

- State of the art for ping-pong binary + game-score + live win-probability prediction.
- Calibrated probabilities with conformal intervals.
- Training pipeline that is reproducible, walk-forward validated, and leakage-free.
- A clean separation between **base rater**, **margin model**, **live engine**, **calibrator**, and **sizer**.

### 7.2 Layered model stack

**Layer 1 — Base rater (dual)**
- **Glicko2** (existing) retained.
- **TrueSkill2** added in parallel. TrueSkill2 handles score margin, partial information, and time-decayed skill drift better than Glicko2 in tight-cluster semi-pro pools. Consensus rating is a Bayesian blend weighted by each system's RD/σ.

**Layer 2 — Margin & game-count model**
- Gradient-boosted trees (LightGBM via Java wrapper or a Python microservice) trained on the existing feature vectors (`FeatureService.buildMatchupFeatureVector`, 77+).
- Targets: `P(p1 wins)`, `E[score diff]`, `P(best-of-5 ends in 3-0 / 3-1 / 3-2)`.
- Features added: consensus rating delta, rating uncertainty gap, recent-form exponential decay, surface-conditional form, tournament-level form, same-day fatigue index, H2H recency-weighted winrate, rest days, time-of-day of match.

**Layer 3 — Live engine (point-by-point Markov)**
- A 2-d Markov chain on `(p1_points, p2_points)` within a game, chained across games. Transition probabilities depend on server and a per-player `on-serve / off-serve` win rate.
- Priors calibrated from historical point-by-point data (Stream-CV-derived in 3.0; Sofascore-derived backfill for 2.x).
- Live win-probability updates every point → feeds Value Engine.

**Layer 4 — Calibration**
- Platt scaling plus isotonic regression, selected per regime via cross-validation.
- Beta calibration for heavy-tail adjustment.
- Reliability diagrams always visible on Analytics Lab.

**Layer 5 — Uncertainty**
- Conformal prediction produces a per-bet 80/90/95% interval on expected value.
- Reliability band (hi/med/lo) becomes a function of the interval width rather than a hand-coded lookup.

### 7.3 Value engine & devigging

- Replace heuristic margin with principled devigging: Shin, power, and additive methods; pick the best by out-of-sample Brier score on closing lines.
- Edge = fair_prob − implied_prob_after_devig.
- Steam-move detector: if closing line moves > σ toward our side in < 30 s, boost confidence; if against, fade.

### 7.4 Risk & sizing

- Fractional Kelly (default 0.25 Kelly) with portfolio constraints:
  - Max exposure per match: 2% of bankroll.
  - Max exposure per player pair per day: 3% of bankroll.
  - Total open exposure cap: 15% of bankroll.
  - Correlation cap: if two bets share >0.6 feature correlation, down-weight Kelly.
- Kelly is further gated by calibration confidence and reliability band. If calibration is loose in the current regime, cap Kelly at 0.10.
- CLV as the primary KPI. ROI is secondary. A profitable run with negative CLV is treated as luck.

### 7.5 Backtesting & walk-forward

- All training is walk-forward: roll through history month by month, retrain, validate next month, slide.
- Leakage audit: never let a feature include data after the match start timestamp.
- Backtest harness produces: equity curve, max drawdown, CLV curve, regime-conditional PnL, trigger-attribution waterfall.
- Every training run registers a `PredictionModelRegistryEntry` with metrics + feature hash + training date range → the existing registry infrastructure is a natural home.

### 7.6 Model training cadence

- **Nightly** walk-forward retrain after all matches settle with `confidence ≥ 0.9`.
- **Weekly** full retrain + calibration review.
- **Regime shift detection**: if rolling Brier score worsens > 2σ, open an alert and pause auto-sizing escalation until a human or a scheduled retrain passes.

### 7.7 Research roadmap (post-3.0 exploratory)

- Transformer sequence model over career match histories (NeuralRater).
- Bayesian dynamic linear models for skill drift.
- Opponent-aware embeddings (style vs style).
- RL-based sizing policy (replace Kelly with learned policy) — after 18+ months of clean labels.

---

## 8. Workstream E — Observability, Testing, Ops

### 8.1 Observability

- Structured JSON logs with correlation IDs across scraper → bus → settlement.
- Prometheus-format `/actuator/metrics` exposing per-source success rate, settlement latency, model Brier, session PnL, CLV.
- Grafana dashboards checked in as code (provisioned JSON).
- Trace every settlement with an OpenTelemetry span (free with Spring Boot 3.x `micrometer-tracing`).

### 8.2 Testing strategy

- Keep `release_gate.sh` but extend with:
  - `score_truth_simulation.sh` — runs a canned 3-match replay and asserts no wrong-match attachments.
  - `cv_smoke.sh` — feeds 5 canned scoreboard images through PaddleOCR, asserts correct parse.
  - `walk_forward_ci.sh` — retrains, checks Brier ≤ threshold.
- Contract tests for every `FeedClient`.
- Property-based tests (jqwik) for the Markov point engine and the 11-point rulebook validator.

### 8.3 Replay & reversibility

- Every ingest event has a replayable reference.
- `POST /api/ops/replay?from=ts&to=ts` reruns a time window through the engine in shadow mode (no DB writes to primary tables, writes to a `replay_*` shadow set).
- Settled bets can be reverted with audit trail.

### 8.4 Release gate for 3.0

- 2.0 gate preserved, plus:
  - `/api/score-truth/contradictions` returns zero open contradictions, or all open ones have human annotation.
  - Feed-health pill row shows all green (or deliberately disabled sources are grayed).
  - Walk-forward Brier ≤ `0.22` on last 90 days.
  - Stream-CV worker running with ≥ 95% parse-success on the last 24 h.
  - Bundle size < 450 KB gz, FCP < 1.5 s local.

---

## 9. Phased Rollout

All phases are independently shippable. Each ends in a green release gate.

### Phase 00 — Foundations (2 weeks)
- Carve `PaperTradingService` into `PlacementService / SessionService / SettlementEngine / IntegrityService / PaperTradingFacade`. Ports all existing tests. No behavior change.
- Introduce ingestion bus (in-process), DLQ table, provenance schema.
- Add `OddsSnapshot` tick-level persistence — unlocks line movement + CLV immediately.
- Create `web/src/ui/` with tokens, a few primitives (Button, Card, Badge, Table, KPICard), Storybook scaffolding.
- Success: all 2.0 gates still green; code coverage unchanged; `OddsSnapshot` rows accumulating.

### Phase 01 — Score Truth Engine (Core) (3 weeks)
- Introduce `SettlementEvidence` bundle, `AmbiguityScorer`, `ContradictionGuard`, `LearningGate`.
- Rewrite `SettlementEngine` around bundles (behavior-preserving via shadow mode first, then flip default).
- Build TT-Series player-page + H2H scrapers.
- Add Sofascore live mirror (highest-impact of the non-stream sources).
- New endpoints: `/api/score-truth/*`.
- Success: 0 wrong-match settlements on Run-56 replay; `SCORE_BACKED` share > 50% of settlements.

### Phase 02 — Live Studio Redesign (Live Board) (3 weeks)
- Migrate Live Board from `LiveOddsPage.tsx` to new `ui/` primitives (shadcn + Tailwind).
- 3-pane terminal shell + command palette + feed-health ribbon.
- Flash-on-change cells, virtualized grid, saved views.
- Retire the old LiveOddsPage's Live Board section; other sections stay on MUI for now.
- Success: Lighthouse > 90, 60 fps updates on 100-row live board, keyboard-only usable.

### Phase 03 — Stream-CV Pipeline (4 weeks, research-heavy)
- Frame sampler + PaddleOCR + state machine + ROI templates for top 10 TT Elite streams.
- `StreamObservationRepository`, integration into ingestion bus.
- VLM fallback with budget caps.
- Ops Console CV panel.
- Success: 95% parse accuracy on 24-hour continuous live test; median observation latency < 10 s.

### Phase 04 — Paper Trading Dashboard + Score Truth Monitor (2 weeks)
- Split paper-trading telemetry out of Live Studio into `/paper-trading`.
- New `/score-truth` page: contradiction feed, evidence drill-down, per-source confidence matrix.
- Equity/CLV curves on Lightweight Charts.
- Success: old LiveOddsPage below 800 lines; contradiction feed in UI.

### Phase 05 — Prediction Engine 3.0 (4 weeks)
- Add TrueSkill2 + consensus rater.
- Introduce LightGBM margin model (JVM via Microsoft's LightGBM4j, or Python sidecar over gRPC).
- Live point-by-point Markov engine.
- Calibration + conformal intervals.
- Walk-forward backtester and Analytics Lab redesign.
- Success: ≥ 3 bp Brier improvement vs baseline on 90-day walk-forward; calibration ECE ≤ 0.03.

### Phase 06 — Risk & Sizing Rework (1.5 weeks)
- Fractional Kelly with portfolio + correlation caps.
- Reliability-band gating tied to conformal interval widths.
- Sizing explanation card in Right Rail drawer.

### Phase 07 — Remaining UI migration + Ops Console (2.5 weeks)
- Migrate Players, Player Detail, Matchup, Admin to new stack.
- Build Ops Console (feed health, DLQ, replay).
- Hard MUI cutover. Delete `@mui/*` from `package.json`.

### Phase 08 — Hardening, 3.0 Release Gate (1.5 weeks)
- All release-gate scripts.
- Walk-forward regression CI.
- 72-hour live soak.
- Docs refresh.

**Total nominal effort: ~20 calendar weeks** for a single engineer, assuming Stream-CV and ML layer are the longest poles. With parallelization (one person on UI, one on ingestion/ML), compresses to ~12–14 weeks.

---

## 10. Risks, Mitigations, Open Questions

| Risk | Impact | Mitigation |
|---|---|---|
| Hard Rock closes GraphQL surface or blocks scraping | High | Tier-2 mirrors + Stream-CV become primary, not fallback. Early investment in Stream-CV pays off. |
| Stream-CV latency too high for live sizing | Medium | Already acceptable at 3 s cadence for TT pacing (~20 s per point). Use HR-MKT when open; CV when dark. |
| MUI→shadcn migration drags | Medium | Hard cutover date, phase-by-phase page migration, dual stack briefly. Block new pages on shadcn. |
| ML gains are marginal | Medium | CLV tracking means we measure what matters. Even no-gain but calibrated probabilities improve sizing. |
| `PaperTradingService` refactor breaks settlement | High | Strangler-fig: shadow mode + diff harness. Flip default only when shadow matches for 72 h. |
| Sofascore / AiScore ToS / rate limiting | Medium | Respect robots, human-paced polling, identify honestly, cache aggressively. Back off BetsAPI as licensed fallback. |
| Same-day same-player ambiguity still fools us | High | AmbiguityScorer + ContradictionGuard + manual review queue. Never auto-settle ambiguous + contradicted. |
| Bundle bloat from Tremor + LightweightCharts + cmdk | Low | Dynamic imports, route-level code splitting, < 450 KB gz budget enforced in CI. |

**Open questions to resolve in Phase 00:**

1. LightGBM via Java (DJL / LightGBM4j) or Python sidecar over gRPC? Recommendation: Python sidecar for flexibility; slim image; ~50 MB extra in ops overhead.
2. Redis Streams or stick with in-process for ingestion bus through 3.0? Recommendation: in-process through Phase 03, Redis from Phase 04 to enable Ops replay cleanly.
3. Are we comfortable running Stream-CV on operator laptop, or do we stand up a small cloud worker? Recommendation: cloud worker (~$10/mo DO droplet) for reliability; operator laptop can replay.
4. Keep H2 for dev + MySQL for prod, or migrate to Postgres? Recommendation: migrate to Postgres — better JSONB, range indexes, and `TIMESTAMPTZ` for line-movement queries. Do it in Phase 00.
5. Single operator forever, or eventual multi-user? If multi-user, add user_id to `PaperTradeSession` now (cheap in Phase 00).

---

## 11. Summary Scorecard — What "3.0 Done" Looks Like

**Reliability**
- `SCORE_BACKED` settlement share ≥ 60% of total settlements.
- `VOIDED_MISSING_BOARD_TIMEOUT` ≤ 5% of total.
- `SETTLED_FROM_LAST_SCORE_HEURISTIC` ≤ 10% and never in the training set.
- Zero wrong-match settlements on the Run-56 replay corpus.
- Every bet has a viewable evidence bundle with ≥ 2 independent sources.

**Experience**
- Live Board renders 100 rows at 60 fps with flash-on-change.
- Dark/light theme toggle, full keyboard nav, command palette.
- Right-rail drawer shows evidence + model-why + sizing-why for every bet.
- Page TTI < 1.5 s local, bundle < 450 KB gz.

**Intelligence**
- Dual rater (Glicko2 + TrueSkill2) + GBM margin + Markov live engine.
- Walk-forward Brier ≤ 0.22, ECE ≤ 0.03.
- Conformal intervals drive reliability bands.
- CLV reported every session, positive on 90-day rolling.

**Operations**
- Ingestion bus with DLQ + replay.
- Feed-health pills on every page.
- `release_gate.sh`, `cv_smoke.sh`, `walk_forward_ci.sh`, `score_truth_simulation.sh` all green.
- `PaperTradingService` < 1,000 lines; no single service > 1,500 lines.

---

## 12. Companion Documents to Create Next

1. **`TTLElite-Series-3.0-UI-Redesign-Spec.md`** — detailed wireframes, component catalog, design tokens, keyboard map, motion spec.
2. **`TTLElite-Series-3.0-Score-Truth-Engine.md`** — `SettlementEvidence` schema, `AmbiguityScorer` algorithm, `ContradictionGuard` rules, state machine diagrams.
3. **`TTLElite-Series-3.0-Scraper-And-Data-Ingestion-Spec.md`** — per-source client specs, rate limits, schemas, DLQ handling.
4. **`TTLElite-Series-3.0-Stream-CV-Spec.md`** — pipeline, ROI templates, OCR config, VLM fallback budget.
5. **`TTLElite-Series-3.0-Prediction-Engine-Spec.md`** — feature list, rater math, calibration recipe, walk-forward protocol.
6. **`TTLElite-Series-3.0-Implementation-Checklist.md`** — granular per-phase task list mirrored in the issue tracker.
7. **`TTLElite-Series-3.0-Release-Gate-Checklist.md`** — operator-facing gates & scripts.
8. **`phases/phase-00 … phase-08`** — one file per phase with entry/exit criteria.

---

*End of 3.0 Master Plan v1.0.*
