#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_ENV="${1:-local}"
ENV_KEY="$(printf '%s' "$TARGET_ENV" | tr '[:lower:]-' '[:upper:]_')"

resolve_var() {
  local base_name="$1"
  local fallback="${2:-}"
  local scoped_name="${base_name}_${ENV_KEY}"
  local value="${!scoped_name:-}"
  if [[ -n "${value}" ]]; then
    printf '%s' "$value"
    return 0
  fi
  value="${!base_name:-$fallback}"
  printf '%s' "$value"
}

FLYWAY_URL="$(resolve_var FLYWAY_URL "${SPRING_DATASOURCE_URL:-}")"
FLYWAY_USER="$(resolve_var FLYWAY_USER "${SPRING_DATASOURCE_USERNAME:-sa}")"
FLYWAY_PASSWORD="$(resolve_var FLYWAY_PASSWORD "${SPRING_DATASOURCE_PASSWORD:-}")"

if [[ -z "${FLYWAY_URL}" ]]; then
  echo "Missing datasource URL. Set FLYWAY_URL or FLYWAY_URL_${ENV_KEY}." >&2
  exit 1
fi

cd "$ROOT_DIR"
./mvnw -q -DskipTests compile \
  -Dflyway.url="${FLYWAY_URL}" \
  -Dflyway.user="${FLYWAY_USER}" \
  -Dflyway.password="${FLYWAY_PASSWORD}" \
  flyway:info
