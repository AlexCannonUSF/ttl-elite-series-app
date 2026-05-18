# Infra SQL

This folder contains operator-facing SQL checks and baseline queries that support 3.0 release gates.

## Queries

- `clv_baseline.sql`
  - computes a conservative 7-day CLV baseline from `paper_trade_bet` and `odds_snapshot`
  - joins through sportsbook event identity (`external_event_id` / `locked_external_event_id`)
  - prefers the latest `CLOSED` snapshot for the bet side, then `SUSPENDED`, then latest available

## Expected usage

Run the query against the same database that backs the paper-trading and odds-snapshot tables.

Example:

```bash
mysql ... < infra/sql/clv_baseline.sql
```

Interpretation notes:

- `clv_baseline` is the average of `(closing implied probability - placed implied probability)`.
- `coverage_ratio` tells you how much of the 7-day bet window had a usable closing snapshot.
- A non-null CLV baseline with meaningful coverage is the Phase 01 gate target.
