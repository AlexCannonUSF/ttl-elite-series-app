#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-${1:-http://localhost:8080}}"
STRATEGY="${TTL_STRATEGY:-conservative}"
MODEL="${TTL_MODEL:-ENSEMBLE}"
WATCH_LOOPS="${TTL_WATCH_LOOPS:-30}"
SLEEP_SECONDS="${TTL_WATCH_SLEEP_SECONDS:-20}"
TAPE_LIMIT="${TTL_TAPE_LIMIT:-10}"
TARGET_BET_ID="${TTL_TARGET_BET_ID:-}"
TARGET_SIDE_NAME="${TTL_TARGET_SIDE_NAME:-}"

require_cmd() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    echo "Missing required command: $name" >&2
    exit 1
  fi
}

fetch_json() {
  local url="$1"
  curl -fsS "$url"
}

post_json() {
  local url="$1"
  curl -fsS -X POST "$url"
}

target_filter() {
  if [[ -n "$TARGET_BET_ID" ]]; then
    printf '.[] | select(.id == %s)' "$TARGET_BET_ID"
    return
  fi
  if [[ -n "$TARGET_SIDE_NAME" ]]; then
    printf '.[] | select(.sideName == "%s")' "$TARGET_SIDE_NAME"
    return
  fi
  printf '.[]'
}

print_step() {
  printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$1"
}

require_cmd curl
require_cmd jq

settlement_seen=0

for ((i=1; i<=WATCH_LOOPS; i++)); do
  print_step "Watch cycle $i/$WATCH_LOOPS"
  post_json "$BASE_URL/api/live-studio/sync?strategy=$STRATEGY&modelVersion=$MODEL" >/dev/null

  session_payload="$(fetch_json "$BASE_URL/api/live-studio/session")"
  open_payload="$(fetch_json "$BASE_URL/api/live-studio/open-bets")"
  settled_payload="$(fetch_json "$BASE_URL/api/live-studio/settled-tape?limit=$TAPE_LIMIT")"
  integrity_payload="$(fetch_json "$BASE_URL/api/live-studio/integrity")"

  echo "--- session ---"
  printf '%s\n' "$session_payload" | jq '{sessionId,currentBankroll,totalBets,openBets,lastSyncAt}'

  echo "--- target open bets ---"
  printf '%s\n' "$open_payload" | jq "[ $(target_filter) | {id,sideName,trackingState,trackedAfterClose,lastObservedPhase,lastObservedScore,lastObservationDisplayed,lastObservationResulted,lastMatchCompleted,lastObservedAt} ]"

  echo "--- settled tape ---"
  printf '%s\n' "$settled_payload" | jq 'map({id,sideName,status,settlementSource,settlementReason,lastObservedScore,settledAt})'

  echo "--- integrity ---"
  printf '%s\n' "$integrity_payload" | jq '{trackedAfterCloseObservations,targetedCompletionSettlements,officialResultSettlements,databaseSettlements,heuristicSettlements,voidedSettlements}'

  settled_count="$(printf '%s\n' "$settled_payload" | jq 'length')"
  if [[ "$settled_count" -gt 0 ]]; then
    settlement_seen=1
    print_step "Settlement observed"
    break
  fi

  if [[ "$i" -lt "$WATCH_LOOPS" ]]; then
    sleep "$SLEEP_SECONDS"
  fi
done

if [[ "$settlement_seen" -eq 1 ]]; then
  exit 0
fi

print_step "Watch ended without a settlement"
exit 0
