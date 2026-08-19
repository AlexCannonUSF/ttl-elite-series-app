# TTLElite Series 3.0 — Scraper & Data Ingestion Spec

**Status:** Draft v1.0 · **Parent:** `TTLElite-Series-3.0-Master-Plan.md`
**Covers:** Workstream C — Scraper & Data Ingestion Hardening
**Companion:** `TTLElite-Series-3.0-Stream-CV-Spec.md`, `TTLElite-Series-3.0-Score-Truth-Engine.md`

---

## 1. Goals

- Eliminate "no data when the market closes" by diversifying inputs.
- Capture data we are not capturing today: tick-level odds, line movement, closing lines, CLV.
- Make every feed self-monitoring (health SLA, circuit breaker, DLQ, replay).
- Persist raw payloads referenced by every event for forensic replay.
- Centralize ingestion onto a single bus with provenance; kill the hidden side-effect scraping that lives inside `PaperTradingService`.

---

## 2. Current State (as-is)

| Source | File | Method | Freq | Issues |
|---|---|---|---|---|
| Hard Rock odds (board) | `HardRockOddsScraper.java` (2,397 lines) | Jsoup HTML + hopeful JSON endpoint via `-Dhr.json=` | ~20 s (via `OddsValueScheduler`) | Regex-based parsing brittle; no WS; dies silently when page changes |
| Hard Rock scores (live/target) | Called from `PaperTradingService` | GraphQL (some paths), public tree (others) | inline during refresh | Brittle against `{count:N}` shapes; parser fragile |
| TT-Series results | `TtSeriesScraper.java` (1,306 lines) | Jsoup, configurable selectors | Scheduled + admin-triggered | Working; but only post-level granularity |
| TT-Series ELO rankings | `TtSeriesEloSyncService.java` | HTTP + parser | every 6 h | Working |

No centralized bus. No DLQ. No tick-level snapshot storage. No per-source health. Scrape logic tangled with placement in `PaperTradingService`.

---

## 3. Target Architecture

```
         ┌────────────────────────────── Sources ──────────────────────────────┐
         │                                                                     │
  Hard Rock GraphQL (WS + poll) ─┐                                             │
  Hard Rock Targeted Poller ─────┤                                             │
  Hard Rock Public Tree (2ndary) ┤                                             │
  Sofascore Mirror ──────────────┤                                             │
  AiScore Mirror ────────────────┤── FeedClient (common contract) ────────┐    │
  BetsAPI Mirror (optional paid) ┤                                         │    │
  TT-Series Post Scraper ────────┤                                         │    │
  TT-Series Player Page Scraper ─┤                                         │    │
  TT-Series H2H Page Scraper ────┤                                         │    │
  ITTF / WTT Ranking Refresh ────┤                                         │    │
  Stream-CV Worker ──────────────┘                                         │    │
                                                                           │    │
         ┌─── Ingestion Bus (Spring events → Redis Streams in Phase 04) ───┘    │
         │                                                                      │
         │  Topics: odds.updated, score.observed, result.confirmed,             │
         │          identity.updated, market.closed, stream.frame,              │
         │          ingest.error, feed.health                                   │
         │                                                                      │
         │  Provenance: {source, sourceVersion, observedAt, confidence,         │
         │               rawPayloadRef, correlationId}                          │
         │                                                                      │
         └─────── Subscribers ─────────┐                                        │
                  - Evidence Builder   │                                        │
                  - Odds Snapshot Sink │                                        │
                  - Identity Service   │                                        │
                  - Integrity Counters │                                        │
                  - DLQ Router         │                                        │
         ┌───────────────────────────────────────────────────────────────┐      │
         │ RawPayloadStore (MinIO / S3-compatible, keyed by rawPayloadRef)│      │
         └───────────────────────────────────────────────────────────────┘      │
         ┌───────────────────────────────────────────────────────────────┐      │
         │ DeadLetterQueue (JPA table, replayable, UI-visible in Ops)    │      │
         └───────────────────────────────────────────────────────────────┘      │
                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. FeedClient Contract

```java
public interface FeedClient<T> {
    SourceId source();

    /** For pollers. */
    List<IngestEvent<T>> pullOnce(PullContext ctx);

    /** For WS/SSE-capable sources. */
    default Optional<Flux<IngestEvent<T>>> stream(StreamContext ctx) { return Optional.empty(); }

    FeedHealth currentHealth();
    BackoffPolicy backoff();
    Set<Capability> capabilities();   // ODDS, SCORES, RESULTS, RANKINGS, STREAM_URL ...
}

