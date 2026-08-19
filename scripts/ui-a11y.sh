#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${TTLELITE_A11Y_BASE_URL:-http://127.0.0.1:5174}"
ARGS=("--base-url" "$BASE_URL")

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)
      BASE_URL="$2"
      ARGS=("--base-url" "$BASE_URL")
      shift 2
      ;;
    --routes|--out|--json|--settle-ms)
      VALUE="$2"
      if [[ "$1" != "--settle-ms" && "$VALUE" != /* ]]; then
        VALUE="$ROOT_DIR/$VALUE"
      fi
      ARGS+=("$1" "$VALUE")
      shift 2
      ;;
    --reduced-motion|--fail-on-serious|--fail-on-any)
      ARGS+=("$1")
      shift
      ;;
    *)
      ARGS+=("$1")
      shift
      ;;
  esac
done

cd "$ROOT_DIR"
node web-v3/scripts/a11y-audit.mjs "${ARGS[@]}"
