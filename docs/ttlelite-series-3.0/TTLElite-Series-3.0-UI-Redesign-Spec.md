# TTLElite Series 3.0 — UI Redesign Spec

**Status:** Draft v1.0 · **Parent:** `TTLElite-Series-3.0-Master-Plan.md`
**Covers:** Workstream A — UI/UX Re-Platforming

---

## 1. Goals & Non-Goals

### Goals
- Feel like a premium **trading terminal × sportsbook** (Bloomberg × TradingView × Polymarket × Betfair Exchange), not a generic admin panel.
- Dense where density matters (live board, settled tape, evidence drill-downs) and airy where it doesn't (session KPIs, settings).
- Dark-first with a daylight mode for demos and recording.
- Keyboard-first: every core action bindable.
- 60 fps updates on a 100-row live board.
- Page TTI < 1.5 s local; initial bundle < 450 KB gz.
- Progressive disclosure: the primary console shows only decision-critical data; drill-downs live behind a click.
- Accessible to WCAG 2.2 AA.

### Non-Goals
- Native mobile app (PWA only).
- Multi-user theming or per-user customization at launch (workspace persistence is per-operator on device).
- Full SSR via Next.js — Vite + React Router 7 is sufficient for a single-operator local-first app.

---

## 2. Stack Decision (final, backed by April 2026 research)

| Layer | Technology | Rationale |
|---|---|---|
| Framework | React 19 + Vite 7 (retain) | Already current; no migration value |
| Router | React Router v7 (retain) | Working; lazy routes already set up |
| Components | **shadcn/ui + Radix primitives** (new) | Source-owned, copy-paste, a11y baked in, smallest bundle, best dark-mode story |
| Styles | **Tailwind CSS v4** (new) | OKLCH tokens, CSS-first `@theme`, dark mode as data-attribute |
| Tokens | **Radix Colors** + OKLCH + Tailwind v4 | Production-grade, used by Vercel/Stripe/GitHub |
| Typography | **Inter** (UI) + **IBM Plex Mono** (numbers/tables) + **Bricolage Grotesque** (display/brand retained) | Screen-optimized, tabular numerals supported |
| Charts (live) | **TradingView Lightweight Charts v4+** | 45 KB, 60 fps, canvas-rendered |
| Charts (analytics) | **Apache ECharts** (tree-shaken) | Heatmaps, reliability diagrams, odds ladders |
| Charts (in-row sparklines) | Lightweight Charts (line) | Consistent with primary |
| Data grid | **Glide Data Grid v5+** (live board, settled tape) + **TanStack Table v8** (static lists) | Canvas grid for 100–1000 rows/sec; TanStack for sorting/filtering/selection logic |
| Motion | **Motion v12.36+** (formerly Framer Motion) | GPU-accelerated, layout animations, respects `prefers-reduced-motion` |
| Command palette | **cmdk** | Linear/Raycast-class |
| Shortcuts | **react-hotkeys-hook v5** | Scopes + sequences |
| State (durable) | **TanStack Query v5** (retain) | Already in use |
| State (ephemeral real-time) | **Zustand v4** (new) | <2 KB; direct mutations for flash-on-change latency |
| Live comms | **SSE** for prices/odds/scores + **WebSocket** for upstream scraping client | SSE simpler to scale; WS for bidirectional needs |
| Theme | CSS variables on `<html data-theme="dark|light">` | No runtime CSS-in-JS |

**Retired from current stack:** MUI v7, Emotion, Recharts. Retained as transitional only; hard cutover at end of Phase 07.

---

## 3. Information Architecture