public record IngestEvent<T>(
    SourceId source,
    String topic,
    Instant observedAt,
    double confidence,
    String correlationId,
    String rawPayloadRef,
    T payload
) { }
```

All clients decorated with:
- **Resilience4j CircuitBreaker** (`slidingWindowSize=60, failureRateThreshold=40%`).
- **Resilience4j RateLimiter** (per source).
- **Exponential backoff with jitter** on failure.
- **Micrometer timer** tagged `source, method, outcome`.

### 4.1 Health probe

Each feed client computes a rolling `FeedHealth`:

```java
public record FeedHealth(
    SourceId source,
    Instant lastSuccess,
    Instant lastFailure,
    double rollingSuccessRate5m,
    int inFlight,
    BackoffState backoffState,
    String lastError
) { }
```

Published on the `feed.health` topic every 1 s by default (configurable via `ttl.feed.health.emitFixedDelayMs`) and persisted to `feed_health_sample` every 30 s; consumed by the UI feed-health ribbon.

---

## 5. Per-Source Specs

### 5.1 HR-MKT — Hard Rock GraphQL Market

- **Endpoint:** GraphQL at the booker's exposed API path (already known in the app via `HardRockOddsScraper`). Explicit `Content-Type: application/json`, browser-like `User-Agent`, realistic `Accept-Language`.
- **Primary mode:** WebSocket subscription when the booker's production app uses one (inspect via browser DevTools). Poll fallback when WS is unavailable.
- **Poll cadence:** 1 s while markets open, 5 s while no live TT markets detected.
- **Capabilities:** `ODDS`, `SCORES`, `MARKET_STATE`, `BOOKER_EVENT_ID`.
- **Parser:** hand-rolled JSON→record mapping; no regex.
- **Output events:** `odds.updated`, `score.observed`, `market.closed`.
- **Change management:** version the GraphQL query; if a schema field disappears, emit `feed.degraded` and fall back to previous version until operator intervenes.

### 5.2 HR-TGT — Hard Rock Targeted-by-Event Poller

- **Trigger:** every tracked event gets a poller started at placement.
- **Cadence:** 2 s while in-play, 10 s after `market.closed` for up to 45 min or confirmed `FINISHED`.
- **Capabilities:** `SCORES`, `MARKET_STATE`, `COMPLETION_SIGNAL`.
- **Output events:** `score.observed` with `completionSignal=true` when the booker flags `resulted`/`matchCompleted`.

### 5.3 HR-TREE — Hard Rock Public Tree (discovery only)

- **Demoted:** no longer primary.
- **Role:** occasional sweep for newly posted TT matches we don't know about yet; output feeds Identity Service for discovery, not settlement.
- **Cadence:** 60 s.

### 5.4 SOFASCORE — Mirror

- **Endpoint:** Sofascore's public mobile-web JSON. Query by fixture ID and by category/date.
- **Cadence:** 10 s per tracked match, 60 s category sweep.
- **Capabilities:** `SCORES`, `POINT_BY_POINT`, `RESULTS`, `SERVER_INDICATOR` (when present).
- **Canonicalization:** maintain a `sofascore_player_alias` table mapping `Sofascore.playerId → Player.id`; seeded with fuzzy-match + manual curation.
- **Polite practice:** browser-like headers, no parallel fan-out, cache GETs aggressively, respect `Retry-After` 429s, identify honestly if/when asked.

### 5.5 AISCORE — Mirror

- **Endpoint:** AiScore mobile-web JSON.
- **Role:** redundancy for Sofa; only active for tracked events not covered by Sofa.

### 5.6 BETSAPI — Licensed Mirror (optional)

- **When enabled:** operator pays ~$30–80/mo license and sets `ttl.score-truth.integration.betsapi-enabled=true`.
- **Role:** high-trust Tier-2 feed, particularly for Polish and Czech TT studios where coverage is best.
- **Cadence:** per-match stream subscription when available; otherwise 5 s poll.

### 5.7 TTS-POST — TT-Series Post Scraper (retain)

- Same as today; Phase 00 adds Prometheus metrics + DLQ.

### 5.8 TTS-PLAYER — TT-Series Player Page Scraper (NEW)

- **Endpoint:** `GET https://www.tt-series.com/player/{slug}/` (or equivalent).
- **Parser:** extract per-player match log (date, opponent, result, tournament) with timestamps where present.
- **Role:** T4 confirmation. Most valuable for same-day same-opponent disambiguation.
- **Cadence:** on demand when `AmbiguityScorer` ≥ 0.3; plus 1×/h for active players in the session.

