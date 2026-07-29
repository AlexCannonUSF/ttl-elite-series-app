# MinIO Raw Store + CV Audit Buffer

Phase 04 uses MinIO as the S3-compatible object store for ingestion raw payloads
(`ttl-raw`) and Stream-CV evidence frames (`ttl-cv-audit`). It is the durable
backing store referenced by `rawPayloadRef` on every ingested event and by
`settlement_audit.evidence_refs` on Stream-CV contradictions.

## Dev

```bash
docker compose -f /Users/alexcannon/Downloads/TTLEliteSeries/infra/minio/compose.dev.yaml up -d
```

- S3 API: `http://localhost:9000`
- Console: `http://localhost:9001`
- Default credentials: `ttl-minio` / `ttl-minio-dev` (override with
  `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`).
- Data persists in the `ttl-minio-dev-data` named volume.

The `minio-init` sidecar runs once on bring-up. It creates both buckets,
disables anonymous access, and installs the lifecycle policies. Re-running
`docker compose up -d` is idempotent.

## Staging

```bash
docker compose -f /Users/alexcannon/Downloads/TTLEliteSeries/infra/minio/compose.staging.yaml up -d
```

Same image and bucket layout as dev. Override `TTL_MINIO_S3_PORT` and
`TTL_MINIO_CONSOLE_PORT` if `9000` / `9001` are already reserved on the host.
Data persists in the `ttl-minio-staging-data` named volume.

## Buckets

| Bucket         | Purpose                                                            | Lifecycle | Public access |
| -------------- | ------------------------------------------------------------------ | --------- | ------------- |
| `ttl-raw`      | Gzipped raw bodies of every ingested event (`{source}/{yyyy-mm-dd}/{correlationId}.json.gz`). | Expire after 14 days | None |
| `ttl-cv-audit` | Stream-CV evidence JPEGs written on contradictions. Referenced by `settlement_audit.evidence_refs`. | Expire after 30 days | None |

Lifecycle TTLs match Phase 04 §3 and the Scraper Spec §6.4. Adjust by editing
the `mc ilm import` payloads in `compose.*.yaml` and re-running the init
sidecar (`docker compose up minio-init`).

## Backend wiring

### Raw payload writer (Phase 04 item 4)

`RawPayloadStoringIngestionBus` decorates the active `IngestionBus`. For every
ingested event with a `correlationId` and no pre-existing `rawPayloadRef`, it
serializes the typed payload to JSON, gzips it, and uploads to
`ttl-raw` under the key `{source}/{yyyy-mm-dd}/{correlationId}.json.gz`. The
returned `rawPayloadRef` (`s3://ttl-raw/{source}/{yyyy-mm-dd}/{correlationId}`)
is stamped back onto the event before it is forwarded to the underlying bus,
so downstream sinks (`OddsSnapshotFactory`, `MirrorObservationFactory`, …)
persist the canonical reference into `raw_payload_ref` columns.

Toggle and credentials live in `application.properties`:

```properties
ttl.ingestion.raw-store.enabled=${TTL_RAW_STORE_ENABLED:false}
ttl.ingestion.raw-store.endpoint=${TTL_RAW_STORE_ENDPOINT:http://localhost:9000}
ttl.ingestion.raw-store.bucket=${TTL_RAW_STORE_BUCKET:ttl-raw}
ttl.ingestion.raw-store.access-key=${TTL_RAW_STORE_ACCESS_KEY:ttl-minio}
ttl.ingestion.raw-store.secret-key=${TTL_RAW_STORE_SECRET_KEY:ttl-minio-dev}
ttl.ingestion.raw-store.region=${TTL_RAW_STORE_REGION:us-east-1}
```

Defaults are off in dev and tests; flip `TTL_RAW_STORE_ENABLED=true` once the
MinIO compose is up. Upload failures and serialization failures are fail-open:
the event is forwarded with an empty `rawPayloadRef` and the failure is logged
under `[raw-store]`. Events whose `correlationId` is blank (e.g. periodic
`feed.health` pings) are forwarded unchanged.

### CV audit emitter (Phase 04 item 7)

The Stream-CV worker pushes recent JPEG frames into `CvAuditFrameBuffer`
(rolling per-match, default 10-frame cap). On a `ContradictionGuard` event,
`SettlementShadowAuditService.recordAttempt(...)` asks
`CvAuditEvidenceStore.uploadForContradiction(matchId, bundleAsOf)` to dump
the buffer to `ttl-cv-audit` under
`s3://ttl-cv-audit/<matchId>/<utcMinute>/<seq>.jpg`. The returned ref list is
JSON-serialised into `settlement_audit.evidence_refs`. The bucket's 30-day
lifecycle (provisioned by `compose.*.yaml`) auto-purges the evidence.

Toggle via `application.properties`:

```properties
ttl.cv-audit.enabled=${TTL_CV_AUDIT_ENABLED:false}
ttl.cv-audit.bucket=${TTL_CV_AUDIT_BUCKET:ttl-cv-audit}
ttl.cv-audit.maxFramesPerMatch=${TTL_CV_AUDIT_MAX_FRAMES_PER_MATCH:10}
```

Endpoint/credentials/region default to the same MinIO instance used by the
raw payload writer (`ttl.cv-audit.*` falls back to `ttl.ingestion.raw-store.*`).
Override individually if you split MinIO deployments per bucket.
