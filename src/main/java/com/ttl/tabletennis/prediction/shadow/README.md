# Prediction shadow (Phase 04 item 11)

The shadow path is the Java side of the v3 prediction rollout. Every
`PredictionFacade.predict(...)` call still serves the v2 result, and a
deterministic fraction (5% by default) of those calls is mirrored to the
`/v1/blend` endpoint on `ttl-predict-py`. The comparison row is persisted
to `prediction_diff_log` so we can verify v3 calibration on real traffic
before promoting.

## Wiring

```
PredictionFacade.predict
  └── PredictionShadowService.shadow
        ├── PredictionShadowSampler.shouldShadow   (5% by default, deterministic FNV-1a)
        ├── BlenderClient.score                    (HTTP POST to ttl-predict-py)
        └── PredictionDiffLogRepository.save       (async via ttlPredictionShadowExecutor)
```

## Toggles

| Property | Default | Purpose |
| --- | --- | --- |
| `ttl.predict-v3.enabled` | `false` | Master switch for the HTTP client. When false the bean is `DisabledBlenderClient`. |
| `features.predict-v3` (flag) | `off` | Cluster-wide rollout state (`off / shadow / on`). The service runs only when `shadow` or `on`. |
| `ttl.predict-v3.endpoint` | `http://localhost:8090/v1/blend` | Python service URL. |
| `ttl.predict-v3.shadowRate` | `0.05` | Fraction of predictions mirrored to v3. |
| `ttl.predict-v3.requestTimeoutMs` | `750` | Per-call HTTP timeout. |
| `ttl.predict-v3.featureSchemaHash` | empty | Required to send real requests; mismatched hash is a hard error per spec §3.10. |

## Diff log

`prediction_diff_log` carries the v2 baseline, the v3 response, the absolute
probability gap, and the shadow status (`OK`, `SCHEMA_HASH_MISMATCH`,
`SERVICE_UNAVAILABLE`, `ERROR`, `DISABLED`). One row per shadowed
prediction; the `prediction_id` column is unique.

When the Python service is running both blenders (Phase 05 item 5 —
Variant B with-market sanity check), `/v1/blend` returns an extra
`sanity` block. The shadow service persists three more columns on the same
row: `v3_variant_b_model_version`, `v3_variant_b_p1_probability`, and
`variant_ab_abs_diff` — the §9.3 agreement metric. Queries can then watch
`mean(variant_ab_abs_diff) ≤ 0.04` for promotion decisions without
needing a separate table.

## Why deterministic sampling

The sampler hashes `(min(player_id), max(player_id), as_of_date)` so the
same matchup either always shadows or always skips. That makes it trivial
to find every diff row for a given match without dealing with run-to-run
randomness, and is symmetric in player order so swapping top/bot doesn't
change the bucket.
