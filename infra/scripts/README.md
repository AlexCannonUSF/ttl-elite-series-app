# Infra Scripts

This folder contains environment-facing operational scripts.

## Scripts

- `flyway-info.sh`
  - shows migration status for the selected environment
- `flyway-migrate.sh`
  - runs the numbered Flyway migrations for the selected environment
- `assert-tables.sh`
  - verifies that the phase tables exist and have rows
  - usage: `./infra/scripts/assert-tables.sh p01 staging`
  - exits non-zero if a required table is missing or still empty
  - currently supports MySQL JDBC URLs resolved from the same env chain as Flyway, with `MYSQL_*` fallbacks

## Environment variables

Each script accepts an environment name like `local` or `staging`.

It resolves credentials in this order:

1. `FLYWAY_URL_<ENV>`
2. `FLYWAY_URL`
3. `SPRING_DATASOURCE_URL`
4. `MYSQL_URL`

User/password follow the same pattern with `FLYWAY_USER*` / `FLYWAY_PASSWORD*`, then `SPRING_DATASOURCE_*`, then `MYSQL_*`.
