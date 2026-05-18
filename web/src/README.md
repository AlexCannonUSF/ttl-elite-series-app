# Frontend Guide

This folder contains the React application for the product UI.

Each major frontend subfolder in this tree now also has its own local `README.md` so page-, API-, and shell-level context is available in place.

## Structure

- `app`
  - application bootstrapping, router, theme, query client
- `components`
  - shared layout pieces
- `pages`
  - primary product surfaces
- `lib`
  - API client and formatting helpers
- `types`
  - TypeScript DTO mirrors of backend contracts

## Primary Entry Points

- [`/Users/alexcannon/Downloads/TTLEliteSeries/web/src/app/router.tsx`](app/router.tsx)
  - route map and lazy loading
- [`/Users/alexcannon/Downloads/TTLEliteSeries/web/src/components/AppShell.tsx`](components/AppShell.tsx)
  - shared navigation shell
- [`/Users/alexcannon/Downloads/TTLEliteSeries/web/src/lib/api.ts`](lib/api.ts)
  - frontend-to-backend API boundary

## Page Ownership

- `LiveOddsPage.tsx`
  - Live Studio
- `DashboardPage.tsx`
  - Overview
- `PlayersPage.tsx`
  - Players Intelligence list/search surface
- `PlayerDetailPage.tsx`
  - individual player scouting profile
- `MatchupPage.tsx`
  - head-to-head analysis and decision lens
- `AnalyticsPage.tsx`
  - model, calibration, trigger, and session analytics
- `AdminPage.tsx`
  - operations and maintenance

## Rule Of Thumb

If a page needs a backend change, start by checking:

1. the page file in `pages`
2. [`/Users/alexcannon/Downloads/TTLEliteSeries/web/src/lib/api.ts`](lib/api.ts)
3. the matching backend controller
4. the service behind that controller
