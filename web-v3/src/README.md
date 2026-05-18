# V3 Frontend Source Guide

## Structure

- `app`
  - application bootstrapping and router wiring
- `components/layout`
  - shell-level framing and workspace chrome
- `components/ui`
  - source-owned UI primitives in the shadcn/ui style
- `routes`
  - route surfaces; Phase 00 has only a placeholder home route
- `lib`
  - shared client-side helpers

## Current entry points

- `app/App.tsx`
- `app/router.tsx`
- `routes/HomeRoute.tsx`

The current scaffold is intentionally small. The next steps mount this workspace at `/v3/*` and then start moving real product surfaces into it one route at a time.
