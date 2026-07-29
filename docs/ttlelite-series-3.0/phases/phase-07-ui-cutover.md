# Phase 07 — UI Cutover
_Target duration: 2 weeks · Blast radius: high (UX change) · Reversibility: flag-off, 60 s_

## Purpose
Move every page from the 2.0 MUI/React shell to the v3 shadcn/ui + Tailwind v4 shell. The v3 routes become the canonical user experience. The 2.0 UI is removed. This is the "feels different" phase for end users: faster, more information-dense, keyboard-driven, WCAG 2.2 AA.

## Entry criteria
- Phase 06 soak passed.
- `/v3/` has had every page staged and internally reviewed.
- Performance budgets met on staging for all canonical routes.

## Deliverables
1. Live Board page with FlashOnChange, TradingView Lightweight Charts, Glide Data Grid (per UI Redesign Spec §3–§4).
2. Match Detail page with tabs (Evidence, Prediction, History, Market).
3. Review queue, Ops Console, Feeds page, Streams panel — all on v3 shell.
4. 2.0 UI routes removed; `/` redirects to `/v3/`.
5. A11y audit report (axe-core) attached to the phase ticket.
6. Performance report (Lighthouse) attached to the phase ticket.
7. CDN + long-term caching configured for the v3 bundles.

## Implementation notes
- 2026-05-19: Live Board is now available at `/v3/live-board` with the v3 shell, live session ribbon, FlashOnChange odds/edge cells, strategy controls, local rolling odds history, and TradingView Lightweight Charts for the selected matchup.
- 2026-05-19: Match Detail is now consolidated at `/v3/matches/:id/:tab` with Evidence, Prediction, History, and Market tabs. Existing `/v3/matches/:id/evidence` and `/v3/matches/:id/prediction` URLs now land in the same tabbed shell, while live-board rows open the detail route directly.
- 2026-05-19: Ops Console is now available at `/v3/ops`, composing the already-promoted Review Queue, Ops Feeds, Ops Ingest, Stream Workers, and Settlement Diffs routes into one v3 control-room snapshot.
- 2026-05-19: `/` now redirects to `/v3/`, backend static resources no longer mount the retired `web/dist` MUI SPA as the root fallback, and the startup/browser defaults point at `web-v3` on port 5174.
- 2026-05-19: A11y audit is reproducible via `./scripts/ui-a11y.sh`. The v3 shell now has a skip link, visible focus rings, darker AA-safe muted text, command-palette dialog/listbox semantics, keyboard-selectable live-board rows, reduced-motion CSS, and axe-core reports with zero WCAG violations in standard and reduced-motion modes.

## Work breakdown
- Each page is ported one at a time to v3 and enabled with `features.ui-shell-v3` per-route. We keep the 2.0 route behind a dev-only flag during the soak.
- Keyboard map audited against UI Redesign Spec §5; every shortcut has a test.
- Reduced-motion mode verified: all Motion v12 animations check `prefers-reduced-motion` and degrade cleanly.
- Dark mode verified on OLED with OKLCH palette per UI Redesign Spec §4.

## Exit criteria
- Release Gate Checklist §9 fully ticked.
- LCP ≤ 2.0 s, TTI ≤ 1.5 s, CLS ≤ 0.05 on the 10 canonical routes.
- Initial JS payload ≤ 450 KB gz; per-route additions ≤ 120 KB.
- Zero axe-core critical issues across the canonical routes.
- Two weeks with no regressions reported.

## Risks
- **Power users miss the 2.0 layout.** Mitigation: a "classic" dense mode toggle in v3 keeps the same information density as 2.0.
- **Regressions in less-trafficked pages.** Mitigation: per-page shadow toggling with automated screenshot diffs in CI.

## Rollback
- `features.ui-shell-v3=off` restores 2.0 UI at `/`. Static bundles are versioned, so rollback is a single flag + CDN purge.

## Operator runbook
- `./scripts/deploy-phase-07.sh staging`
- `./scripts/ui-budget.sh --routes canonical.json`
- `./scripts/ui-a11y.sh --routes canonical.json`
- `./scripts/ui-screenshot-diff.sh --against prod` — pixel diff vs. prod; reviewer approves.
- `./scripts/rollback-phase-07.sh staging`
