"""Tests for scripts/lint-features.sh expiry-and-warning behaviour.

These run via plain pytest from the repo root and use temp fixtures via the
``FEATURES_FILE_OVERRIDE`` env var so they never touch the production
features.yaml. They lock the three modes the lint can land in:

- pass + clean (no warnings, no errors)
- pass + expiring-soon warning printed
- fail when a flag is already expired

Plus the ``--enforce-expiry`` mode is additive: same fail conditions plus the
"expired flag must be renewed or removed before release" line.
"""

from __future__ import annotations

import datetime as dt
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
LINT_SCRIPT = REPO_ROOT / "scripts" / "lint-features.sh"


def _flag(name: str, expires_on: dt.date, *, state: str = "off", allowed=("off", "on")) -> str:
    lines = [
        f'  "{name}":',
        '    owner: "Alex"',
        f'    expires_on: "{expires_on.isoformat()}"',
        f'    state: "{state}"',
        '    description: "test flag"',
        '    allowed_states:',
    ]
    for value in allowed:
        lines.append(f'      - "{value}"')
    return "\n".join(lines) + "\n"


def _make_fixture(tmp_path: Path, dates: dict[str, dt.date]) -> Path:
    fixture = tmp_path / "features-fixture.yaml"
    body = "schema_version: 1\nfeatures:\n"
    body += _flag("features.canonicaliser", dates["features.canonicaliser"], allowed=("off", "on"))
    body += _flag("features.stream-cv", dates["features.stream-cv"], allowed=("off", "shadow", "on"))
    body += _flag("features.score-truth", dates["features.score-truth"], allowed=("off", "shadow", "advisory", "primary"))
    body += _flag("features.predict-v3", dates["features.predict-v3"], allowed=("off", "shadow", "on"))
    body += _flag("features.ui-shell-v3", dates["features.ui-shell-v3"], allowed=("off", "shadow", "on"))
    body += _flag("features.redis-streams", dates["features.redis-streams"], allowed=("off", "shadow", "on"))
    body += _flag("features.stake-policy-v3", dates["features.stake-policy-v3"], allowed=("off", "shadow", "on"))
    fixture.write_text(body)
    return fixture


def _run(fixture: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(LINT_SCRIPT), *args],
        env={"FEATURES_FILE_OVERRIDE": str(fixture), "PATH": "/usr/bin:/bin:/usr/local/bin"},
        capture_output=True,
        text=True,
        cwd=str(REPO_ROOT),
    )


def test_clean_catalogue_passes_without_warnings(tmp_path: Path) -> None:
    far_future = dt.date.today() + dt.timedelta(days=400)
    dates = {flag: far_future for flag in (
        "features.canonicaliser",
        "features.stream-cv",
        "features.score-truth",
        "features.predict-v3",
        "features.ui-shell-v3",
        "features.redis-streams",
        "features.stake-policy-v3",
    )}
    fixture = _make_fixture(tmp_path, dates)

    result = _run(fixture)

    assert result.returncode == 0, result.stderr
    assert "warning:" not in result.stdout
    assert "Feature flag lint passed" in result.stdout


def test_expiring_within_30_days_prints_warning_but_passes(tmp_path: Path) -> None:
    soon = dt.date.today() + dt.timedelta(days=20)
    far = dt.date.today() + dt.timedelta(days=400)
    dates = {
        "features.canonicaliser": soon,
        "features.stream-cv": far,
        "features.score-truth": far,
        "features.predict-v3": far,
        "features.ui-shell-v3": far,
        "features.redis-streams": far,
        "features.stake-policy-v3": far,
    }
    fixture = _make_fixture(tmp_path, dates)

    result = _run(fixture)

    assert result.returncode == 0, result.stderr
    assert "warning: features.canonicaliser: expires in 20 day(s)" in result.stdout


def test_already_expired_flag_fails_default_lint(tmp_path: Path) -> None:
    expired = dt.date.today() - dt.timedelta(days=1)
    far = dt.date.today() + dt.timedelta(days=400)
    dates = {flag: far for flag in (
        "features.stream-cv",
        "features.score-truth",
        "features.predict-v3",
        "features.ui-shell-v3",
        "features.redis-streams",
        "features.stake-policy-v3",
    )}
    dates["features.canonicaliser"] = expired
    fixture = _make_fixture(tmp_path, dates)

    result = _run(fixture)

    assert result.returncode == 1
    assert "expires_on" in result.stderr
    assert "is in the past" in result.stderr


def test_enforce_expiry_adds_second_failure_line(tmp_path: Path) -> None:
    expired = dt.date.today() - dt.timedelta(days=1)
    far = dt.date.today() + dt.timedelta(days=400)
    dates = {flag: far for flag in (
        "features.stream-cv",
        "features.score-truth",
        "features.predict-v3",
        "features.ui-shell-v3",
        "features.redis-streams",
        "features.stake-policy-v3",
    )}
    dates["features.canonicaliser"] = expired
    fixture = _make_fixture(tmp_path, dates)

    result = _run(fixture, "--enforce-expiry")

    assert result.returncode == 1
    assert "expired flag must be renewed or removed before release" in result.stderr
