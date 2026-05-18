# TTLElite Series 3.0 — Release Gate Checklist
_Document v1.0 — operator-facing gates and scripts for each phase transition_

This is the **checklist the operator runs** before promoting a phase from "shipped" to "stable" and before cutting a release tag. It is intentionally paranoid. Every gate has a pass criterion, an owner, and a "how to run" recipe. If a gate is yellow, the phase ships but flags remain half-open; if any gate is red, the phase does not promote.

## 0. How to use this document
1. Start a release ticket from the template `release-ticket.md`.
2. Pick the phase you're promoting.
3. Run every gate in order, pasting the output into the release ticket.
4. The release owner (default: Alex) must tick every gate and sign the bottom.
5. Promote. Watch the post-promotion dashboards for the soak window noted per phase.

## 1. Universal pre-flight (every phase)
| Gate | Pass criterion | How to run |
|---|---|---|
| **U1 Build green** | Main CI pipeline last 3 runs all green. | `gh run list -L 10 -b main --json conclusion,name,headSha` |
| **U2 Migrations applied** | `flyway info` shows zero pending on staging. | `./infra/scripts/flyway-info.sh staging` |
| **U3 No P0 open** | No open issue with `p0` label on the repo. | `gh issue list -l p0 --state open` |
| **U4 Backups fresh** | Last DB snapshot < 24 h; last MinIO replication < 6 h (when applicable). | `./infra/scripts/check-backups.sh` |
| **U5 Rollback drill** | Rollback script for the phase tested on staging within last 7 days. | `./infra/scripts/rollback-drill.sh phase-XX` |
| **U6 Docs synced** | `docs/ttlelite-series-3.0/phase-XX.md` reflects what actually shipped. | Manual diff review + PR link pasted. |

## 2. Phase 00 — Foundations & Scaffolding
| Gate | Pass | How |
|---|---|---|
| **P00-G1** All three facades (`PredictionFacade`, `SettlementFacade`, `FeedClient` adapters) instantiated and passing their unit tests. | 100 % unit tests green. | `./mvnw -pl backend test -Dtest='*Facade*,*FeedClient*'` |
| **P00-G2** Feature flags registered in `features.yaml` with owners and expiry. | 7 flags present; no expired flags. | `./scripts/lint-features.sh` |
| **P00-G3** `shadow-diff` harness captures at least one real decision with zero diff. | 1+ row in `settlement_diff_log` since last deploy. | `psql … -c "select count(*) from settlement_diff_log"` |
| **P00-G4** Prometheus + Grafana reachable; base dashboards render. | 200 on `http://monitor.dev.ttl/healthz`. | `curl -fsS http://monitor.dev.ttl/healthz` |
| **P00-G5** `/v3/` placeholder renders. | 200 HTTP, HTML contains `TTLElite v3`. | `curl -fsS https://staging.ttl/v3/` |

**Soak**: 24 h. **Rollback trigger**: any spike in `settlement_diff_log` > 1 % row disagreement or facade 5xx > 0.1 %.

## 3. Phase 01 — Data & Identity
| Gate | Pass | How |
|---|---|---|
| **P01-G1** New tables present and empty on promotion, then populating. | `odds_snapshot`, `mirror_observation`, `stream_observation`, `feed_health_sample`, `ingest_dlq` exist; all have >0 rows after 1 h. | `./infra/scripts/assert-tables.sh p01` |
| **P01-G2** Player canonicaliser precision on the labelled 500-pair benchmark. | Precision ≥ 0.99, recall ≥ 0.95. | `./mvnw test -Dtest=PlayerCanonicaliserBenchmarkIT` |
| **P01-G3** FeedHealth emits `feed.health` events ≥ 1/minute per feed. | `rate(feed_health_events_total[5m]) > 0` per source. | Prometheus query in `/v3/ops/feeds`. |
| **P01-G4** DLQ bounded. | `ingest_dlq_depth{source} < 50` sustained 1 h. | Prometheus. |
| **P01-G5** CLV baseline computable. | `clv_baseline.sql` returns a non-null value for last 7 days. | `mysql … < infra/sql/clv_baseline.sql` |

**Soak**: 48 h.

