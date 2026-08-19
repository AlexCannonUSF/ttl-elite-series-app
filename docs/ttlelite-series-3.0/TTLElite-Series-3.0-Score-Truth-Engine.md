# TTLElite Series 3.0 — Score Truth Engine Spec

**Status:** Draft v1.0 · **Parent:** `TTLElite-Series-3.0-Master-Plan.md`
**Covers:** Workstream B — Score Truth Engine
**Companion:** `TTLElite-Series-3.0-Stream-CV-Spec.md`

---

## 1. Problem Restated

When Hard Rock closes the market on a live match, our existing pipeline loses its primary score observation. Bets enter `MARKET_CLOSED_SCORE_TRACKED` and frequently exit via `VOIDED_MISSING_BOARD_TIMEOUT` (6 of 9 closed bets in Run-59) or `SETTLED_FROM_LAST_SCORE_HEURISTIC` (3 of 9). Score-backed settlement was used 0 times. Worse, same-player same-day pairings cause wrong-match attachment when the official-result resolver runs (Run-56 Bug A).

Today's `PaperTradingService#settleOpenBets` (file `src/main/java/com/ttl/tabletennis/service/PaperTradingService.java:1009`) is a nested cascade of 8+ pathways with 21+ string-keyed reason codes (`SETTLED_FROM_*`, `VOIDED_*`). Gating predicates (`isSettlementWindowOpen`, `shouldBypassSettlementWindowForCurrentRow`, `shouldBypassSettlementWindowForLastScore`, `isTrackedAfterCloseDatabaseContext`, `hasTrackedAfterCloseDatabaseEvidence`) are smeared across the file.

3.0 replaces this with a first-class **SettlementEvidence bundle** and a pure **SettlementEngine** function `decide(bundle, policy) → Decision`.

---

## 2. Core Data Model

### 2.1 `SettlementEvidence` bundle

```java
public record SettlementEvidence(
    long betId,
    TrackedEventId trackedEventId,              // stable identity
    IdentityLock identityLock,                  // frozen at placement

    List<LiveObservation> liveObservations,     // HR-MKT, HR-TGT
    List<MirrorObservation> mirrorObservations, // Sofascore, AiScore, BetsAPI
    List<StreamObservation> streamObservations, // Stream-CV (OCR)
    List<OfficialCandidate> officialCandidates, // TT-Series post/player/H2H
    List<DatabaseCandidate> databaseCandidates, // internal Match rows

    CoverageState coverageState,                // FULL | PARTIAL | DARK
    List<Contradiction> contradictions,
    double ambiguityScore,                      // 0..1
    double confidence,                          // Bayesian aggregate 0..1
    Instant bundleAsOf
) { }
```

Each observation record carries:

```java
public sealed interface Observation permits LiveObservation, MirrorObservation, StreamObservation {
    SourceId source();
    TrustTier tier();              // T1..T4
    Instant observedAt();
    double confidence();           // source-local 0..1
    MatchPhase phase();            // PREMATCH, LIVE_EARLY, LIVE_MID, LIVE_LATE, FINISHED, UNKNOWN
    ScoreState score();            // games (p1, p2), current-game points (p1, p2), server
    String rawPayloadRef;          // pointer into RawPayloadStore
    boolean completionSignal();    // explicit "match finished" flag from source
}
```

```java
public record IdentityLock(
    long player1Id,
    long player2Id,
    Instant placementTime,
    Duration ambiguityWindow,      // typically 90 min
    String bookerEventId,
    String bookerMarketId
) { }
```

### 2.2 `SourceId` and `TrustTier`