### 5.9 TTS-H2H — TT-Series H2H Page Scraper (NEW)

- **Endpoint:** `GET https://www.tt-series.com/h2h/{p1}/{p2}/` (or equivalent).
- **Parser:** chronological H2H history including multi-match same-day slates.
- **Role:** T4 confirmation, gold for disambiguating same-player same-day repeats.
- **Cadence:** on demand during settlement.

### 5.10 ITTF-WTT — Rankings Refresh

- Existing `TtSeriesEloSyncService` retained. Add a parallel WTT rankings fetcher for long-term drift features.

### 5.11 STREAM-CV — Computer-Vision Worker

- See `TTLElite-Series-3.0-Stream-CV-Spec.md`.

---

## 6. Data We Start Capturing

### 6.1 `OddsSnapshot` (tick-level)

```sql
CREATE TABLE odds_snapshot (
    id BIGSERIAL PRIMARY KEY,
    tracked_event_id VARCHAR(64) NOT NULL,
    booker_event_id VARCHAR(128),
    match_key VARCHAR(128),
    side VARCHAR(4) NOT NULL,           -- P1 | P2
    price_decimal DOUBLE PRECISION NOT NULL,
    implied_prob DOUBLE PRECISION NOT NULL,
    market_state VARCHAR(24) NOT NULL,  -- OPEN | SUSPENDED | CLOSED
    source_id VARCHAR(16) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(64),
    raw_payload_ref VARCHAR(128)
);

CREATE INDEX odds_snapshot_event_time ON odds_snapshot(tracked_event_id, observed_at DESC);
CREATE INDEX odds_snapshot_match_time ON odds_snapshot(match_key, observed_at DESC);
```

Unlocks:
- Line-movement features in `FeatureService`.
- Closing-line snapshot per bet (= last `OddsSnapshot` before `market.closed` for that event).
- CLV = (fair prob at closing) − (implied prob at placement).

### 6.2 `MirrorObservation`, `StreamObservation`

```sql
CREATE TABLE mirror_observation (
    id BIGSERIAL PRIMARY KEY,
    tracked_event_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(16) NOT NULL,       -- SOFASCORE | AISCORE | BETSAPI
    observed_at TIMESTAMPTZ NOT NULL,
    phase VARCHAR(16),
    games_p1 INT, games_p2 INT,
    points_p1 INT, points_p2 INT,
    server VARCHAR(4),
    completion_signal BOOLEAN,
    confidence DOUBLE PRECISION,
    payload_json JSONB NOT NULL,
    raw_payload_ref VARCHAR(128)
);
CREATE INDEX mirror_obs_event_time ON mirror_observation(tracked_event_id, observed_at DESC);

CREATE TABLE stream_observation (
    id BIGSERIAL PRIMARY KEY,
    tracked_event_id VARCHAR(64) NOT NULL,
    stream_url VARCHAR(256),
    observed_at TIMESTAMPTZ NOT NULL,
    games_p1 INT, games_p2 INT,
    points_p1 INT, points_p2 INT,
    server VARCHAR(4),
    ocr_confidence DOUBLE PRECISION,
    state_machine_passed BOOLEAN,
    frame_ref VARCHAR(256),            -- pointer into RawPayloadStore
    vlm_fallback_used BOOLEAN
);
CREATE INDEX stream_obs_event_time ON stream_observation(tracked_event_id, observed_at DESC);
```

### 6.3 `FeedHealthSample`

```sql
CREATE TABLE feed_health_sample (
    id BIGSERIAL PRIMARY KEY,
    source_id VARCHAR(16) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    rolling_success_rate_5m DOUBLE PRECISION,
    in_flight INT,
    backoff_state VARCHAR(16),
    last_error VARCHAR(256)
);
```

### 6.4 Retention

| Data | TTL |
|---|---|
| Raw frames (CV) | 7 days |
| Mirror raw payloads | 30 days |
| `OddsSnapshot` | 365 days |
| `MirrorObservation` / `StreamObservation` | 180 days |
| Derived features, evidence bundles, audit | indefinite |

Retention enforced via nightly pruning job.

---

## 7. Ingestion Bus

### 7.1 Phase 00 — In-process

Use Spring's `ApplicationEventPublisher` with asynchronous listeners on a bounded `ThreadPoolTaskExecutor` (`core=8, max=16, queue=200`). DLQ is a JPA table:

