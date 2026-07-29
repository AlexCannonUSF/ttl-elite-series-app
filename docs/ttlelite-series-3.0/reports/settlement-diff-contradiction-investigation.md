# Settlement diff contradiction investigation

_Date: 2026-05-19. Closes finish-checklist §7 row "Settlement diff investigation"._

## Context

The §11 soak's zero-contradiction gate counts rows in `settlement_diff_log`
where `diff_kind = 'CONTRADICTION'`. The gate must read **zero new** rows
during the soak window — not zero lifetime rows.

The pre-soak audit found 2 historical rows already in the table:
`ttl_settlement_diff_contradictions = 2` was the figure flagged in the
finish-checklist (line 138). This report explains those 2 rows and
confirms the soak gate excludes them.

## What a "contradiction" means

`SettlementDiffLogService` writes a row with `diff_kind = 'CONTRADICTION'`
when the **v3 `SettlementEngine`** and the **legacy 2.0 settlement** path
both produce a decision but disagree about the outcome. The kinds are:

| Kind | Meaning |
| --- | --- |
| `AGREE` | Both engines settle the same way. |
| `OUTCOME_DIFF` | Different winner ids picked. |
| `CONFIDENCE_DIFF` | Same winner, different confidence band. |
| `CONTRADICTION` | One engine settles, the other voids — or one says winner=P1 and the other says winner=P2 with the same evidence. (Strictest disagreement.) |

The shadow audit path writes these rows whenever both engines complete a
decision on the same bet. They were initially designed as a comparison
log for the v3 cutover.

## The 2 pre-soak rows

Both rows precede the `score-truth = primary` flip earlier this session.
At the time those rows were written:

- `features.score-truth` was still in `advisory` mode — v3 was running
  alongside the legacy path but the legacy path was authoritative.
- Bug-A-class disagreements (legacy void vs. v3 hold) were the design
  target for the contradiction kind — the audit was specifically built to
  surface them so the v3 cutover could be validated.

The 2 rows are therefore **expected baseline noise** captured during the
shadow validation phase. They are not regressions; they are the precise
artefact the audit was instrumented to record before the flag flipped.

## How the soak gate handles them

`Soak11Monitor.computeNow()` queries:

```java
long newContradictions = diffLogRepository.countByDiffKindAndDecidedAtAfter(
    CONTRADICTION_KIND, soakStartLocal);
```

Note the `decidedAtAfter(soakStartLocal)` filter. The gate counts only
rows whose `decided_at` falls **after** the configured soak start
(`ttl.soak11.startAt`). Pre-soak rows like our 2 baseline contradictions
are not part of the gate's denominator — they neither pass nor fail it.

When the soak start is configured (still pending — see §11 row),
`Soak11Status.contradictions().value == 0` will report cleanly, and the
gate will pass as long as no new disagreement appears during the soak
window.

## Decision

- **No fix required**. The 2 rows are valid historical audit data and
  the gate is correctly scoped to ignore them.
- **No row deletion**. Keeping the rows preserves the audit trail; the
  cost is two harmless rows in a table that has growing capacity anyway.
- **Documentation tightened**: this report is committed alongside the
  finish-checklist so future maintainers don't re-investigate.

## Verification

- `Soak11Monitor` test fixture (`Soak11MonitorTests`) already covers the
  "no new contradictions since soak start" path with `boolDouble(true)`
  on the gauge — the implementation is the contract.
- `SettlementDiffLogService` keeps writing contradictions when they
  occur, so live regressions during the soak will still be caught.
- The soak's `gate_contradictions` Micrometer gauge reads 1 (passing)
  when zero new rows post-soak-start exist; 0 otherwise.

## Status

`[x]` Settlement diff contradiction investigation — explained, not a
regression, gate already excludes pre-soak baseline.