```java
public enum TrustTier { T1_SPORTSBOOK, T2_MIRROR, T3_STREAM_CV, T4_CONFIRMATION }

public record SourceId(String id, TrustTier tier) {
    public static final SourceId HR_MKT   = new SourceId("HR_MKT",   T1_SPORTSBOOK);
    public static final SourceId HR_TGT   = new SourceId("HR_TGT",   T1_SPORTSBOOK);
    public static final SourceId SOFASCORE= new SourceId("SOFASCORE",T2_MIRROR);
    public static final SourceId AISCORE  = new SourceId("AISCORE",  T2_MIRROR);
    public static final SourceId BETSAPI  = new SourceId("BETSAPI",  T2_MIRROR);
    public static final SourceId STREAM_CV= new SourceId("STREAM_CV",T3_STREAM_CV);
    public static final SourceId TTS_POST = new SourceId("TTS_POST", T4_CONFIRMATION);
    public static final SourceId TTS_PLAYER = new SourceId("TTS_PLAYER", T4_CONFIRMATION);
    public static final SourceId TTS_H2H  = new SourceId("TTS_H2H",  T4_CONFIRMATION);
    public static final SourceId INTERNAL_DB = new SourceId("INTERNAL_DB", T4_CONFIRMATION);
}
```

### 2.3 `Contradiction`

```java
public record Contradiction(
    Observation a,
    Observation b,
    ContradictionKind kind,  // SCORE_DIVERGENCE | WINNER_DISAGREE | PHASE_MISMATCH
    double severity          // 0..1
) { }
```

### 2.4 `Decision`

```java
public sealed interface Decision permits Settle, HoldOpen, Escalate, Void, ManualReview {
    SettlementEvidence evidence();
    SettlementReason reason();
}

public record Settle(
    SettlementEvidence evidence,
    long winnerPlayerId,   // or 0 for push
    SettlementReason reason,
    double confidence
) implements Decision { }

public record HoldOpen(SettlementEvidence e, String note) implements Decision { }
public record Escalate(SettlementEvidence e, List<SourceId> nextSources) implements Decision { }
public record Void(SettlementEvidence e, SettlementReason reason) implements Decision { }
public record ManualReview(SettlementEvidence e, List<Contradiction> contradictions) implements Decision { }
```

### 2.5 `SettlementReason` (sealed, replaces string enum)

Catalog is explicit. Each reason carries `tier`, `requiresConfidence`, `learnable`:

```
SCORE_BACKED_DECISIVE         T1/T2/T3  0.85  learnable
SCORE_BACKED_FINISHED         T1/T2/T3  0.90  learnable
TARGETED_COMPLETION_SIGNAL    T1        0.95  learnable
OFFICIAL_RESULT_CONFIRMED     T4        0.90  learnable   (only when un-ambiguous & un-contradicted)
DATABASE_RESULT_CONFIRMED     T4        0.85  learnable
STREAM_CV_CONSENSUS           T3        0.80  learnable   (requires 3-frame consensus)
LAST_SCORE_HEURISTIC          T1/T2     0.50  NOT learnable (tainted flag)
STALE_ESCALATION_RECOVERED    *         *     learnable
VOIDED_NO_EVIDENCE            n/a       n/a   NOT learnable
VOIDED_AMBIGUOUS_UNRESOLVED   n/a       n/a   NOT learnable
MANUAL_REVIEW_AWAITING        n/a       n/a   NOT learnable (not-yet-settled marker)
```

The `learnable` flag is consumed by the Learning Gate (§6) and excludes weak evidence from model training.

---

## 3. Policy (configuration)

```yaml
ttl.score-truth:
  ambiguity:
    same-pair-same-day-window-hours: 8
    max-allowed-without-tiebreaker: 0.7   # above this, block auto-settle
  settlement:
    min-confidence-to-auto-settle: 0.85
    contradiction-block-severity: 0.5
    require-sources: 2                    # ≥ 2 independent for high-confidence
    match-completion-settle-delay-seconds: 60   # small debounce
  stale-live-recovery:
    enter-after-minutes-dark: 10
    escalation-order: [HR_TGT, SOFASCORE, AISCORE, BETSAPI, STREAM_CV]
    official-window-minutes: 180
  heuristic:
    allowed: true
    after-dark-minutes: 240
    max-per-session-pct: 5.0
  learning-gate:
    min-confidence: 0.9
    require-no-contradictions: true
  integration:
    stream-cv-enabled: true
    sofascore-enabled: true
    aiscore-enabled: true
    betsapi-enabled: false          # flip when licensed
```