```sql
CREATE TABLE ingest_dlq (
    id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(64) NOT NULL,
    source_id VARCHAR(16) NOT NULL,
    payload_json JSONB NOT NULL,
    failure_count INT NOT NULL,
    last_error TEXT,
    next_retry_at TIMESTAMPTZ,
    arrived_at TIMESTAMPTZ NOT NULL
);
```

### 7.2 Phase 04 — Redis Streams

Swap the in-process bus for Redis Streams:
- Topics → streams (`ttl:odds`, `ttl:scores`, `ttl:results`, …).
- Consumer groups for subscribers.
- Built-in PEL makes DLQ semantics trivial.
- Enables external replay workers and future multi-host scale.

Kafka/Redpanda remain out of scope until we outgrow Redis Streams.

### 7.3 Replay

```
POST /api/ops/replay?from=2026-04-10T00:00Z&to=2026-04-10T06:00Z&sources=HR_MKT,HR_TGT&mode=shadow
```

Replays captured raw payloads through the ingestion bus. In `shadow` mode, subscribers write to `replay_*` shadow tables. In `live` mode (guarded), writes land in normal tables.

---

## 8. Raw Payload Store

- MinIO locally (`data/minio`) in dev, S3-compatible in prod.
- Object key schema: `{source}/{yyyy-mm-dd}/{correlationId}.json.gz`.
- `rawPayloadRef` on every event = `s3://ttl-raw/{source}/{yyyy-mm-dd}/{correlationId}`.
- Stored gzipped; typical raw GraphQL response ≈ 8–40 KB gzipped.

---

## 9. Identity & Canonicalization

- `CanonicalPlayerId` resolver maps source-specific IDs/names to an internal `Player.id`.
- Normalization pipeline: lowercase → remove diacritics → collapse whitespace → strip titles/seeds → Jaro-Winkler similarity against `PlayerAlias` table.
- Confidence threshold 0.92 for auto-accept; below that → queue for operator review in Admin.
- On conflict (two different `Player.id` suggested for the same source name), the `IdentityDriftDetector` raises `identity.drift` event.

---

## 10. Operator Surfaces

### 10.1 Ops Console `/ops`

- **Feed Health board** — per source: last success, success rate 5m, in-flight, backoff state, last error. Click a source → recent events, recent errors.
- **DLQ browser** — paginated, filter by source/topic, one-click retry, bulk requeue.
- **Replay runner** — time-window + source picker, mode (shadow/live), progress.
- **Scrape run explorer** (retained from `AdminPage`).

### 10.2 Metrics / Alerts

Prometheus metrics:
- `ttl_feed_success_total{source}`, `ttl_feed_failure_total{source, reason}`.
- `ttl_feed_inflight{source}`, `ttl_feed_latency_seconds_bucket`.
- `ttl_ingest_events_total{topic}`.
- `ttl_dlq_depth{topic}`.

Alerts (Grafana):
- Any Tier-1 feed `rolling_success_rate_5m < 0.8` for 5 min.
- DLQ depth > 500 for 10 min.
- Identity drift events per hour > 5.

---

## 11. Migration Plan

1. **Phase 00** — Introduce ingestion bus, DLQ table, raw payload store, `OddsSnapshot`. Wire existing scrapers to emit on the bus in addition to current behavior (no subscribers flip yet). Deliver `/api/ops/feeds/health`.
2. **Phase 01** — Settlement reads from subscribers only. Deploy TT-Series player + H2H scrapers + Sofascore adapter.
3. **Phase 03** — Stream-CV worker live.
4. **Phase 04** — Swap to Redis Streams. Build Ops Console.
5. **Phase 06+** — Optional BetsAPI integration, AiScore mirror second.

---

## 12. Open Questions

1. MinIO for raw payloads, or the filesystem in Phase 00 and MinIO later? Recommendation: filesystem-gz in 00, MinIO from 04.
2. Postgres vs MySQL for JSONB columns? Recommendation: migrate to Postgres in Phase 00 (H2 file-mode retained for unit tests only via Testcontainers).
3. Do we ever persist a Sofascore fixture catalog or always resolve on demand? Recommendation: persist a rolling 7-day cache keyed by date.
4. Is parallel fan-out to multiple mirrors acceptable, or strictly sequential with escalation? Recommendation: Tier-2 mirrors sequential (cost + politeness); Tier-1 parallel.

---

*End of Scraper & Data Ingestion Spec v1.0.*
