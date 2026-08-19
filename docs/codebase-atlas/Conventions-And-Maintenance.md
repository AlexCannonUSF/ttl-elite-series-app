# Conventions And Maintenance

This page defines how the repository should stay organized going forward.

## Documentation Structure

Use each lane for one purpose only:

- `/README.md`
  - repo-level entrypoint
  - how to run
  - where to look first
- `/docs/codebase-atlas`
  - source navigation and ownership
  - architecture, flows, file maps
- `/docs/ttlelite-series-2.0`
  - roadmap, release gate, bug-closure plans
- source-folder `README.md` files
  - local navigation once you are inside a specific area

## Folder Responsibilities

- `controller`
  - thin HTTP API surface
  - should delegate to services and return DTOs
- `service`
  - primary business logic and orchestration
- `domain`
  - persisted state and long-lived entities
- `repository`
  - persistence access only
- `dto`
  - API and service handoff contracts
- `scrape`
  - external-source fetching and parsing
- `analytics`, `model`, `projection`, `util`
  - support logic that should stay smaller and more focused than service orchestration
- `web/src/pages`
  - page-level product surfaces
- `web/src/lib/api.ts`
  - browser-side API boundary

## Directory Hygiene

- Keep Java package directories lowercase so git paths, package names, and imports stay aligned.
- Keep top-level source folders documented with a local `README.md`.
- Treat case-only path drift as a structural bug, not a cosmetic issue.

## When Adding New Code

If you add a new:

- controller: update the endpoint map
- service: update backend ownership docs if it creates a new workflow
- page: update frontend map and router docs
- package/folder: add or update the local `README.md`
- script: update `/scripts/README.md`
- test suite: update `/src/test/java/com/ttl/tabletennis/README.md`
- product/workflow doc: link it from the appropriate index

## Regenerating The Atlas

Run:

```bash
python3 ./scripts/generate_codebase_atlas.py
```

This rebuilds:

- backend file index
- endpoint map
- frontend file index
- tests and scripts index

Generated files should not be hand-edited unless you are fixing the generator.

## Naming Guidance

- Keep services workflow-oriented, not generic.
  - Good: `PaperTradingService`, `PlayerIdentityService`
  - Avoid: `HelperService`, `CommonService`
- Keep DTO names aligned with the layer that consumes them.
  - Live Studio API output should read like `PaperTradeBetDto`, `LiveStudioIntegrityDto`
- Keep pages named by surface, not by component style.
  - Good: `LiveOddsPage`, `AnalyticsPage`, `AdminPage`

## Recommended Refactoring Rule

If a class becomes hard to summarize in two or three bullets in this atlas, it is a signal that it may be doing too much and should be split.
