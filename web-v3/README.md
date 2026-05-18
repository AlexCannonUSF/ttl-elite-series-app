# Web V3 Workspace

This folder is the standalone V3 frontend workspace introduced in 3.0 Phase 00.

## Scope right now

- Vite + React 19 app scaffold
- Tailwind v4 styling pipeline
- shadcn-style source-owned UI primitives
- one placeholder shell route only

This workspace is intentionally independent from `/Users/alexcannon/Downloads/TTLEliteSeries/web` for now. The current 2.0 frontend remains the live product until the next Phase 00 step mounts `web-v3` at `/v3/*`.

## Commands

```bash
cd /Users/alexcannon/Downloads/TTLEliteSeries/web-v3
npm install
npm run dev
```

The workspace pins npm to a local cache via `.npmrc`, so it does not depend on the machine-wide npm cache being healthy.

The V3 shell is built with a `/v3/` base path, so in standalone dev you should open:

- [http://localhost:5174/v3/](http://localhost:5174/v3/)

Build verification:

```bash
cd /Users/alexcannon/Downloads/TTLEliteSeries/web-v3
npm run build
```