---

## 4. SettlementEngine — Pure Decision Function

```java
public interface SettlementEngine {
    Decision decide(SettlementEvidence evidence, SettlementPolicy policy);
}
```

The default implementation is a **ranked multi-source voting** function, not a branch cascade. Pseudocode:

```
1. Refresh ambiguity score from IdentityLock + officialCandidates.
2. Build grouped tally of outcome claims:
      claims: Map<Outcome, List<Observation>>  where Outcome ∈ {p1_wins, p2_wins, push, not_finished}.
3. For each claim, compute:
      weightedConfidence = Σ obs.weight(tier, recency, observer-calibration) * obs.confidence
4. Detect contradictions: any two claims with weightedConfidence > 0.4 about the same match that disagree.
5. If contradictions.severity_max > policy.contradictionBlockSeverity → ManualReview.
6. If ambiguityScore > policy.ambiguity.maxAllowedWithoutTiebreaker:
     - require a SOURCE_PAIR_MATCH tiebreaker (bookerEventId match OR point-in-time score trajectory match).
     - If tiebreaker missing → ManualReview.
7. Let bestClaim = argmax(claims, weightedConfidence).
8. If bestClaim.outcome is not_finished:
     - If coverageState == DARK && staleMinutes > policy.staleLiveRecovery.enterAfterMinutesDark:
         → Escalate(nextSources = policy.staleLiveRecovery.escalationOrder - already-tried).
     - Else → HoldOpen.
9. If bestClaim.outcome ∈ {p1_wins, p2_wins, push}:
     - If bestClaim.confidence >= policy.settlement.minConfidenceToAutoSettle
       AND distinct sources >= policy.settlement.requireSources:
         → Settle(winner, reason = reasonFromEvidence(bestClaim)).
     - Else → HoldOpen(note="insufficient independent evidence").
10. If nothing has fired, elapsed > officialWindowMinutes:
     - If heuristic allowed AND under per-session cap:
         → Settle(LAST_SCORE_HEURISTIC, tainted=true).
     - Else → Void(VOIDED_NO_EVIDENCE).
```

Observer weights:

```
tierWeight:    T1 = 0.35, T2 = 0.30, T3 = 0.25, T4 = 0.40
recencyDecay:  exp(-ageSeconds / 600)              # 10-min half-life
calibrationMultiplier: per-source rolling Brier-based trust, clamped [0.5, 1.2]
completionBonus:  completionSignal ? 1.25 : 1.0
```

Pure function; deterministic; trivially unit-testable; replayable.

---

## 5. AmbiguityScorer + ContradictionGuard

### 5.1 `AmbiguityScorer`

Inputs: identityLock, officialCandidates, mirror candidates.

```
score = 0
for each candidate in (official + mirror):
    if candidate.playerPair == identityLock.pair AND candidate.date ∈ identityLock.ambiguityWindow:
        score += 1.0 per matching candidate
        score -= 0.4 if candidate.bookerEventId == identityLock.bookerEventId  // exact booker match reduces ambiguity
        score -= 0.3 if candidate has matching score trajectory across >=3 shared observations
normalize to [0..1]
```

Thresholds:
- `score < 0.3` → unambiguous, proceed.
- `0.3 ≤ score < 0.7` → partial, require T1 or T3 evidence before settling.
- `score ≥ 0.7` → ambiguous, **ManualReview** unless tiebreaker present.

### 5.2 `ContradictionGuard`

Compare top-ranked outcome claim against:
1. **Live timeline direction** — if last live/mirror/stream observation shows p1 losing but settlement claims p1 won → contradiction.
2. **Progressive score** — game score differentials that cannot be reconciled with a legal match progression.
3. **Phase inversion** — an OFFICIAL_RESULT claiming FINISHED before the last LIVE_LATE observation.

