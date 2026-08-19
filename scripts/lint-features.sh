#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Allow callers to point at an alternate features.yaml. Used by tests in
# tests/scripts/test_lint_features.py and by anyone running release_gate.sh
# against a deploy preview's catalogue.
FEATURES_FILE="${FEATURES_FILE_OVERRIDE:-${ROOT_DIR}/features.yaml}"
MODE="${1:-}"

python3 - "$FEATURES_FILE" "$MODE" <<'PY'
from __future__ import annotations

import datetime as dt
import sys
from pathlib import Path


REQUIRED_FLAGS = {
    "features.canonicaliser",
    "features.stream-cv",
    "features.score-truth",
    "features.predict-v3",
    "features.ui-shell-v3",
    "features.redis-streams",
    "features.stake-policy-v3",
}
REQUIRED_FIELDS = {"owner", "expires_on", "state", "description"}
ALLOWED_MODES = {"", "--enforce-expiry"}


def parse_scalar(raw: str) -> str:
    value = raw.strip()
    if value.startswith(("'", '"')) and value.endswith(("'", '"')) and len(value) >= 2:
        return value[1:-1]
    return value


def parse_features_yaml(path: Path) -> dict[str, dict[str, object]]:
    features: dict[str, dict[str, object]] = {}
    current_flag: str | None = None
    current_list_key: str | None = None
    in_features = False

    for line_no, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped_line = raw_line.strip()
        if not stripped_line or stripped_line.startswith("#"):
            continue

        indent = len(raw_line) - len(raw_line.lstrip(" "))

        if indent == 0:
            current_list_key = None
            current_flag = None
            in_features = stripped_line == "features:"
            continue

        if not in_features:
            continue

        if indent == 2 and stripped_line.endswith(":"):
            current_flag = parse_scalar(stripped_line[:-1])
            features[current_flag] = {}
            current_list_key = None
            continue

        if indent == 4 and ":" in stripped_line and current_flag:
            key, raw_value = stripped_line.split(":", 1)
            key = key.strip()
            raw_value = raw_value.strip()
            if raw_value == "":
                features[current_flag][key] = []
                current_list_key = key
            else:
                features[current_flag][key] = parse_scalar(raw_value)
                current_list_key = None
            continue

        if indent == 6 and stripped_line.startswith("- ") and current_flag and current_list_key:
            target = features[current_flag].setdefault(current_list_key, [])
            if not isinstance(target, list):
                raise ValueError(
                    f"{path}:{line_no}: expected list for {current_flag}.{current_list_key}"
                )
            target.append(parse_scalar(stripped_line[2:]))
            continue

        raise ValueError(f"{path}:{line_no}: unsupported YAML structure: {raw_line}")

    return features


def main() -> int:
    path = Path(sys.argv[1])
    mode = sys.argv[2] if len(sys.argv) > 2 else ""

    if mode not in ALLOWED_MODES:
        print(f"Unsupported mode: {mode}", file=sys.stderr)
        return 2

    if not path.exists():
        print(f"Missing features file: {path}", file=sys.stderr)
        return 1

    try:
        features = parse_features_yaml(path)
    except Exception as exc:  # noqa: BLE001
        print(f"Failed to parse {path}: {exc}", file=sys.stderr)
        return 1

    errors: list[str] = []
    upcoming: list[str] = []

    missing_flags = sorted(REQUIRED_FLAGS.difference(features.keys()))
    extra_flags = sorted(set(features.keys()).difference(REQUIRED_FLAGS))

    if missing_flags:
        errors.append("Missing required flags: " + ", ".join(missing_flags))

    today = dt.date.today()
    warning_window = dt.timedelta(days=30)

    for flag in sorted(REQUIRED_FLAGS.intersection(features.keys())):
        payload = features[flag]
        missing_fields = sorted(REQUIRED_FIELDS.difference(payload.keys()))
        if missing_fields:
            errors.append(f"{flag}: missing required fields: {', '.join(missing_fields)}")
            continue

        owner = str(payload["owner"]).strip()
        state = str(payload["state"]).strip()
        description = str(payload["description"]).strip()

        if not owner:
            errors.append(f"{flag}: owner must not be blank")
        if not state:
            errors.append(f"{flag}: state must not be blank")
        if not description:
            errors.append(f"{flag}: description must not be blank")

        allowed_states = payload.get("allowed_states")
        if isinstance(allowed_states, list) and allowed_states:
            if state not in {str(item) for item in allowed_states}:
                errors.append(f"{flag}: state '{state}' is not in allowed_states")

        try:
            expires_on = dt.date.fromisoformat(str(payload["expires_on"]))
        except ValueError:
            errors.append(f"{flag}: expires_on must be ISO-8601 YYYY-MM-DD")
            continue

        if expires_on < today:
            errors.append(f"{flag}: expires_on {expires_on.isoformat()} is in the past")
        elif expires_on - today <= warning_window:
            days_left = (expires_on - today).days
            upcoming.append(
                f"{flag}: expires in {days_left} day(s) on {expires_on.isoformat()} — renew or remove soon"
            )

        if mode == "--enforce-expiry" and expires_on <= today:
            errors.append(f"{flag}: expired flag must be renewed or removed before release")

    if extra_flags:
        print("Note: additional non-gated flags present: " + ", ".join(extra_flags))

    for warning in upcoming:
        print(f"warning: {warning}")

    if errors:
        print("Feature flag lint failed:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    print(
        f"Feature flag lint passed for {len(features)} flags in {path.name} "
        f"(required set: {len(REQUIRED_FLAGS)})."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
PY
