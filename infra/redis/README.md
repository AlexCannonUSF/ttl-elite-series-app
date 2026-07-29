# Redis Streams Runtime

Phase 04 uses Redis 7 as the durable ingestion bus for `IngestionBus` events.

## Dev

```bash
docker compose -f /Users/alexcannon/Downloads/TTLEliteSeries/infra/redis/compose.dev.yaml up -d
```

The dev container exposes Redis on `localhost:6379` and persists data in the `ttl-redis-dev-data` volume.

## Staging

```bash
docker compose -f /Users/alexcannon/Downloads/TTLEliteSeries/infra/redis/compose.staging.yaml up -d
```

Staging uses the same Redis 7 image and append-only persistence. Override `TTL_REDIS_PORT` if the host already reserves `6379`.

## Backend Selection

The backend keeps the in-process Spring event bus while `features.redis-streams=off`.

- `off`: `ApplicationEventIngestionBus`
- `shadow`: Spring events remain primary; events are mirrored to Redis Streams,
  decoded by the `ttl-app` consumer group, and acknowledged without redispatch
- `on`: `RedisStreamsBus`; the `ttl-app` consumer group reconstructs the typed
  event and dispatches it to the existing Spring event listeners before ACK

Redis stream keys are topic families such as `ttl:odds`, `ttl:scores`, `ttl:results`, `ttl:health`, and `ttl:identity`.

The consumer starts each missing group at `$`, so enabling shadow mode does not
replay an old local backlog. It first drains records already pending for its
consumer name, then reads new records. Decode or listener failures are written
to `ingest_dlq`; a record is acknowledged only after successful handling or a
successful DLQ write. Its poll loop runs on a dedicated scheduler thread, so a
long scrape or settlement task cannot stall Redis consumption.

```properties
ttl.ingestion.redis.consumer.group=ttl-app
ttl.ingestion.redis.consumer.name=ttl-app-1
ttl.ingestion.redis.consumer.batchSize=100
ttl.ingestion.redis.consumer.pollDelayMs=250
```

Use a unique consumer name per running backend replica. A flag promotion still
requires a backend restart because the selected `IngestionBus` is a Spring bean
created at application startup.

## Phase 04 producer cutover (item 3)

Every event-producing component publishes through the `IngestionBus` abstraction,
so the active backend is controlled by `features.redis-streams` alone. Producers
audited:

- Feed adapters: `HardRockFeedClient`, `HardRockTreeDiscovery`,
  `HardRockTargetedPoller`, `TtSeriesFeedClient`, `SofaScoreFeedClient`,
  `AiScoreFeedClient`, `BetsApiFeedClient`, `ItftWttFeedClient`,
  `AbstractJsonMirrorFeedClient`.
- Score-truth recovery: `StaleLiveRecoveryService` (`stale.live.detected`).
- Stream-CV: `StreamFrameIngestionEmitter` (`stream.frame`).

No production code publishes `IngestEvent`s directly via
`ApplicationEventPublisher`; the only remaining `@EventListener` usages outside
the ingestion subscribers are `ApplicationReadyEvent` lifecycle hooks.

Routing: `RedisStreamsBus` maps each topic to a stream family — `*.odds` →
`ttl:odds`, `*.score | *.frame | stale.live.*` → `ttl:scores`,
`*.result | *.ledger` → `ttl:results`, `*.health` → `ttl:health`,
`*.identity | *.ranking` → `ttl:identity`. Unknown topics fall back to
`ttl:<sanitized-topic>`.

`RedisIngestEventCodec` uses an explicit payload allow-list rather than Jackson
polymorphic class loading. Keep that allow-list and its tests in sync whenever a
new production `IngestEvent` payload type is introduced.