Any severity > 0.5 blocks auto-settlement. Contradiction events are persisted to a `Contradiction` table and surfaced in the Score Truth Monitor UI.

---

## 6. Learning Gate

```java
public final class LearningGate {
    public boolean isLearnable(Settle settle) {
        if (!settle.reason().learnable()) return false;
        if (settle.confidence() < policy.learningGate.minConfidence) return false;
        if (!settle.evidence().contradictions().isEmpty()) return false;
        return true;
    }
}
```

Wired into `PaperTradeLearningSample` creation (currently `PaperTradingService#persistLearningSample` around line 1373). Heuristic and database-only settlements enter dashboards (for operator review) but never the model training set.

---

## 7. Stale-Live Recovery Workflow

A dedicated service — `StaleLiveRecoveryService` — runs on a short cadence (every 30 s) over bets in `MARKET_CLOSED_SCORE_TRACKED` / `OPEN_SCORE_VISIBLE` whose last observation is older than `enterAfterMinutesDark`:

1. Emit `stale.live.detected` on the ingestion bus.
2. For each source in `escalation-order` not already tried in the last 5 min, issue a targeted fetch.
3. Collect observations, re-run `SettlementEngine.decide`.
4. If still no evidence after all sources, schedule an official-result recovery job at `now + 15 min`.
5. Continue until `officialWindowMinutes` elapsed. Only then permit heuristic/void decisions.

This directly addresses the 2.0 doc's gap: "there is no dedicated stale-live-recovery workflow with second-source escalation".

---

## 8. Service Decomposition

Carve the 5,653-line `PaperTradingService` into:

```
com.ttl.tabletennis.paper/
├── PlacementService.java         # candidate eval, exposure capping (was 'resolveCandidate' etc.)
├── SessionService.java           # lifecycle, counters, reset
├── PaperTradingFacade.java       # thin orchestrator
├── IntegrityService.java         # UI counters rollup
└── learning/
    ├── DecisionSamplingService.java
    └── AdaptiveTuningService.java

com.ttl.tabletennis.settlement/
├── SettlementEngine.java         # pure decide()
├── SettlementEvidenceBuilder.java
├── SettlementPolicy.java
├── AmbiguityScorer.java
├── ContradictionGuard.java
├── LearningGate.java
├── reason/SettlementReason.java
├── observation/{Live,Mirror,Stream,Official,Database}Observation.java
├── recovery/StaleLiveRecoveryService.java
└── audit/SettlementAuditor.java

com.ttl.tabletennis.identity/
├── IdentityLockService.java
└── IdentityDriftDetector.java
```

Target: no file > 1,500 lines; `SettlementEngine.decide` ≤ 200 lines.

### 8.1 Migration strategy (strangler fig)

1. Introduce new packages with **shadow mode** — both old and new settle the same bets; outcomes diffed into a `SettlementDiffLog` table.
2. Run for 72 h. When diff rate < 0.5% (all diffs explainable by policy change), flip the default.
3. Delete the old `settleOpenBets` branch cascade.
4. Port tests method-by-method to the new services; retain `PaperTradingServiceTests` as an end-to-end smoke harness.

---

## 9. Persistence

### 9.1 New tables

