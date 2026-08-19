# Process supervisor recipes for ttl-predict-py

The Python prediction service is started as a `nohup` process during dev
runs; it survives a closed terminal but not an OS reboot. These recipes
are the production-shape alternative — pick the one that matches your
target host.

| Host | File | Notes |
| --- | --- | --- |
| macOS dev / staging laptop | `com.ttl.predict-py.plist` | launchd; load with `launchctl load -w ~/Library/LaunchAgents/<file>`. |
| Linux staging / prod | `ttl-predict-py.service` | systemd; install in `/etc/systemd/system/` then `systemctl enable --now`. |

Both recipes ship with crash-loop protection (max 5 restarts in 60s)
and tee stdout/stderr to readable log paths.

The Spring backend is wrapped by `scripts/run-with-restart.sh` for dev;
prod backends should also use launchd / systemd / docker compose with
`restart: unless-stopped` set — exactly the policy that's now applied to
`infra/redis/compose.staging.yaml` and `infra/minio/compose.staging.yaml`.

## Why a script + two recipes?

The wrapper script (`run-with-restart.sh`) is the dev ergonomics path —
no `sudo`, no daemon-reload, no admin permissions. It runs in the
foreground so Ctrl-C still works. Per-environment supervisor recipes are
the prod path because the host is expected to restart the service across
reboots without a human present.

When deploying to a new environment, copy the appropriate recipe,
replace the `REPLACE_ME` paths, then enable via the install command in
the file's header comment.
