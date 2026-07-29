# Feature-flag promotion runbook

_Mechanical "what to do when a soak window passes" for each remaining v3 flag. Authoritative for §2 of the finish-checklist._

Every promotion is the same three steps:

1. Confirm the soak gate.
2. Edit `./features.yaml` (the script below does it idempotently with `sed`).
3. Restart the Spring backend so `FeatureFlagCatalog` reloads.

The lint step (`./scripts/lint-features.sh --enforce-expiry`) is wired into `release_gate.sh` + `.github/workflows/ci.yml`, so an expired flag will block any release before its promotion lands.

---

## Promotion ladder

| Flag | Path | Soak signal |
|---|---|---|
| `features.redis-streams` | `shadow → on` | 24 h with zero DLQ growth, zero partition lag > 50, zero ingestion-error log lines |
| `features.stream-cv` (precondition) | `off → shadow` | ROI templates + YOLO/OCR artefacts staged + route catalogue committed |
| `features.stream-cv` | `shadow → on` | 7 days of CV workers reporting `>=99%` per-match coverage on tracked matches |
| `features.score-truth` | `primary → primary-with-stream-cv-enforced` | `features.stream-cv == on` |

> Note: `features.score-truth` doesn't actually have a distinct `primary-with-stream-cv-enforced` state in the catalogue — once `stream-cv == on`, `ScoreTruthPrimaryService.enforcePostCloseStreamCvPolicy` automatically tightens because `evidence.streamObservations()` becomes non-empty. The "promotion" is just confirming the right downstream observation is present.

---

## 1. `redis-streams: shadow → on`

### Gate
```bash
# Should be HEALTHY for at least 24 h continuously, with active="RedisStreamsBus shadow"
curl -s http://localhost:8080/api/v3/ops/ingest | jq '.bus'

# Should be 0 across the window
curl -s http://localhost:8080/actuator/prometheus | grep ttl_ingest_dlq_depth
```

If both pass for 24+ hours:

### Promotion
```bash
# 1. flip the flag
sed -i.bak -e '/^  "features.redis-streams":$/,/^  "features\./{ s/state: "shadow"/state: "on"/; }' features.yaml
./scripts/lint-features.sh --enforce-expiry

# 2. restart backend (in the terminal running ./mvnw spring-boot:run)
#    Ctrl-C, then:
./mvnw spring-boot:run

# 3. verify the bus flipped to authoritative
curl -s http://localhost:8080/api/v3/ops/ingest | jq '.bus.activeBus'
# Expect: "RedisStreamsBus"  (no longer "RedisStreamsBus shadow")
```

### Rollback
```bash
sed -i.bak -e '/^  "features.redis-streams":$/,/^  "features\./{ s/state: "on"/state: "shadow"/; }' features.yaml
# Then restart Spring.
```

---

## 2. `stream-cv: off → shadow` (after staging)

### Precondition: stage the artefacts

These are not yet in the repo. Drop them in then promote:

```bash
mkdir -p models/stream-cv/{roi,yolo,ocr}
# Drop platform-specific ROI templates into models/stream-cv/roi/
# Drop YOLO weights into models/stream-cv/yolo/
# Drop OCR character classifier into models/stream-cv/ocr/

# Commit infra/stream-cv/route-catalog.yaml with the stream URLs per tracked platform.
```

### Gate
```bash
ls models/stream-cv/roi/   # at least one template per tracked platform
ls models/stream-cv/yolo/  # weights present
test -f infra/stream-cv/route-catalog.yaml
```

### Promotion
```bash
sed -i.bak -e '/^  "features.stream-cv":$/,/^  "features\./{ s/state: "off"/state: "shadow"/; }' features.yaml
./scripts/lint-features.sh --enforce-expiry
# restart backend

# verify a Stream-CV worker is registered
curl -s http://localhost:8080/api/v3/ops/feeds/streams | jq '.summary'
# Expect enabledWorkers >= totalWorkers > 0
```

---

## 3. `stream-cv: shadow → on`

### Gate
```bash
# After 7 days, every tracked match should have at least one stream observation
curl -s http://localhost:8080/actuator/prometheus | grep ttl_stream_cv_coverage_ratio
# Expect: >= 0.99 sustained for 7 days
```

### Promotion
Same `sed` pattern + restart:
```bash
sed -i.bak -e '/^  "features.stream-cv":$/,/^  "features\./{ s/state: "shadow"/state: "on"/; }' features.yaml
./scripts/lint-features.sh --enforce-expiry
```

This is the promotion that tightens `ScoreTruthPrimaryService.enforcePostCloseStreamCvPolicy` — once stream observations are required, `trackedAfterClose` bets without Stream-CV evidence will get held as `SCORE_BACKED_ONLY` rather than force-voided.

### Verification post-promotion
```bash
# Soak11 should now report streamCvCoverage gate as passing
curl -s http://localhost:8080/api/v3/ops/soak | jq '.streamCvCoverage'
```

---

## Idempotency

Every `sed` invocation in this runbook is a no-op if the target state is already set. Re-running won't double-flip.

The `--enforce-expiry` lint runs at every step; if any flag's expiry date has passed, the promotion is blocked until someone bumps the date in `features.yaml`. The 30-day-soon warning fires earlier (default lint mode) so this never sneaks up on a release night.

## What changes if a gate fails mid-promotion

- A failed lint exits non-zero — nothing is written to features.yaml beyond the `.bak` file.
- A failed restart leaves the prior JVM running (Spring boot fails fast on bad config). Roll back with the `sed` invocation in the section's "Rollback" subhead.
- `Soak11Monitor.refresh()` is idempotent and called every minute; the soak gauges and `/api/v3/ops/soak` reflect actual state at the next tick, regardless of where you are in this runbook.
