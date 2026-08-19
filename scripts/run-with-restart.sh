#!/usr/bin/env bash
# Wrap a long-running TTLElite process so it survives transient crashes.
#
# Usage:
#   scripts/run-with-restart.sh backend         # Spring backend via ./mvnw
#   scripts/run-with-restart.sh python          # ttl-predict-py via venv uvicorn
#   scripts/run-with-restart.sh -- ./foo --bar  # arbitrary command after --
#
# Behaviour:
#   - Restarts on non-zero exit
#   - Counts consecutive failures; bails after 5 in 60s so a config error
#     doesn't burn through your CPU in an infinite loop
#   - Forwards SIGINT / SIGTERM to the child so Ctrl-C still exits cleanly
#
# This is the minimal "supervisor" — for prod use launchd / systemd /
# docker compose. The script is here so a developer-machine kill doesn't
# require remembering the exact mvnw / uvicorn invocation each time.

set -uo pipefail

MAX_FAILURES_PER_MINUTE=5
BACKOFF_SECONDS=2

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-}"

case "$MODE" in
  backend)
    cd "$ROOT_DIR"
    CMD=(./mvnw -q spring-boot:run)
    LABEL="backend"
    ;;
  python)
    cd "$ROOT_DIR/ttl-predict-py"
    # shellcheck source=/dev/null
    source .venv/bin/activate
    CMD=(python3 -m uvicorn app.main:app --host 127.0.0.1 --port 8090 --log-level info)
    LABEL="ttl-predict-py"
    ;;
  --)
    shift
    if [ "$#" -eq 0 ]; then
      echo "usage: $0 -- <command> [args...]" >&2
      exit 2
    fi
    CMD=("$@")
    LABEL="${CMD[0]}"
    ;;
  *)
    echo "usage: $0 backend | python | -- <command> [args...]" >&2
    exit 2
    ;;
esac

declare -a failure_window=()
child_pid=""

terminate() {
  if [ -n "$child_pid" ] && kill -0 "$child_pid" 2>/dev/null; then
    kill -TERM "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  fi
  exit 0
}
trap terminate INT TERM

while true; do
  echo "[run-with-restart] starting $LABEL (cmd: ${CMD[*]})"
  "${CMD[@]}" &
  child_pid=$!
  wait "$child_pid"
  exit_code=$?
  child_pid=""

  if [ "$exit_code" -eq 0 ]; then
    echo "[run-with-restart] $LABEL exited cleanly; not restarting"
    exit 0
  fi

  now="$(date +%s)"
  failure_window+=("$now")
  while [ "${#failure_window[@]}" -gt 0 ] && [ $((now - ${failure_window[0]})) -gt 60 ]; do
    failure_window=("${failure_window[@]:1}")
  done
  fail_count="${#failure_window[@]}"
  echo "[run-with-restart] $LABEL exited with code $exit_code; failures in last 60s = $fail_count"

  if [ "$fail_count" -ge "$MAX_FAILURES_PER_MINUTE" ]; then
    echo "[run-with-restart] $LABEL failed $fail_count times in 60s — bailing out so a config error doesn't loop forever" >&2
    exit 1
  fi

  sleep "$BACKOFF_SECONDS"
done
