# Scripts Guide

This folder contains operational helper scripts for smoke checks and release gating.

## Scripts

- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_studio_smoke.sh`](live_studio_smoke.sh)
  - resets the paper session and verifies clean post-reset Live Studio behavior
- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts/live_settlement_watch.sh`](live_settlement_watch.sh)
  - watches a live session for state transitions and settlements
- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts/release_gate.sh`](release_gate.sh)
  - runs the standard release gate in one command
- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts/lint-features.sh`](lint-features.sh)
  - validates the top-level `features.yaml` contract used by the 3.0 rollout plan
- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts/ui-a11y.sh`](ui-a11y.sh)
  - runs the Phase 07 axe-core WCAG audit against canonical v3 routes
- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts/cv-replay.sh`](cv-replay.sh)
  - runs the Stream-CV replay fixture gate (`all` or one fixture id)
- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts/cv-template-new.sh`](cv-template-new.sh)
  - opens `tools/cv-template-builder` with a template id seed
- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts/generate_codebase_atlas.py`](generate_codebase_atlas.py)
  - rebuilds the generated codebase indexes

## Related infra scripts

- `/Users/alexcannon/Downloads/TTLEliteSeries/infra/scripts/flyway-info.sh`
  - migration status for `local`, `staging`, or any env wired via `FLYWAY_URL_*`
- `/Users/alexcannon/Downloads/TTLEliteSeries/infra/scripts/flyway-migrate.sh`
  - runs numbered Flyway migrations without needing the app to boot first
- `/Users/alexcannon/Downloads/TTLEliteSeries/infra/scripts/assert-tables.sh`
  - validates that the phase tables exist and are actively populating after promotion

## Recommended Order

1. `python3 ./scripts/generate_codebase_atlas.py`
2. `./scripts/lint-features.sh`
3. `./scripts/ui-a11y.sh --fail-on-any`
4. `./scripts/cv-replay.sh all`
5. `./scripts/live_studio_smoke.sh`
6. `./scripts/live_settlement_watch.sh`
7. `./scripts/release_gate.sh`
