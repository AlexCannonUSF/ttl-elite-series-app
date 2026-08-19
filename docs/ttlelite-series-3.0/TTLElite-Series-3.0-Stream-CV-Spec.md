# TTLElite Series 3.0 — Stream-CV Spec
_Document v1.0 — companion to Master Plan §6.4 and Score Truth Engine §4.3_

## 0. Purpose and framing
Stream-CV (computer-vision scoreboard reader) is the mechanism that lets TTLElite Series **produce independent score evidence when Hard Rock closes the market**. This is the single most important lever for resolving the "closed bets → no live scoring" defect called out in §1 of the Master Plan. The pipeline watches the public video stream embedded in each TT-Series match page, samples frames, locates the scoreboard region, reads the point and game totals via OCR, validates sequences against the 11-point table tennis state machine, and emits `stream.frame` observations onto the ingestion bus. The Score Truth Engine consumes those observations as an independent T3 evidence source.

This spec is written so an engineer (or coding agent) can implement it phase by phase without guesswork. Every threshold, budget, and schema is pinned. No decisions are left to runtime improvisation.

## 1. Non-goals
- We do not try to recognise players, crowd, umpire calls, or whistle audio. Scoreboard-only, always.
- We do not rebroadcast, cache, or store frames beyond the rolling buffer needed for validation (§9). No derived or raw video leaves the process.
- We do not replace the oddsboard or match list. Stream-CV only emits **score** observations.
- We do not execute betting logic. The Stream-CV worker is a strictly read-side producer; settlement lives in the Score Truth Engine.

## 2. Architecture overview
```
+-------------------+     +-----------------+     +----------------+     +----------------+
| StreamRouter      | --> | StreamFetcher   | --> | FrameSampler   | --> | BoardLocator   |
| (match -> URL)    |     | (yt-dlp/hls)    |     | (ffmpeg 1fps)  |     | (OpenCV ROI)   |
+-------------------+     +-----------------+     +----------------+     +----------------+
                                                                                  |
                                                                                  v
+-------------------+     +-----------------+     +----------------+     +----------------+
| IngestionBus sink | <-- | ScoreValidator  | <-- | TextReader     | <-- | Preprocessor   |
| stream.frame      |     | (TT state mach) |     | (Paddle/Easy/  |     | (denoise +     |
|                   |     |                 |     |   Gemini VLM)  |     |  threshold)    |
+-------------------+     +-----------------+     +----------------+     +----------------+
          ^
          |
   +----------------+
   | CostGovernor   |
   | (VLM budget)   |
   +----------------+
```
Each running match has one **StreamWorker** fiber. All workers share a single `StreamPool` supervisor that enforces the global cost ceiling and restarts failed workers with exponential backoff.

