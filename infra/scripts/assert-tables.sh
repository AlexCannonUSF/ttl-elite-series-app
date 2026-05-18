#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./infra/scripts/assert-tables.sh <phase> [environment]

Examples:
  ./infra/scripts/assert-tables.sh p01
  ./infra/scripts/assert-tables.sh p01 staging

Datasource resolution order:
  1. FLYWAY_URL_<ENV>
  2. FLYWAY_URL
  3. SPRING_DATASOURCE_URL
  4. MYSQL_URL

User/password follow the same scoped fallback pattern with
FLYWAY_USER / FLYWAY_PASSWORD, then SPRING_DATASOURCE_*, then MYSQL_*.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

PHASE_KEY="${1:-p01}"
TARGET_ENV="${2:-local}"
ENV_KEY="$(printf '%s' "$TARGET_ENV" | tr '[:lower:]-' '[:upper:]_')"
PHASE_LABEL="$(printf '%s' "$PHASE_KEY" | tr '[:lower:]' '[:upper:]')"

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

resolve_mysql_bin() {
  if command -v mysql >/dev/null 2>&1; then
    printf '%s' "mysql"
    return 0
  fi
  if command -v mariadb >/dev/null 2>&1; then
    printf '%s' "mariadb"
    return 0
  fi
  return 1
}

build_phase_tables() {
  case "$PHASE_KEY" in
    p01|P01)
      printf '%s\n' \
        "odds_snapshot" \
        "mirror_observation" \
        "stream_observation" \
        "feed_health_sample" \
        "ingest_dlq"
      ;;
    *)
      echo "Unsupported phase key: $PHASE_KEY" >&2
      exit 1
      ;;
  esac
}

JDBC_URL="$(resolve_var FLYWAY_URL "${SPRING_DATASOURCE_URL:-${MYSQL_URL:-}}")"
DB_USER="$(resolve_var FLYWAY_USER "${SPRING_DATASOURCE_USERNAME:-${MYSQL_USER:-sa}}")"
DB_PASSWORD="$(resolve_var FLYWAY_PASSWORD "${SPRING_DATASOURCE_PASSWORD:-${MYSQL_PASSWORD:-}}")"

if [[ -z "${JDBC_URL}" ]]; then
  echo "Missing datasource URL. Set FLYWAY_URL, SPRING_DATASOURCE_URL, or MYSQL_URL." >&2
  exit 1
fi

MYSQL_BIN="$(resolve_mysql_bin || true)"
if [[ -z "${MYSQL_BIN}" ]]; then
  echo "Missing mysql client. Install mysql or mariadb CLI to run this check." >&2
  exit 1
fi

NORMALIZED_URL="${JDBC_URL#jdbc:}"
if [[ ! "${NORMALIZED_URL}" =~ ^mysql://([^/:?]+)(:([0-9]+))?/([^?]+) ]]; then
  echo "Unsupported datasource URL for assert-tables.sh: ${JDBC_URL}" >&2
  echo "Expected a MySQL JDBC URL like jdbc:mysql://host:3306/database" >&2
  exit 1
fi

DB_HOST="${BASH_REMATCH[1]}"
DB_PORT="${BASH_REMATCH[3]:-3306}"
DB_NAME="${BASH_REMATCH[4]}"

TABLES=()
while IFS= read -r table_name; do
  [[ -n "${table_name}" ]] && TABLES+=("${table_name}")
done < <(build_phase_tables)

if [[ "${#TABLES[@]}" -eq 0 ]]; then
  echo "No tables configured for phase ${PHASE_KEY}." >&2
  exit 1
fi

QUERY=""
for table_name in "${TABLES[@]}"; do
  if [[ -n "${QUERY}" ]]; then
    QUERY+=$'\nUNION ALL\n'
  fi
  QUERY+="SELECT '${table_name}' AS table_name,"
  QUERY+=" CASE WHEN EXISTS ("
  QUERY+="SELECT 1 FROM information_schema.tables"
  QUERY+=" WHERE table_schema = DATABASE() AND table_name = '${table_name}'"
  QUERY+=" ) THEN 1 ELSE 0 END AS table_exists,"
  QUERY+=" CASE WHEN EXISTS ("
  QUERY+="SELECT 1 FROM information_schema.tables"
  QUERY+=" WHERE table_schema = DATABASE() AND table_name = '${table_name}'"
  QUERY+=" ) THEN (SELECT COUNT(*) FROM ${table_name}) ELSE 0 END AS row_count"
done
QUERY+=";"

MYSQL_ARGS=(
  "--host=${DB_HOST}"
  "--port=${DB_PORT}"
  "--user=${DB_USER}"
  "--database=${DB_NAME}"
  "--batch"
  "--raw"
  "--skip-column-names"
  "--execute=${QUERY}"
)

echo "Phase ${PHASE_LABEL} table check against ${TARGET_ENV} (${DB_HOST}:${DB_PORT}/${DB_NAME})"
echo
printf '%-22s %-8s %-10s %-10s\n' "TABLE" "EXISTS" "ROWS" "STATUS"
printf '%-22s %-8s %-10s %-10s\n' "----------------------" "------" "----------" "----------"

failure_count=0
while IFS=$'\t' read -r table_name table_exists row_count; do
  exists_label="yes"
  status_label="ok"
  if [[ "${table_exists}" != "1" ]]; then
    exists_label="no"
    status_label="missing"
    failure_count=$((failure_count + 1))
  elif [[ "${row_count}" == "0" ]]; then
    status_label="empty"
    failure_count=$((failure_count + 1))
  fi

  printf '%-22s %-8s %-10s %-10s\n' "${table_name}" "${exists_label}" "${row_count}" "${status_label}"
done < <(MYSQL_PWD="${DB_PASSWORD}" "${MYSQL_BIN}" "${MYSQL_ARGS[@]}")

echo
if [[ "${failure_count}" -gt 0 ]]; then
  echo "Phase ${PHASE_LABEL} table assertion failed with ${failure_count} issue(s)." >&2
  exit 1
fi

echo "Phase ${PHASE_LABEL} table assertion passed."
