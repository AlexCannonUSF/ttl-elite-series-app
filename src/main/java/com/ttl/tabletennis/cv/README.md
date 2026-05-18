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

The canonical rollout switch is `features.stream-cv` in `/Users/alexcannon/Downloads/TTLEliteSeries/features.yaml`.
The flag remains `off` by default, so these classes do not spawn live workers unless the rollout state is moved to `shadow` or `on`.
The Force VLM hook is intentionally not wired to an API yet; Ops can be attached later without changing the fallback decision contract.