## 4. Phase 02 — Score Truth Engine MVP + Stream-CV Ingress
| Gate | Pass | How |
|---|---|---|
| **P02-G1** Ambiguity scorer unit tests. | 100 % green including property-based. | `./mvnw -pl backend test -Dtest='AmbiguityScorer*'` |
| **P02-G2** Contradiction guard simulation. | Shadow 2-week run: 0 uncaught contradictions vs. human-labelled ground truth. | `./scripts/replay-shadow-p02.sh` |
| **P02-G3** Stream-CV replay accuracy. | ≥ 95 % tuple accuracy on all 6 fixtures. | `./scripts/cv-replay.sh all` |
| **P02-G4** Settlement diff parity. | ≥ 99.5 % match between 2.0 and shadow Decisions on undisputed matches. | `./scripts/settlement-parity.sh last-14d` |
| **P02-G5** Evidence endpoint authenticated and authorised. | `/api/score-truth/evidence/{id}` requires auth; negative tests pass. | `./scripts/security-smoke.sh p02` |

**Soak**: 14 days shadow.

## 5. Phase 03 — Score Truth Promotion + Scraper Completeness
| Gate | Pass | How |
|---|---|---|
| **P03-G1** Advisory overturn rate. | ≤ 0.2 % manual-review overrides reverse the engine's decision. | `/v3/ops/diffs` metric `override_rate`. |
| **P03-G2** `StaleLiveRecoveryService` exercised. | ≥ 10 recovered sessions since shadow start; zero left stuck > TTL. | SQL `select count(*) from paper_trade_session where state='RECOVERED' and updated_at_utc > now() - '14 days'`. |
| **P03-G3** New feeds live. | BetsAPI + AiScore + HR-TGT all emitting ≥ 1/min. | Feeds panel. |
| **P03-G4** Review queue depth. | p95 depth < 15 for 7 days. | Prometheus `manual_review_queue_depth`. |
| **P03-G5** Stream-CV Tier B + Tier C wired. | At least 3 distinct VLM fallbacks observed in CostGovernor log; none exceeded cap. | `select count(distinct match_id) from stream_vlm_call where called_at_utc > now()-'7 days'`. |

**Soak**: 14 days advisory.

## 6. Phase 04 — Ingestion Bus + VLM Budget + Raw Store
| Gate | Pass | How |
|---|---|---|
| **P04-G1** Redis Streams steady state. | Consumer lag p95 < 2 s for 7 days. | Redis `XPENDING` + Prometheus. |
| **P04-G2** MinIO raw store growth normal. | Bytes/day within ±30 % of estimate; lifecycle rule purging at 30 days. | `./infra/scripts/minio-metrics.sh` |
| **P04-G3** VLM budget. | Daily cost < $1.20 average over 7 days. | `stream_vlm_cost_usd_total` Prometheus. |
| **P04-G4** Blender Variant A walk-forward. | Brier ≤ 2.0 blender Brier; ECE ≤ 0.02; BSS ≥ 0. | `./scripts/predict-walkforward.sh --variant A --days 60` |
| **P04-G5** Blender shadow CLV. | 7-day CLV ≥ 0; Brier non-inferior. | `/v3/ml/quality/shadow`. |

**Soak**: 7 days post-Redis cutover.

## 7. Phase 05 — Prediction Stack Core + Markov Simulator
| Gate | Pass | How |
|---|---|---|
| **P05-G1** TrueSkill-2 + Weng-Lin ratings refresh nightly. | Both jobs green 7 days. | CI history. |
| **P05-G2** Rater disagreement rate. | ≤ 6 % of matchups flagged `dq.rater_disagreement`. | `prediction_rater_disagreement_total`. |
| **P05-G3** Markov latency. | p99 end-to-end `/v1/markov` < 80 ms. | Prometheus. |
| **P05-G4** Devigging invariants. | Unit tests: sum of devigged probs = 1 to 1e-9 for 10k random fixtures. | `./mvnw -pl backend test -Dtest=DeviggingInvariantsTest` |
| **P05-G5** Conformal coverage. | Empirical coverage ∈ [0.87, 0.93] at α=0.1 on test slice. | `./scripts/predict-conformal-coverage.sh` |
| **P05-G6** Variant B shadow non-inferior. | Variant B BSS ≥ Variant A BSS within 0.01. | `/v3/ml/quality/ab`. |

