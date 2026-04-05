#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-${1:-http://localhost:8080}}"
STRATEGY="${TTL_STRATEGY:-conservative}"
MODEL="${TTL_MODEL:-ENSEMBLE}"
STARTING_BANKROLL="${TTL_STARTING_BANKROLL:-1000}"

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

print_step() {
  printf '\n[%s] %s\n' "$(date '+%H:%M:%S')" "$1"
}

require_cmd curl
require_cmd jq

print_step "Resetting Live Studio session with clear history"
reset_payload="$(post_json "$BASE_URL/api/live-studio/reset?startingBankroll=$STARTING_BANKROLL&clearHistory=true")"
reset_id="$(printf '%s' "$reset_payload" | jq -r '.sessionId')"
if [[ -z "$reset_id" || "$reset_id" == "null" ]]; then
  echo "Reset did not return a sessionId" >&2
  exit 1
fi

print_step "Checking session snapshot"
session_payload="$(fetch_json "$BASE_URL/api/live-studio/session")"
session_id="$(printf '%s' "$session_payload" | jq -r '.sessionId')"
if [[ "$session_id" != "$reset_id" ]]; then
  echo "Session mismatch after reset: reset=$reset_id current=$session_id" >&2
  exit 1
fi

print_step "Checking integrity counters"
integrity_payload="$(fetch_json "$BASE_URL/api/live-studio/integrity")"
integrity_nonzero="$(printf '%s' "$integrity_payload" | jq '[.trackedObservations,.boardObservations,.scoreFeedObservations,.trackedAfterCloseObservations,.scoreBackedSettlements,.targetedCompletionSettlements,.officialResultSettlements,.databaseSettlements,.heuristicSettlements,.voidedSettlements] | map(select(. != 0)) | length')"
if [[ "$integrity_nonzero" != "0" ]]; then
  echo "Integrity counters were not zero after clear-history reset" >&2
  printf '%s\n' "$integrity_payload" | jq
  exit 1
fi

print_step "Running one sync"
sync_payload="$(post_json "$BASE_URL/api/live-studio/sync?strategy=$STRATEGY&modelVersion=$MODEL")"
sync_session_id="$(printf '%s' "$sync_payload" | jq -r '.session.sessionId')"
if [[ "$sync_session_id" != "$reset_id" ]]; then
  echo "Sync attached to unexpected session: reset=$reset_id sync=$sync_session_id" >&2
  exit 1
fi

print_step "Inspecting open bets"
open_payload="$(fetch_json "$BASE_URL/api/live-studio/open-bets")"

invalid_visible_upcoming="$(printf '%s' "$open_payload" | jq '[.[] | select(.lastObservationDisplayed == true and (.lastObservedPhase // "") == "UPCOMING" and ((.lastObservedScore // "") | length) == 0 and .trackingState != "OPEN_PENDING_SCORE")] | length')"
if [[ "$invalid_visible_upcoming" != "0" ]]; then
  echo "Found visible upcoming bets that are not OPEN_PENDING_SCORE" >&2
  printf '%s\n' "$open_payload" | jq '[.[] | select(.lastObservationDisplayed == true and (.lastObservedPhase // "") == "UPCOMING" and ((.lastObservedScore // "") | length) == 0)]'
  exit 1
fi

invalid_tracked_after_close="$(printf '%s' "$open_payload" | jq '[.[] | select(.lastObservationDisplayed == true and .trackedAfterClose == true)] | length')"
if [[ "$invalid_tracked_after_close" != "0" ]]; then
  echo "Found visible bets incorrectly marked trackedAfterClose" >&2
  printf '%s\n' "$open_payload" | jq '[.[] | select(.lastObservationDisplayed == true and .trackedAfterClose == true)]'
  exit 1
fi

print_step "Smoke summary"
session_after_sync="$(fetch_json "$BASE_URL/api/live-studio/session")"
printf '%s\n' "$session_after_sync" | jq '{sessionId,currentBankroll,totalBets,openBets,lastSyncAt}'
printf '%s\n' "$sync_payload" | jq '{rowsScanned,betsPlaced,betsSkipped,betsSettled,betsVoided}'
printf '%s\n' "$open_payload" | jq 'map({id,sideName,trackingState,trackedAfterClose,lastObservedPhase,lastObservedScore,lastObservationDisplayed})'

print_step "Live Studio smoke check passed"
