#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/Users/alexcannon/Downloads/TTLEliteSeries"
WEB_DIR="$ROOT_DIR/web"
BASE_URL="${BASE_URL:-http://localhost:8080}"

RUN_CORE_TESTS="${TTL_RUN_CORE_TESTS:-true}"
RUN_SUPPORT_TESTS="${TTL_RUN_SUPPORT_TESTS:-true}"
RUN_FRONTEND_BUILD="${TTL_RUN_FRONTEND_BUILD:-true}"
RUN_SMOKE="${TTL_RUN_SMOKE:-true}"
RUN_SETTLEMENT_WATCH="${TTL_RUN_SETTLEMENT_WATCH:-false}"

WATCH_LOOPS="${TTL_WATCH_LOOPS:-12}"
WATCH_SLEEP_SECONDS="${TTL_WATCH_SLEEP_SECONDS:-10}"
TARGET_BET_ID="${TTL_TARGET_BET_ID:-}"
TARGET_SIDE_NAME="${TTL_TARGET_SIDE_NAME:-}"

require_cmd() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    echo "Missing required command: $name" >&2
    exit 1
  fi
}

print_step() {
  printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$1"
}

run_core_tests() {
  print_step "Running core backend release gate"
  (
    cd "$ROOT_DIR"
    ./mvnw -Dtest=PaperTradingServiceTests,OddsValueEngineServiceTests,PredictionModelServiceTests,FeatureServiceTests test
  )
}

run_support_tests() {
  print_step "Running support backend release gate"
  (
    cd "$ROOT_DIR"
    ./mvnw -Dtest=HardRockOddsScraperTests,ScrapeMetricsTests,PlayerIdentityServiceTests,TtSeriesEloSyncServiceTests test
  )
}

run_frontend_build() {
  print_step "Running frontend build gate"
  (
    cd "$WEB_DIR"
    npm run build
  )
}

run_smoke() {
  print_step "Running Live Studio smoke gate"
  bash "$ROOT_DIR/scripts/live_studio_smoke.sh" "$BASE_URL"
}

run_settlement_watch() {
  print_step "Running settlement watch gate"
  local env_args=(
    "BASE_URL=$BASE_URL"
    "TTL_WATCH_LOOPS=$WATCH_LOOPS"
    "TTL_WATCH_SLEEP_SECONDS=$WATCH_SLEEP_SECONDS"
  )

  if [[ -n "$TARGET_BET_ID" ]]; then
    env_args+=("TTL_TARGET_BET_ID=$TARGET_BET_ID")
  fi
  if [[ -n "$TARGET_SIDE_NAME" ]]; then
    env_args+=("TTL_TARGET_SIDE_NAME=$TARGET_SIDE_NAME")
  fi

  env "${env_args[@]}" bash "$ROOT_DIR/scripts/live_settlement_watch.sh" "$BASE_URL"
}

print_summary() {
  print_step "Release gate summary"
  cat <<EOF
Base URL: $BASE_URL
Core backend tests:   $RUN_CORE_TESTS
Support backend tests:$RUN_SUPPORT_TESTS
Frontend build:       $RUN_FRONTEND_BUILD
Live smoke:           $RUN_SMOKE
Settlement watch:     $RUN_SETTLEMENT_WATCH

Release gate completed successfully.
EOF
}

require_cmd bash
require_cmd curl
require_cmd jq
require_cmd npm

run_feature_flag_lint() {
  print_step "Feature-flag lint + expiry enforcement"
  (
    cd "$ROOT_DIR"
    ./scripts/lint-features.sh --enforce-expiry
  )
}

# Always runs (cheap, deterministic, no infra dep) so an expired flag
# stops release-gate before the slower test/build steps fire.
run_feature_flag_lint

if [[ "$RUN_CORE_TESTS" == "true" ]]; then
  run_core_tests
fi
if [[ "$RUN_SUPPORT_TESTS" == "true" ]]; then
  run_support_tests
fi
if [[ "$RUN_FRONTEND_BUILD" == "true" ]]; then
  run_frontend_build
fi
if [[ "$RUN_SMOKE" == "true" ]]; then
  run_smoke
fi
if [[ "$RUN_SETTLEMENT_WATCH" == "true" ]]; then
  run_settlement_watch
fi

print_summary