### 3.1 Top-level navigation (AppShell)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  ▣ TTLElite 3.0   Live   Matchup   Analytics   Ops   Players   Admin       │
│                                                  🔔  ⌘K  ☾  👤             │
└─────────────────────────────────────────────────────────────────────────────┘
```

Top bar is **always visible**, 48 px tall, contains:
- Brand mark (click → `/`).
- Primary nav (7 items).
- Notification bell (feed-health alerts, contradiction alerts).
- Command palette trigger (`⌘K` / `Ctrl-K`).
- Theme toggle.
- Operator avatar + settings.

### 3.2 Live Studio (landing page) — 3-pane terminal

```
┌──────────────────────────────────────────────────────────────────────────┐
│  [Feed Health Ribbon]                                                     │
│  HR-MKT ●  HR-TGT ●  Sofa ●  AiScore ●  Stream-CV ●  Official ●  DLQ 0    │
├──────────────────────────────────────────────────────────────────────────┤
│  ┌─ Live Board (virtualized, flash-on-change) ─────┐ ┌─ Right Rail ─────┐│
│  │ Match │ Edge │ Odds │ Side │ Model │ Rel │ Score │ │ SELECTED BET     ││
│  │ A v B │ +7%  │ 1.85 │  A   │ .62   │ Hi  │ 2-1   │ │ Evidence Bundle  ││
│  │ C v D │ +4%  │ 2.10 │  D   │ .49   │ Med │ 1-0   │ │ Timeline         ││
│  │ ...                                              │ │ Model Explain    ││
│  │                                                  │ │ Point-by-Point   ││
│  │                                                  │ │ Sizing Rationale ││
│  │                                                  │ │ Evidence Sources ││
│  └──────────────────────────────────────────────────┘ └──────────────────┘│
│  ┌─ Session ────┐  ┌─ Top Triggers ──┐  ┌─ Open Bets ────────────────┐   │
│  │ +$1,240 ▲4.1%│  │ VALUE_SPIKE 62%  │  │ 12 open   $3,200 at risk   │   │
│  │ CLV +0.8% ▲  │  │ REGIME_FLIP 58%  │  │ (sparkline)                │   │
│  │ (sparkline)  │  │ (bar list)       │  │                            │   │
│  └──────────────┘  └──────────────────┘  └────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
```

**Behavior**:
- Left pane (80%): virtualized Live Board with saved views, column visibility toggle, sticky header, flash-on-change cells.
- Right rail (320 px, collapsible via `[` shortcut): SELECTED BET drawer. Opens on row click or `Enter`. Six tabs: Evidence · Timeline · Model · Point-by-Point · Sizing · Raw Sources.
- Bottom ribbon (fixed 120 px): Three KPI cards, no heavy chart JS — sparklines only.

### 3.3 Page catalog

| Path | Page | Primary widgets | Density |
|---|---|---|---|
| `/` | Live Studio | Feed-health ribbon, Live Board, Right Rail, Session ribbon | Very high |
| `/paper-trading` | Paper Trading Dashboard | Equity curve (Lightweight Charts), drawdown heatmap, trigger ROI bar list, CLV curve, settled-tape table | High |
| `/score-truth` | Score Truth Monitor | Contradiction feed, evidence viewer, per-source confidence matrix, ambiguity histogram | High |
| `/matchup` | Matchup Lab | Player compare cards, feature radar, Markov simulator, H2H matrix | Medium |
| `/analytics` | Analytics Lab | Model registry table, reliability diagrams, walk-forward PnL, regime profile explorer | High |
| `/players` | Players | Virtualized TanStack Table with filters (rating band, volume, alias status), per-row mini rating trajectory | Medium |
| `/players/:id` | Player Detail | Dual rating trajectory, recent form heatmap, streak strip, H2H lookup, strengths/weaknesses | Medium |
| `/ops` | Ops Console | Feed SLAs, DLQ browser, replay runner, scrape run explorer, error log | High |
| `/admin` | Admin | Scrape triggers, rating rebuild, migration tools, model train | Medium |
| `/settings` | Settings | Theme, keyboard bindings, display density, feed toggles | Low |

---

## 4. Design System (web/src/ui/)

### 4.1 Directory layout

```
web/src/ui/
├── tokens.ts                 // design-token constants
├── theme.css                 // @theme tokens, data-theme rules
├── primitives/
│   ├── Button.tsx
│   ├── IconButton.tsx
│   ├── Badge.tsx
│   ├── Card.tsx
│   ├── Section.tsx
│   ├── KPICard.tsx
│   ├── SparkCard.tsx
│   ├── ChartCard.tsx
│   ├── FlashOnChange.tsx
│   ├── DataTable.tsx
│   ├── LiveGrid.tsx           // Glide wrapper
│   ├── ModalDialog.tsx
│   ├── Drawer.tsx
│   ├── Tabs.tsx
│   ├── Select.tsx
│   ├── Combobox.tsx
│   ├── ContextMenu.tsx
│   ├── Toast.tsx / Toaster.tsx
│   ├── Tooltip.tsx
│   ├── Popover.tsx
│   ├── CommandPalette.tsx
│   ├── EmptyState.tsx
│   ├── ShortcutHint.tsx
│   ├── ThemeToggle.tsx
│   ├── FeedHealthPill.tsx
│   ├── StatusBadge.tsx
│   └── index.ts
├── charts/
│   ├── LwcChart.tsx           // TradingView Lightweight Charts wrapper
│   ├── EquityCurve.tsx
│   ├── Sparkline.tsx
│   ├── ReliabilityDiagram.tsx // ECharts
│   ├── Heatmap.tsx            // ECharts
│   └── OddsLadder.tsx         // ECharts
├── motion/
│   ├── FlashOnChange.tsx
│   ├── layout.ts
│   └── presets.ts
├── shortcuts/
│   ├── ShortcutProvider.tsx
│   ├── registry.ts
│   └── useShortcut.ts
└── storybook/                 // one story per primitive
```

Every primitive ships with: dark+light variants, a Storybook entry, keyboard-nav verification, and an accessibility note.

### 4.2 Tokens (subset)

```ts
// tokens.ts
export const colors = {
  // semantic, map to OKLCH Radix palettes
  bg: { app: 'var(--color-bg-app)', card: 'var(--color-bg-card)', hover: 'var(--color-bg-hover)' },
  fg: { primary: 'var(--color-fg-primary)', muted: 'var(--color-fg-muted)' },
  border: { subtle: 'var(--color-border-subtle)', strong: 'var(--color-border-strong)' },
  price: { up: 'var(--color-up)', down: 'var(--color-down)', flat: 'var(--color-flat)' },
  state: {
    live:    'var(--color-state-live)',
    pending: 'var(--color-state-pending)',
    settled: 'var(--color-state-settled)',
    voided:  'var(--color-state-voided)',
    won:     'var(--color-state-won)',
    lost:    'var(--color-state-lost)',
  },
} as const;

export const radii = { sm: 4, md: 8, lg: 12, pill: 999 } as const;
export const shadows = {
  card:  '0 1px 2px oklch(0% 0 0 / 0.10), 0 2px 8px oklch(0% 0 0 / 0.06)',
  hover: '0 2px 4px oklch(0% 0 0 / 0.14), 0 8px 24px oklch(0% 0 0 / 0.10)',
} as const;
export const motion = {
  flashMs: 420,
  rowSpringStiffness: 300,
  rowSpringDamping: 30,
} as const;
```

```css
/* theme.css — Tailwind v4 @theme */
@theme {
  --color-up:      oklch(62% 0.20 142);
  --color-down:    oklch(57% 0.25  27);
  --color-flat:    oklch(60% 0.02 271);
}

html[data-theme='dark'] {
  --color-bg-app:    oklch(15% 0.01 271);
  --color-bg-card:   oklch(18% 0.01 271);
  --color-bg-hover:  oklch(22% 0.01 271);
  --color-fg-primary:oklch(95% 0.00 0);
  --color-fg-muted:  oklch(65% 0.02 271);
  --color-border-subtle:oklch(25% 0.01 271);
  --color-border-strong:oklch(35% 0.02 271);
  --color-state-live:   oklch(70% 0.20 142);
  --color-state-pending:oklch(70% 0.15  85);
  --color-state-settled:oklch(60% 0.02 271);
  --color-state-voided: oklch(55% 0.02 271);
  --color-state-won:    oklch(62% 0.20 142);
  --color-state-lost:   oklch(57% 0.25  27);
}

html[data-theme='light'] {
  /* inverted scale; same semantic tokens */
  ...
}
```

### 4.3 Typography system

```ts
export const fonts = {
  ui:      'Inter, system-ui, -apple-system, sans-serif',
  display: '"Bricolage Grotesque", Inter, sans-serif',
  mono:    '"IBM Plex Mono", ui-monospace, monospace',
} as const;

// Tabular nums for anything numeric
.tabular { font-feature-settings: "tnum","cv11"; }
```

Scale: 11/13/14/16/20/24/32 px. Line-height 1.2 for headings, 1.5 for body, 1 for mono numeric columns.

### 4.4 Component contract — `FlashOnChange`

```tsx
<FlashOnChange value={price}>
  {(v, direction) => <span className={`tabular ${direction === 'up' ? 'text-up' : direction === 'down' ? 'text-down' : ''}`}>{v.toFixed(2)}</span>}
</FlashOnChange>
```

- Applies a 420 ms background tint (OKLCH up/down color at 18% alpha) that fades out.
- `prefers-reduced-motion: reduce` → swap tint for a 1-frame border flash.
- Wrapped in `role="status" aria-live="polite" aria-atomic="true"` so changes are announced.

### 4.5 Live board grid contract

- Backed by Glide Data Grid. Cell renderers pull from a Zustand store subscribed to SSE.
- Row height: 32 px (dense). Column widths persisted per user in `localStorage` under `ttl.board.v1`.
- Column visibility toggle via right-click header menu.
- Keyboard: `j/k` row nav, `Enter` opens Right Rail, `b` places paper bet on selected row (confirmation modal), `/` focuses search.
- Saved views: `localStorage` slot `ttl.board.views.v1`. Default views shipped: All, High Edge (>5%), High Reliability, Live Only, Pre-match Only.

---

## 5. Motion & Real-Time Feedback

| Pattern | Duration | Easing | Notes |
|---|---|---|---|
| Flash-on-change cell tint | 420 ms | `ease-out` | OKLCH up/down at 18% alpha |
| Drawer open (Right Rail) | 220 ms | spring(300, 30) | Motion library |
| Tab underline | 160 ms | `ease-in-out` | |
| Toast enter/exit | 180 ms | `ease-out` / `ease-in` | |
| Row insertion (settled tape) | 240 ms | `ease-out` | Slide down + fade |
| Equity curve new tick | 60 fps streaming | — | Lightweight Charts handles natively |
| Feed-health pill state change | 300 ms | `ease-in-out` | Color cross-fade |

All motion respects `prefers-reduced-motion` — reduced mode replaces all transitions with single-frame state changes (no visual regression, just less motion).

---

## 6. Keyboard Map (shipped in Phase 02)

### 6.1 Global

| Keys | Action |
|---|---|
| `⌘K` / `Ctrl-K` | Open command palette |
| `?` | Open keyboard-shortcut help sheet |
| `g` then `l` | Go to Live Studio |
| `g` then `p` | Go to Paper Trading |
| `g` then `s` | Go to Score Truth |
| `g` then `m` | Go to Matchup Lab |
| `g` then `a` | Go to Analytics |
| `g` then `o` | Go to Ops Console |
| `⌘\` / `Ctrl-\` | Toggle theme |
| `[` | Toggle Right Rail |

### 6.2 Live Board

| Keys | Action |
|---|---|
| `j` / `k` | Next / prev row |
| `Enter` | Open selected row in Right Rail |
| `Esc` | Close Right Rail |
| `b` | Place paper bet on selected row |
| `v` | Cycle saved views |
| `f` | Focus filter bar |
| `/` | Focus quick-search |
| `1`..`9` | Jump to saved view 1..9 |

### 6.3 Settings

All bindings are read from a registry and remappable in `/settings`. Conflicts flagged at save time.

---

## 7. Accessibility

- Every interactive control has a visible focus ring ≥ 2 px with 4.5:1 contrast against its background (OKLCH `blue-9` ring token).
- All Radix primitives used as-is for Dialog, Tabs, Popover, Tooltip, DropdownMenu — their built-in focus trapping and WAI-ARIA patterns are preserved.
- Flash-on-change cells wrap in `role="status" aria-live="polite"`. For price tickers prone to >1 change/sec, wrap the whole ticker in one `aria-live="off"` region and batch announcements every 5 s via `aria-live="polite"` summary node.
- Color is never the sole indicator of state. Badges include icon + text (`● LIVE`, `○ PENDING`).
- Tables use `<table>` semantics where feasible; Glide Data Grid is configured with accessible cell descriptions.
- WCAG 2.2 SC 2.4.11 Focus Not Obscured: Right Rail drawer never covers the focused cell in the grid (grid pans if needed).
- Reduced-motion support as above.

---

## 8. Performance Budget

| Metric | Budget | Enforcement |
|---|---|---|
| Initial JS bundle (gz) | ≤ 450 KB | Vite-plugin-bundle-size in CI |
| First Contentful Paint (local) | ≤ 1.0 s | Lighthouse CI |
| Time To Interactive (local) | ≤ 1.5 s | Lighthouse CI |
| Live Board frame time (100 rows × 1 Hz update) | ≤ 16 ms | Chrome tracing in CI |
| Memory growth (1 h soak) | ≤ 50 MB | Puppeteer memory profile |

Code-splitting per route; charts dynamically imported; Motion tree-shaken.

---

## 9. Migration Plan (sequence matches Master Plan phases)

1. **Phase 00** — Scaffold `web/src/ui/`, add Tailwind v4, install shadcn primitives, build Button/Card/Badge/Table/KPICard/FlashOnChange/CommandPalette + Storybook. No user-visible change.
2. **Phase 02** — Ship new Live Studio shell + Live Board + Right Rail. Old LiveOddsPage still reachable at `/legacy/live-odds` for comparison.
3. **Phase 04** — Ship Paper Trading Dashboard + Score Truth Monitor. Retire the telemetry sections of old LiveOddsPage.
4. **Phase 07** — Migrate Players, Player Detail, Matchup, Analytics, Admin to new stack. Delete `@mui/*` from `package.json`. Delete `/legacy/*` routes. Final bundle measurement.

Each phase ends with Storybook updated, Lighthouse CI green, and visual regression snapshots approved.

---

## 10. Open Questions (resolve in Phase 00)

1. Do we ship Storybook with the app, or keep it separate? Recommendation: separate (`web/storybook/`), not shipped to prod.
2. Is per-operator persistence via `localStorage` sufficient, or do we want to sync workspace state via backend? Recommendation: `localStorage` for now; revisit if we ever go multi-user.
3. Should the command palette include model-train / scrape-refresh ops actions? Recommendation: yes behind a `ops:` prefix, gated by an "advanced" toggle.
4. PWA install on the main domain or a subdomain? Recommendation: main.

---

*End of UI Redesign Spec v1.0.*