```sql
CREATE TABLE settlement_evidence (
    id BIGSERIAL PRIMARY KEY,
    bet_id BIGINT NOT NULL REFERENCES paper_trade_bet(id),
    bundle_as_of TIMESTAMPTZ NOT NULL,
    coverage_state VARCHAR(16) NOT NULL,
    ambiguity_score DOUBLE PRECISION NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    payload_json JSONB NOT NULL,
    CONSTRAINT uq_bet_asof UNIQUE (bet_id, bundle_as_of)
);

CREATE INDEX settlement_evidence_bet_asof ON settlement_evidence(bet_id, bundle_as_of DESC);

CREATE TABLE contradiction (
    id BIGSERIAL PRIMARY KEY,
    bet_id BIGINT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    kind VARCHAR(32) NOT NULL,
    severity DOUBLE PRECISION NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolution_note TEXT,
    payload_json JSONB NOT NULL
);

CREATE TABLE settlement_audit (
    id BIGSERIAL PRIMARY KEY,
    bet_id BIGINT NOT NULL,
    decision VARCHAR(24) NOT NULL,          -- SETTLE | HOLD | ESCALATE | VOID | MANUAL
    reason VARCHAR(64) NOT NULL,
    confidence DOUBLE PRECISION,
    evidence_id BIGINT REFERENCES settlement_evidence(id),
    decided_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE settlement_diff_log (
    id BIGSERIAL PRIMARY KEY,
    bet_id BIGINT NOT NULL,
    old_reason VARCHAR(64),
    new_reason VARCHAR(64),
    diff_kind VARCHAR(32),  -- AGREE | OUTCOME_DIFF | CONFIDENCE_DIFF
    old_winner BIGINT,
    new_winner BIGINT,
    decided_at TIMESTAMPTZ NOT NULL
);
```

### 9.2 Extend existing tables

Add to `paper_trade_bet`:
- `identity_lock_p1 BIGINT`, `identity_lock_p2 BIGINT`, `identity_lock_booker_event_id VARCHAR(128)`, `identity_lock_set_at TIMESTAMPTZ`.
- `learning_eligible BOOLEAN NOT NULL DEFAULT TRUE`.

Add to `tracked_match_observation`:
- `source_id VARCHAR(16) NOT NULL`, `tier VARCHAR(16) NOT NULL`, `raw_payload_ref VARCHAR(128)`.

---

## 10. API Surface

```
GET  /api/score-truth/bets/{betId}/evidence          → latest bundle
GET  /api/score-truth/bets/{betId}/evidence/history  → paginated
GET  /api/score-truth/bets/{betId}/timeline          → merged timeline
GET  /api/score-truth/contradictions?status=open     → paginated feed
POST /api/score-truth/contradictions/{id}/resolve    → operator annotation
POST /api/score-truth/bets/{betId}/manual-review     → force ManualReview
POST /api/score-truth/bets/{betId}/manual-settle     → operator override (winner, reason, note) — logged
GET  /api/score-truth/sources/health                 → per-source SLA
```

All endpoints return stable JSON contract; documented in OpenAPI.

---

## 11. Frontend — Score Truth Monitor Page

Routes: `/score-truth`

Widgets:
1. **Contradiction feed** (live). Each card: bet summary, two disagreeing sources side-by-side, severity, "mark resolved" and "open bet in Live Studio" actions.
2. **Ambiguity histogram** — distribution of ambiguity scores over the last 24 h.
3. **Per-source confidence matrix** — source × outcome-class heatmap showing calibration over rolling 7-day window.
4. **Evidence viewer** (opened from any bet) — timeline of observations, flagged contradictions, decision log.

See `TTLElite-Series-3.0-UI-Redesign-Spec.md` for component details.

---

## 12. Release Gate Additions for Score Truth

- `SCORE_BACKED_*` reasons ≥ 60% of all settlements over the last 72 h.
- `LAST_SCORE_HEURISTIC` ≤ 10% and tagged `learning_eligible=false`.
- Zero `OFFICIAL_RESULT_CONFIRMED` settlements where `ambiguityScore > 0.7` went without manual review.
- Zero wrong-match settlements on the Run-56 replay corpus (CI asserts).
- Every bet that settles has `evidence.sources.size() ≥ 2` OR carries an explicit `tainted=true` tag.

---

## 13. Open Questions

1. How do we canonicalize player identity across Hard Rock, Sofascore, and TT-Series? (Fuzzy name match + alias table is starting point; Phase 01 will need a shared `CanonicalPlayerId` resolver.)
2. Should `Escalate` decisions block `Settle` attempts during their TTL, or run in parallel? Recommendation: block for 30 s, re-evaluate.
3. Do we allow operator manual overrides during live sessions? Recommendation: yes, logged in `settlement_audit` with operator id + note.

---

*End of Score Truth Engine Spec v1.0.*
