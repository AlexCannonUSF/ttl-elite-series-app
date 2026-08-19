#!/usr/bin/env bash
set -euo pipefail

template_id="${1:-new.template.v1}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool_dir="${repo_root}/tools/cv-template-builder"
tool_path="${tool_dir}/index.html"
port="${CV_TEMPLATE_BUILDER_PORT:-8765}"

if [[ ! -f "$tool_path" ]]; then
  echo "Missing cv-template-builder at ${tool_path}" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to serve cv-template-builder" >&2
  exit 1
fi

url="$(python3 - "$port" "$template_id" <<'PY'
from urllib.parse import quote
import sys

port = int(sys.argv[1])
template_id = quote(sys.argv[2], safe="")
print(f"http://127.0.0.1:{port}/?templateId={template_id}")
PY
)"

cd "$tool_dir"
python3 -m http.server "$port" --bind 127.0.0.1 >/tmp/ttl-cv-template-builder.log 2>&1 &
server_pid=$!
trap 'kill "$server_pid" >/dev/null 2>&1 || true' EXIT

if command -v open >/dev/null 2>&1; then
  open "$url"
else
  echo "$url"
fi

echo "Serving cv-template-builder at ${url}"
echo "Press Ctrl-C to stop."
wait "$server_pid"
