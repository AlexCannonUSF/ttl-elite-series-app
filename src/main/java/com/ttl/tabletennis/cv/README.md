# CV Package

This package is the backend-side scaffold for the 3.0 Stream-CV path.

## Phase 03 state

- `StreamRouter`
  - resolves direct match streams, Hard Rock hints, and hardened `stream_routes.yaml` overrides
  - accepts route aliases such as `event_code`, `table`, `url_template`, and `roi_template_id`
  - expands `${ENV_OR_PROPERTY:fallback}` placeholders in operator-edited URLs
  - re-resolves after 90 seconds of zero valid frames, capped at three failed attempts
- `StreamFetcher`
  - builds the `yt-dlp -> ffmpeg image2pipe` worker command plan
  - keeps sampling at 1 fps by default, capped at 2 fps for deuce handling
- `FrameSampler`
  - parses ffmpeg MJPEG image-pipe output into timestamped frame samples
- `RoiTemplateCatalog` / `BoardLocator`
  - loads `cv-assets/roi/*/roi.json` and locates the configured scoreboard ROI per frame
- `ScoreboardTextReader` / `PaddleOcrDigitEngine`
  - crops template digit fields, normalizes them to 64x32 OCR inputs, and accepts digit-only PaddleOCR JSON output
- `ClassicCvDigitEngine`
  - ordered after PaddleOCR as the Tier B fallback
  - segments one- and two-digit fields from binary crops and matches them against local digit masks without external services
- `ScoreStateMachine` / `StreamFrameConsensus`
  - rejects impossible score progression and emits only after three agreed frames
- `StreamCvVlmFallbackHook`
  - exposes an in-memory operator `Force VLM` queue with TTL and consume-on-next-frame semantics
  - creates system fallback decisions for classic-CV exhaustion and OCR disagreement, ready for Phase 04 VLM client wiring
- `tools/cv-template-builder`
  - provides a static canvas tool for drawing a scoreboard ROI and required digit fields
  - includes a 200-frame clip-manifest smoke test CLI for new `roi.json` templates
- `StreamFrameEventFactory`
  - builds `stream.frame` ingestion events from consensus-backed score payloads
- `StreamFrameIngestionEmitter`
  - Phase 04: publishes those `stream.frame` events through `IngestionBus`, so they
    route to Redis Streams when `features.redis-streams` is `shadow` or `on` and
    fall back to Spring application events otherwise
- `VlmClient` / `GeminiFlashVisionClient` / `ClaudeHaikuVisionClient` / `DisabledVlmClient`
  - Phase 04 Tier C scoreboard reader. `VlmClient` is the adapter interface;
    Gemini 2.0 Flash is the primary engine and Claude Haiku 4.5 is the
    contingency swap. The active engine is selected by
    `ttl.streamCv.vlm.engine` (`disabled` | `gemini-flash` | `claude-haiku`).
    Without an API key for the selected engine the configuration logs and
    falls back to `DisabledVlmClient`. Responses are validated against the
    Stream-CV §8.1 JSON schema by `VlmResponseParser`. Cost estimates use
    published per-million-token pricing constants.
- `CvAuditFrameBuffer` / `CvAuditEvidenceUploader` / `CvAuditEvidenceStore`
  - Phase 04 item 7 contradiction evidence pipeline. The Stream-CV worker
    pushes JPEG frames into `CvAuditFrameBuffer` (rolling per-match, default
    10-frame cap). On a `ContradictionGuard` event the
    `SettlementShadowAuditService.recordAttempt` path asks
    `CvAuditEvidenceStore.uploadForContradiction(matchId, bundleAsOf)` for the
    refs, and serializes them into `settlement_audit.evidence_refs`.
    `MinioCvAuditEvidenceUploader` writes each frame to
    `s3://ttl-cv-audit/<matchId>/<utcMinute>/<seq>.jpg`; the bucket's 30-day
    lifecycle (provisioned in `infra/minio/compose.*.yaml`) auto-purges old
    evidence. Toggle with `ttl.cv-audit.enabled`. Default is off; when off, a
    `NoopCvAuditEvidenceUploader` is wired and `evidence_refs` remains null.
- `CostGovernor` / `GovernedVlmCaller` / `VlmCallRecorder` / `StreamVlmMetrics`
  - Phase 04 item 6. `CostGovernor` enforces the §8.2 budget: per-worker
    150 calls/hour (rolling), global daily soft cap 2 500, global daily hard
    cap 4 000 (flips to exhausted until UTC rollover). `GovernedVlmCaller` is
    the entry point workers call: it asks the governor for a reservation,
    delegates to the chosen `VlmClient`, then routes the result back through
    the governor and `VlmCallRecorder` (which persists `stream_vlm_call` rows
    when a `StreamVlmCallRepository` is wired). `StreamVlmMetrics` exposes
    `stream_vlm_calls_total{model,reason}`, `stream_vlm_cost_usd_total{model}`,
    `stream_vlm_tokens_total{model,kind}`, `stream_vlm_latency`,
    `stream_vlm_governor_blocks_total{reason}`, `stream_vlm_daily_calls{model}`,
    and `stream_vlm_daily_cost_usd_estimate{model}`. Caps and the global
    enable flag are controlled by `ttl.streamCv.vlm.governor.*` properties.

The canonical rollout switch is `features.stream-cv` in `/Users/alexcannon/Downloads/TTLEliteSeries/features.yaml`.
The flag remains `off` by default, so these classes do not spawn live workers unless the rollout state is moved to `shadow` or `on`.
The Force VLM hook is intentionally not wired to an API yet; Ops can be attached later without changing the fallback decision contract.
