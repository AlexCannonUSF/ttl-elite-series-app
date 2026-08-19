#!/usr/bin/env bash
set -euo pipefail

fixture="${1:-all}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repo_root"
if [[ "$fixture" == "all" ]]; then
  exec ./mvnw -Dtest=StreamCvReplayFixtureTests test
fi

exec ./mvnw -Dtest=StreamCvReplayFixtureTests -Dttl.streamCv.replayFixture="$fixture" test