## 8. Phase 06 — Staking v3 + Settlement Promotion
| Gate | Pass | How |
|---|---|---|
| **P06-G1** Staking policy caps respected. | Zero violations in audit over 14-day shadow. | `select * from staking_audit_violation where …`. |
| **P06-G2** Kill-switch drill. | `/api/admin/kill-switch/staking` flips in ≤ 5 s; all bets blocked. | `./infra/scripts/kill-switch-drill.sh`. |
| **P06-G3** SettlementEngine promotion. | 2 weeks primary with zero Bug-A contradictions reaching users. | `contradiction.count` gauge. |
| **P06-G4** CLV non-inferior. | 14-day CLV ≥ 2.0 baseline with p < 0.05 (bootstrap). | `./scripts/clv-ab.sh`. |
| **P06-G5** Rollback plan exercised. | Flag flip back to 2.0 settlement works end-to-end in staging. | Rollback drill log. |

## 9. Phase 07 — UI Cutover
| Gate | Pass | How |
|---|---|---|
| **P07-G1** Lighthouse budgets. | LCP ≤ 2.0 s, TTI ≤ 1.5 s, CLS ≤ 0.05 on the canonical routes. | `./scripts/ui-perf.sh staging`. |
| **P07-G2** Initial JS payload. | ≤ 450 KB gz for landing; ≤ 120 KB per route thereafter. | `./scripts/ui-bundle.sh`. |
| **P07-G3** A11y audit. | WCAG 2.2 AA passes on 10 canonical pages; zero critical axe-core issues. | `./scripts/ui-a11y.sh`. |
| **P07-G4** Keyboard map complete. | Every documented shortcut works; cmd palette opens and navigates. | Manual scripted test log. |
| **P07-G5** Rollback works. | `/` falls back to 2.0 UI instantly on flag flip. | `./scripts/rollback-ui.sh`. |

## 10. Phase 08 — Tightening & Retirement
| Gate | Pass | How |
|---|---|---|
| **P08-G1** `PaperTradingService` ≤ 800 LOC. | Measured with `cloc`. | `cloc backend/src/main/java/.../PaperTradingService.java`. |
| **P08-G2** Dead code removed. | All feature flags with `state=off` for ≥ 30 days are deleted. | `./scripts/lint-features.sh --enforce-expiry`. |
| **P08-G3** Capacity plan signed off. | `infra/capacity-2026q3.md` present and signed. | `git log -- infra/capacity-2026q3.md`. |
| **P08-G4** v3.1 seeds filed. | Master Plan §11 updated; three v3.1 ideas have owners. | Master Plan diff review. |

## 11. Release ticket template
```markdown
# TTLElite Series 3.0 — Phase <nn> Release Ticket
- Phase: <nn>
- Release tag: v3.<phase>.<patch>
- Release owner: Alex
- Date (UTC): YYYY-MM-DD
- Rollback owner: <name>

## Universal pre-flight
- [ ] U1 Build green — <link>
- [ ] U2 Migrations applied — <paste output>
- [ ] U3 No P0 open — <link>
- [ ] U4 Backups fresh — <timestamp>
- [ ] U5 Rollback drill — <log link>
- [ ] U6 Docs synced — <pr link>

## Phase <nn> gates
- [ ] P<nn>-G1 …
- [ ] P<nn>-G2 …
- [ ] …

## Soak window
- Start: <utc>
- End:   <utc>
- Dashboards watched: <urls>

## Post-promotion sign-off
- Release owner: _signature_
- Eng on-call:   _signature_
```

## 12. Emergency procedures
- **Kill-switch** (highest blast radius): `POST /api/admin/kill-switch/staking?reason=…` — blocks all new bets, keeps read paths open, keeps feed ingestion running. Acknowledgment in logs with operator identity.
- **Settle-freeze**: `POST /api/admin/kill-switch/settlement?reason=…` — SettlementEngine stops promoting decisions; bets stay in `PENDING_EVIDENCE` until the flag is cleared.
- **Feed-quarantine**: `POST /api/admin/feeds/{sourceId}/quarantine` — stops ingestion from a specific source; useful when a feed goes wild.
- **CV-halt**: `POST /api/admin/stream-cv/halt?reason=…` — stops all Stream-CV workers; useful if a platform sends a legal request.
- **Policy-revert**: `POST /api/admin/policy/revert?toVersion=…` — restores a prior `policy.yaml`; reload is atomic.

All kill-switch flips page the on-call via the existing alerting path and write an immutable audit row.

## 13. Sign-off
_All gates ticked, all soak windows passed, post-promotion dashboards clean._

Release owner: __________________ Date (UTC): __________________

Eng on-call: __________________ Date (UTC): __________________

---
*End of Release Gate Checklist v1.0.*