## 3. Discovery and routing
### 3.1 Sources of truth for "what stream is this match on"
Ordered highest-confidence-first:
1. `TtSeriesScraper` detail page anchor `<a data-role="stream">` or `<iframe src>` containing `youtube.com/embed/…`, `twitch.tv/…`, or the TT-Series internal HLS origin. Store in `match.streamUrl`.
2. `hardrock_event.streamHint` (Hard Rock's tree sometimes includes a `livestreamUrl`).
3. `stream_routes.yaml` — hand-maintained overrides keyed by `eventCode` and `tableNumber` (e.g. `TTCUP` tournaments route through a single YouTube channel; we keep a mapping of `tableNumber → channelId + scheduled start offset`).
4. None of the above → emit `STREAM_UNAVAILABLE` to feed health and do not spawn a worker.

### 3.2 `stream_routes.yaml` schema
```yaml
# stream_routes.yaml — operator-editable fallback map
version: 1
routes:
  - match: {eventCode: "TTCUP", tableNumber: 1}
    platform: youtube
    channelId: "UCxxxxxxxxxxxxxxxxxxx"
    roiTemplateId: "ttcup.table1.v2"
    notes: "YT Live; scoreboard top-left; chroma key 0x00A8FF"
  - match: {eventCode: "WSTT", tableNumber: "*"}
    platform: ttseries_hls
    baseUrl: "https://stream.tt-series.example/hls/{tableNumber}.m3u8"
    roiTemplateId: "wstt.generic.v1"
```
The `roiTemplateId` is the key into §5.2 — it tells BoardLocator where on the frame the scoreboard lives and which colour channels to expect.

### 3.3 Router behaviour
- `StreamRouter` resolves a URL at match spawn time and re-resolves every 90 s if the worker has produced **zero** valid frames (stream may have been relocated or delayed).
- If three consecutive re-resolves fail, the worker downgrades to `DEGRADED_NO_STREAM` and the match goes on a `SCORE_BACKED_ONLY` settlement policy (see Score Truth Engine §6.1).

## 4. Fetch and sampling
### 4.1 StreamFetcher
- Use **yt-dlp** (`yt-dlp --live-from-start=false --no-part -q -f "best[height<=720]/best" --hls-use-mpegts --output -`) piped into **ffmpeg**. 720p is ample — scoreboards fit inside a ~128×64 ROI even at 480p, and going higher costs bandwidth and decode CPU without accuracy gain.
- For Twitch, we honour the OAuth-less public playlist endpoint. We do **not** log in, we do **not** use credentials, and we do not fetch subscriber-only streams.
- For TT-Series' internal HLS origin, we request with a `User-Agent: TTLElite-StreamCV/3.0 (+ops@ttl-elite.local)` and respect `robots.txt`. If robots disallow, we skip and flag `ROBOTS_DISALLOWED`.

### 4.2 FrameSampler
- ffmpeg sampling: `-vf fps=1,scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2:color=black -f image2pipe -vcodec mjpeg -q:v 5`.
- 1 frame per second is the default. Opens a higher sampling rate (2 fps) only during `deuce` state (§7.2) to catch fast consecutive points. Caps at 2 fps — beyond that the OCR step becomes the bottleneck, not the model.
- Every frame carries a monotonic `frameId = matchId + ":" + seq`, a `capturedAtUtc`, and the source byte count for audit logs.

### 4.3 Backpressure
- `FrameSampler → BoardLocator` is an unbounded fiber channel that the supervisor drops-oldest when depth exceeds 4 frames. A drop event emits `stream.sampler.drop` with per-worker labels so we can see if any worker is CPU-starved.

## 5. Board localisation
### 5.1 Two-tier locator
- **Tier A (fast path): template matching.** For each `roiTemplateId`, we keep a `template.png` (greyscale, edge-boosted) and an ROI rectangle `(x, y, w, h)` normalised to 1280×720. `cv2.matchTemplate(TM_CCOEFF_NORMED)` with a threshold of 0.78. Typical latency 3–6 ms per frame.
- **Tier B (slow path): classic CV heuristics.** If Tier A fails 3 consecutive frames, run:
  - Gaussian blur 3×3 → Canny (50, 150) → `findContours(RETR_EXTERNAL)`
  - Filter rectangles with aspect ratio 2.0–4.5 and height 20–120 px.
  - Rank by "text-likeness" = ratio of horizontal-edge pixels inside. Pick the top 2 candidates, OCR both, keep whichever produces a valid score tuple.
- If Tier B fails 5 consecutive frames, escalate to Tier C (§8 VLM fallback) for one shot; if that also fails, mark the worker `STREAM_ROI_LOST`.

### 5.2 Template catalogue
Templates live under `cv-assets/roi/` as one folder per `roiTemplateId` with:
- `template.png` — the reference scoreboard crop.
- `roi.json`:
```json
{
  "templateId": "wstt.generic.v1",
  "frameWidth": 1280,
  "frameHeight": 720,
  "roi": {"x": 48, "y": 32, "w": 260, "h": 96},
  "colorProfile": "bright-on-dark",
  "digitFields": [
    {"name": "topGames",   "rel": [0,   0, 40, 96]},
    {"name": "topPoints",  "rel": [44,  0, 80, 96]},
    {"name": "botGames",   "rel": [128, 0, 40, 96]},
    {"name": "botPoints",  "rel": [172, 0, 80, 96]}
  ],
  "serverIndicator": {"name":"server","rel":[224,24,24,24],"kind":"colorDot"}
}
```
Operators can add a new template in ≤10 minutes using `tools/cv-template-builder` (ships in Phase 03). The tool lets you drag a rectangle on a still frame, auto-generates the JSON, and runs a 200-frame smoke test against a captured clip.

### 5.3 Adaptive ROI
- Once a template matches three times in a row, we lock the exact pixel offset and skip re-matching for 60 s. This saves ~80 % of the locator CPU in steady state.
- Lock is cleared on any OCR-invalid frame to recover from camera/overlay changes.

## 6. Preprocessing and OCR
### 6.1 Preprocessor pipeline
For each of the four `digitFields`:
1. Crop the rel rect.
2. If `colorProfile == "bright-on-dark"` → invert. If `"dark-on-bright"` → leave as-is.
3. Convert to grey, CLAHE (`clipLimit=2.0`, tile 8×8).
4. Adaptive threshold `ADAPTIVE_THRESH_GAUSSIAN_C`, block 15, C 5.
5. Morph open 2×2 to kill salt noise.
6. Pad to 64×32 with white border.

### 6.2 Primary OCR — PaddleOCR
- `PaddleOCR(use_angle_cls=False, lang='en', rec_model='en_PP-OCRv4_rec', det=False)` — detection is disabled because we already have the field boxes.
- Allowed charset: digits only. Reject any recognition with non-digit characters, empty string, or > 2 chars.
- Keep the recogniser on CPU in the default deployment; a T4 GPU pool (§11) is used only in the `VLM+GPU` variant.

### 6.3 Secondary OCR — EasyOCR
- Run **only if** PaddleOCR confidence < 0.90 on any field.
- `easyocr.Reader(['en'], gpu=False)` with `allowlist='0123456789'`.
- If the two engines agree, accept the value and boost confidence to 0.95. If they disagree, drop to Tier C (VLM).

### 6.4 Field-level fusion
Final `confidence = min(fieldConfidences)` — the board is only as trustworthy as its worst field.

## 7. Score validation (TT state machine)
### 7.1 Rules encoded
- Points in a table-tennis game go 0–11, except in deuce where either side can continue past 10 until one leads by 2. We cap displayed points at 20 for sanity.
- Best-of configuration comes from `match.bestOf` (3, 5, 7). Games count caps at `ceil(bestOf/2)`.
- Server alternates every 2 points except in deuce (≥10-10) where it alternates every 1 point.
- Games can only increment by 1 when the previous frame showed the correct game-end condition (points 11-≤9, or deuce with |diff|=2, lead ≥11).

### 7.2 State machine
```
State = {
  topGames, botGames,
  topPoints, botPoints,
  server: TOP | BOT | UNKNOWN,
  phase: NORMAL | DEUCE | INTERGAME,
  lastValidFrameSeq,
  consecutiveConsensus  // see 7.3
}
```
Transitions are `accept`, `reject`, or `revise`:
- `accept` if the new frame is either equal to state (no change) or a valid +1 to exactly one of `{topPoints, botPoints}` within the same game, or a valid game flip.
- `reject` if the new frame violates the rules (e.g. points jumped by 2, or the game counter went backwards).
- `revise` if the new frame is inconsistent with the current state but matches a **valid alternate lineage** that disagrees with the last accepted frame by at most 1 point — this covers the 1-frame-miss scenario where we skipped a point due to a drop.

### 7.3 Three-frame consensus before emission
- Maintain a rolling window of the last 3 accepted-or-revised frames. Emit a `stream.frame` observation **only** when the latest 3 frames agree on the tuple `(topGames, botGames, topPoints, botPoints)` with `phase ∈ {NORMAL, DEUCE}` and `confidence ≥ 0.85`.
- Exception: an `INTERGAME` flip (`x-y` → `x+1-y`) emits immediately after the first frame that shows it **and** the preceding game had a valid end state.

### 7.4 Staleness
- If no new accepted frame arrives for 20 s, mark the worker `STALE_STREAM` and downgrade all pending emissions to confidence 0.6 until a fresh valid frame lands.

## 8. Vision-language model fallback
### 8.1 Engine
- **Gemini 2.0 Flash** via Vertex AI or the Gemini API. We pick Flash specifically because latency at 256×256 inputs is ~400 ms p95 and per-image cost sits under $0.0002 at the volumes below. Anthropic Claude Haiku 4.5 vision is the contingency swap if Flash becomes unavailable — the adapter layer keeps them interchangeable.
- Prompt contract (trim whitespace, force JSON):
```
System: You are a scoreboard reader. Output ONLY JSON, no prose.
User: Read the table-tennis scoreboard in this image. Return:
  {"topGames": int, "botGames": int, "topPoints": int, "botPoints": int,
   "server": "TOP"|"BOT"|"UNKNOWN", "confidence": 0..1}
If unreadable, return {"error": "UNREADABLE"}.
```
- Response is validated by a JSON schema before feeding back into the state machine.

### 8.2 Cost governor
- Global budget target: **$35 / month / deployment** (covers 8 concurrent streams running 12 h/day each).
- Soft cap: 2,500 VLM calls/day. Hard cap: 4,000/day — beyond that the governor flips to `VLM_EXHAUSTED` and the affected workers fall back to "primary+secondary OCR only" for the rest of the day.
- Per-worker cap: 150 VLM calls/hour. Excess calls queue and are dropped after 30 s.
- Every VLM call is logged with `{frameId, tokensIn, tokensOut, latencyMs, costEstimateUsd}` to `stream_vlm_call` and aggregated into Prometheus gauges `stream_vlm_daily_calls` and `stream_vlm_daily_cost_usd_estimate`.

### 8.3 When we invoke the VLM
1. Tier B (classic CV) failed 5 consecutive frames.
2. Primary+secondary OCR disagree **and** state machine `revise` is inconclusive.
3. Operator `Force VLM` from Ops Console on a specific match (hourly cap still applies).
The VLM is never used "by default" — it is strictly a tiebreaker.

## 9. Frame retention
- Hot buffer: 30 s rolling window per worker, in-memory only, for state-machine replay on restart. Never persisted.
- Cold buffer: on any `ContradictionGuard` event (Score Truth Engine §7), we dump the last 10 s of frames (10 JPEGs) to MinIO under `s3://ttl-elite/cv-audit/<matchId>/<utcMinute>/*.jpg` and reference them in the `settlement_audit.evidence_refs` JSONB column. Retention 30 days, then auto-purged by a lifecycle rule.
- No other frames are written to durable storage.

## 10. Worker lifecycle
### 10.1 State enum
```
WorkerState = {
  BOOTING, ACQUIRING_STREAM, WARMING_LOCATOR,
  RUNNING, DEGRADED_LOW_CONF, DEGRADED_NO_STREAM,
  STALE_STREAM, STREAM_ROI_LOST, VLM_EXHAUSTED,
  STOPPING, STOPPED_CLEAN, STOPPED_CRASH
}
```

### 10.2 Transitions and emissions
- `BOOTING → ACQUIRING_STREAM` on worker spawn.
- `ACQUIRING_STREAM → WARMING_LOCATOR` after first frame decoded.
- `WARMING_LOCATOR → RUNNING` after 3 consecutive template matches.
- `RUNNING → DEGRADED_LOW_CONF` if rolling 60 s confidence p50 < 0.75.
- `RUNNING → STREAM_ROI_LOST` per §5.1.
- `RUNNING → STALE_STREAM` per §7.4.
- Auto-restart on `STOPPED_CRASH` with exponential backoff capped at 5 minutes and max 6 retries per hour. After that, the match is marked `STREAM_UNSTABLE` and Ops Console surfaces it.

### 10.3 Match-lifecycle integration
- Worker is spawned by `MatchOrchestrator` when match state becomes `LIVE_LIKELY` (≤10 minutes before scheduled start) **and** a stream route exists.
- Worker is stopped 3 minutes after `MATCH_ENDED` to catch trailing scoreboard frames that confirm the final game.
- Worker is never spawned for finished or cancelled matches.

## 11. Resource and cost budget
| Deployment variant | CPU (cores) | RAM | Monthly cloud cost ceiling | Notes |
|---|---|---|---|---|
| **Dev / single box** | 2 | 2 GB | $0 (runs on the dev laptop) | Max 2 concurrent workers. |
| **Prod / small** | 4 | 4 GB | $12 (shared VPS) | Target 6 workers at 1 fps. |
| **Prod / medium** | 8 | 8 GB | $38 | 12 workers + VLM budget $35. |
| **Prod / large (GPU)** | 4 + T4 | 16 GB | $180 | 24 workers, PaddleOCR on GPU, VLM budget $60. Only when WSTT + TTCUP overlap. |

The small tier is the default for v3.0.0 — no GPU, no more than 8 concurrent matches, budget-capped VLM usage. The `StreamPool` enforces the concurrency ceiling.

## 12. Emission contract
### 12.1 `stream.frame` event (on the ingestion bus)
```json
{
  "schemaVersion": 1,
  "eventId": "uuidv7",
  "capturedAtUtc": "2026-04-16T14:02:31Z",
  "ingestedAtUtc": "2026-04-16T14:02:31.850Z",
  "matchId": "ttl:match:2026-04-16:TTCUP:t1:r3m12",
  "sourceId": "STREAM-CV",
  "trustTier": "T3",
  "payload": {
    "topGames": 1, "botGames": 2,
    "topPoints": 9, "botPoints": 7,
    "server": "TOP",
    "phase": "NORMAL",
    "confidence": 0.93,
    "templateId": "wstt.generic.v1",
    "reader": "paddle+easy",
    "frameId": "matchid:1842"
  },
  "raw": {
    "payloadRef": "s3://ttl-elite/cv-audit/.../1842.jpg",
    "payloadSha256": "ab12...cd34"
  },
  "signals": {
    "source": "STREAM-CV",
    "latencyMs": 820,
    "correlationId": "uuidv7"
  }
}
```
### 12.2 Downstream consumers
- Score Truth Engine subscribes and records a `StreamObservation` in `settlement_evidence.observations`.
- `FeedHealthService` updates `stream_cv` ring buffer with per-worker p50/p95 confidence and staleness.
- `LiveBoard` UI component renders a small "Stream" badge on every row where `stream.frame` was seen in the last 15 s — this is the end-user's signal that "yes, even if the market is closed, we still see the score".

## 13. Database schema
```sql
-- 13.1 Per-worker config
CREATE TABLE stream_worker_config (
  match_id         VARCHAR(120) PRIMARY KEY,
  stream_url       TEXT NOT NULL,
  platform         VARCHAR(24) NOT NULL,        -- youtube | twitch | ttseries_hls
  roi_template_id  VARCHAR(64) NOT NULL,
  started_at_utc   TIMESTAMP WITH TIME ZONE,
  stopped_at_utc   TIMESTAMP WITH TIME ZONE,
  last_state       VARCHAR(32),
  last_error       TEXT
);

-- 13.2 Per-minute health rollup (for the Ops Console graphs)
CREATE TABLE stream_worker_health_1m (
  match_id          VARCHAR(120),
  minute_bucket_utc TIMESTAMP WITH TIME ZONE,
  frames_ingested   INT,
  frames_emitted    INT,
  p50_confidence    NUMERIC(4,3),
  p95_latency_ms    INT,
  vlm_calls         INT,
  state_seen        JSONB,             -- counts per WorkerState
  PRIMARY KEY (match_id, minute_bucket_utc)
);

-- 13.3 VLM call log (append-only, 30-day retention)
CREATE TABLE stream_vlm_call (
  call_id         UUID PRIMARY KEY,
  match_id        VARCHAR(120) NOT NULL,
  frame_id        VARCHAR(160) NOT NULL,
  model           VARCHAR(48) NOT NULL,
  tokens_in       INT,
  tokens_out      INT,
  latency_ms      INT,
  cost_usd_est    NUMERIC(10,6),
  response_valid  BOOLEAN,
  called_at_utc   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 13.4 Route overrides loaded from YAML (materialised for queryability)
CREATE TABLE stream_route (
  route_id         BIGSERIAL PRIMARY KEY,
  event_code       VARCHAR(32) NOT NULL,
  table_number     VARCHAR(16) NOT NULL,     -- '*' for wildcard
  platform         VARCHAR(24) NOT NULL,
  channel_or_base  TEXT NOT NULL,
  roi_template_id  VARCHAR(64) NOT NULL,
  updated_at_utc   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

## 14. Observability
### 14.1 Prometheus metrics (names final)
- `stream_worker_state{match_id, state}` — gauge (0/1 per state).
- `stream_frames_ingested_total{match_id}` — counter.
- `stream_frames_emitted_total{match_id}` — counter.
- `stream_ocr_confidence{match_id, engine, quantile}` — summary.
- `stream_ocr_disagreement_total{match_id}` — counter.
- `stream_vlm_calls_total{model, reason}` — counter.
- `stream_vlm_cost_usd_total{model}` — counter (derived, Prometheus rule).
- `stream_state_machine_rejects_total{match_id, kind}` — counter.
- `stream_end_to_end_latency_ms{match_id}` — histogram (frame capture → bus emit).

### 14.2 Alerts
- `StreamWorkerCrashLoop` — any worker with ≥6 crashes in 1 h.
- `StreamVLMCostSpike` — `sum(rate(stream_vlm_cost_usd_total[1h])) > $2.50/h` for 15 min.
- `StreamCVSilent` — no `stream_frames_emitted_total` increment for >3 min on a match in `RUNNING`.
- `StreamLatencyP95High` — `stream_end_to_end_latency_ms` p95 > 15 s for 10 min.

### 14.3 Ops Console surface
- **Streams panel** (tab under `/ops/feeds`): a row per active worker showing state, frames/min, OCR confidence p50, last emitted tuple, VLM calls in last hour, template ID, "Force VLM" button, "Stop worker" button.
- **Template health**: aggregates emission success by `roi_template_id` so we can see which template is degrading as a broadcaster changes their overlay.
- **VLM budget**: a live bar chart of monthly spend vs. cap.

## 15. Failure modes and containment
| Failure | Signal | Immediate behaviour | Long-term containment |
|---|---|---|---|
| Broadcaster changes scoreboard overlay | Tier A template match < 0.78 for 3 min | Tier B → Tier C → if still failing, mark `STREAM_ROI_LOST`; Score Truth falls back to other sources. | Operator adds new template via `cv-template-builder` (§5.2). |
| Stream DRM / login wall | yt-dlp exit code 403/2 | `DEGRADED_NO_STREAM`; attempt re-resolve each 90 s (3x); then park. | Remove that platform from `stream_routes.yaml`. |
| Gemini API outage | JSON schema fail or HTTP 5xx | CostGovernor trips `VLM_EXHAUSTED` for 15 min, auto-retry with exponential backoff. | Swap to Claude Haiku 4.5 vision via adapter. |
| Runaway cost | `stream_vlm_cost_usd_total` rate spike | Hard cap hits, governor stops VLM calls. Workers continue OCR-only. | Review the worker causing the spike; usually a stuck low-conf frame loop — patch by raising Tier B rectangle aspect ratio bounds. |
| CPU starvation | `stream.sampler.drop` counter > 30/min | Supervisor scales down concurrency by 1 each minute until drops stop. | Move to Prod/medium tier or enable GPU. |
| Copyright/TOS concern on a platform | Operator flag | Immediate `STOPPING` on all workers for that platform; mark routes disabled. | Legal review before re-enabling. |

## 16. Security and compliance
- Stream-CV workers run as a non-root system user with `CAP_SYS_ADMIN` disabled.
- Only outbound HTTPS to the configured streaming domains and the VLM endpoint is allowed through the firewall egress policy. No arbitrary internet access.
- Raw frame storage in MinIO is server-side encrypted (AES-256). Bucket policy disables public access; lifecycle rule deletes objects after 30 days.
- Frames are considered public broadcast data and contain no PII beyond what the broadcaster chose to display. We do not record commentary or audio.
- We do not circumvent access controls. If a stream requires authentication, we do not fetch it.
- Licence check before enabling any new platform: operator fills in `platforms/<name>/COMPLIANCE.md` with robots.txt snapshot, TOS relevant clauses, and contact email before the route ships.

## 17. Testing strategy
### 17.1 Unit
- `ScoreStateMachine` — property-based tests (`jqwik`) over random sequences of valid and invalid transitions. ≥2,000 generated cases must pass.
- `BoardLocator` Tier A — fixture: 20 frames per template; ≥18/20 must match.
- `Preprocessor` — snapshot tests on golden digit crops.

### 17.2 Integration
- **Replay harness**: the worker takes a pre-recorded 20-minute HLS segment as input (no network) and must emit a known reference sequence of `stream.frame` events with ≥95 % tuple accuracy vs. a hand-labelled ground truth file.
- We ship 6 replay fixtures in the repo under `cv-assets/fixtures/` covering TTCUP, WSTT, an AisScore overlay, a TT-Series overlay, a scoreboard-hidden intergame clip, and a "noisy crowd shot" negative case (must produce zero frames).

### 17.3 End-to-end
- Staging environment runs one Stream-CV worker against a public test loop (we host a 1-hour YouTube unlisted video with a known scoreboard sequence). The nightly CI job compares the emitted `stream.frame` sequence against the fixture manifest and fails on any tuple mismatch > 1 per 10 minutes.
- On-box smoke: `scripts/stream-cv-smoke.sh <matchId> --fixture wstt.v1` spins up one worker, runs 120 s, and asserts ≥100 `stream.frame` emits with p50 confidence ≥ 0.88.

### 17.4 Load
- Synthetic 24-stream load generator runs weekly in the `stream-cv-load` CI job to ensure the `StreamPool` and CostGovernor stay inside their budget envelopes.

## 18. Phased rollout crosswalk
| Master Plan phase | Stream-CV deliverable |
|---|---|
| Phase 00 | Skeleton `StreamRouter`, `StreamFetcher`, `FrameSampler` modules in the repo behind `features.stream-cv=off`. No emit yet. |
| Phase 02 | Tier A BoardLocator + PaddleOCR + state machine + emit to ingestion bus. Enable for 2 fixture matches in staging. |
| Phase 03 | Tier B + Tier C fallback, CostGovernor, `stream_routes.yaml`, Ops Console streams panel. |
| Phase 04 | VLM adapter (Gemini Flash + Haiku swap), MinIO audit buffer, full Prometheus + alerts, replay harness in CI. |
| Phase 05 | Score Truth Engine consumes Stream-CV as a T3 source for live matches. |
| Phase 06 | Stream-CV becomes a **required** input for any match with `market_closed_before_end == true`; SettlementEngine downgrades confidence when it is absent. |
| Phase 07 | Prod/medium tier default; enable Stream-CV for all TTCUP/WSTT matches in production. |
| Phase 08 | Tune budgets, add platform(s), consider GPU tier if concurrency peaks justify. |

## 19. Risks and mitigations specific to CV
- **Template rot**: broadcasters routinely redesign overlays. Mitigation: one-week operator SLO to ship a new template once `STREAM_ROI_LOST` fires; Tier B always present as a backstop; Tier C VLM as last resort.
- **Digit misreads in deuce**: "12" vs "17" vs "27" is a known OCR confusion. Mitigation: state machine rejects any jump > 1 inside a game; field confidence reweighting when `max(points) ≥ 10`.
- **Server dot occlusion**: many overlays hide the server dot during a rally. Mitigation: we do not require the server field to emit — we record `UNKNOWN` and fall back to "alternate every 2 points" for any consistency check downstream.
- **Cost drift**: a mis-tuned worker can burn VLM budget on a stuck frame. Mitigation: governor's per-worker hour cap + `stream.vlm.costSpike` alert + kill switch.
- **Legal / TOS**: we restrict ourselves to public broadcasts, never credentials, never rebroadcast, frame retention capped and encrypted, compliance file per platform.

## 20. Definition of done (per phase)
- **Phase 02**: one match in staging emits `stream.frame` for ≥95 % of its live duration with ≥0.88 p50 confidence.
- **Phase 04**: full prod/small deployment operates 7 days with zero budget breach, zero DLQ from Stream-CV, and ≥98 % tuple accuracy on replay fixtures.
- **Phase 06**: at least 10 matches settle where Stream-CV was the decisive post-market-close source; zero Bug A-style contradictions reach production.

---
*End of Stream-CV Spec v1.0.*
