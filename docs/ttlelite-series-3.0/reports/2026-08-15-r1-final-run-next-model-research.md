# Final R1 Run Analysis and Next-Model Implementation Brief

- **Report date:** 2026-08-15
- **Run analyzed:** Session 72, `Accuracy Guardrails R1 - Fresh Run 2026-08-09`
- **Frozen model:** `accuracy-guardrails-r1-20260809160918-ENSEMBLE-91791`
- **Paper policy:** `accuracy-guardrails-r1-fixed-dollar`
**Purpose:** Preserve everything learned from the completed R1 observation window and define the evidence-based work required before the next model is trusted or promoted.

> This report supersedes the earlier interim snapshot in `2026-08-12-accuracy-consensus-r2.md`. That snapshot contained 249 calls and 234 settled outcomes. The final preserved R1 evidence contains 274 calls and 236 trusted outcomes. R2 code exists, but it was not the model used for this run and therefore has not yet been validated live.

## 1. Executive decision

The last run produced useful model evidence, but it did **not** validate a profitable betting model.

The model picked the winner in **129 of 236 trusted results (54.66%)**, with a 95% Wilson interval of **48.29%–60.89%**. Its probabilities were reasonably conservative after calibration—Brier score **0.2467**, log loss **0.6863**, approximate ECE **3.36 percentage points**—but the win rate is not yet distinguishable from chance after accounting even for ordinary sampling uncertainty, and the true uncertainty is larger because the observations are clustered by date, player, and matchup.

A hypothetical flat **$1 wager on every model call lost $31.58 on $236 staked, or -13.38% ROI**. The estimated ROI interval is approximately **-24.05% to -2.71%**. Only the six policy-approved paper bets affected the simulated bankroll; they finished **2-4, -$2.52**, on $6 staked. Six wagers are far too few to tune staking or infer trigger profitability.

The most important discovery is a likely **player-order symmetry defect**:

- The model selected Player 2 in 148 of 236 outcomes, despite the sportsbook favorite being nearly balanced between Player 1 and Player 2.
- It identified Player 1 winners only 42.28% of the time, but Player 2 winners 68.14% of the time.
- The trained model gives different coefficients to absolute `P1 Recent Form` and `P2 Recent Form`, so swapping the same two players is not guaranteed to produce complementary probabilities.

That defect must be resolved before further fine-tuning. Changing edge thresholds while the representation can depend on arbitrary player order would optimize around a structural error.

The second major finding is that **Hard Rock's no-vig probability was a better winner benchmark than the model during this run**. Market-only direction was correct 58.05% of the time versus the model's 54.66%, and market-only Brier score was approximately 0.2329 versus 0.2467 for the model. Blending more market probability into the model improved in-sample calibration, but no tested blend produced profitable flat betting. The market should become a mandatory benchmark and calibration anchor—not be mistaken for a source of automatic betting profit.

### Recommended next step

Do not immediately hand-tune dozens of weights. Build and validate an **R2.1 integrity candidate** first:

1. Enforce player-swap invariance and audit every ID/name/score orientation path.
2. Require exact model-version pinning and complete decision telemetry.
3. Join timestamp-correct Hard Rock no-vig probabilities into training and evaluation.
4. Keep fixed $1 paper staking and adaptive/live parameter application disabled.
5. Run the existing R2 calibration changes in shadow after the integrity fixes.
6. Promote a later `accuracy-symmetric-market-r3` only after strict future-only, cluster-aware validation.

## 2. Evidence and scope

The analysis used a read-only copy of the persisted H2 database. The active database and live run were not altered.

### Session identity

| Field | Preserved value |
|---|---:|
| Session ID | 72 |
| Created | 2026-08-09 16:23:53 local |
| Last synchronization | 2026-08-12 20:28:27 local |
| Session status in database | `ACTIVE` |
| Starting bankroll | $1,000.00 |
| Ending bankroll | $997.48 |
| Peak bankroll | $1,000.48 |
| Paper bets | 6 |
| Paper record | 2-4 |
| Paper P&L | -$2.52 |
| Paper ROI | -42.00% |
| Decision rows scanned | 17,312 |
| Frozen model calls | 274 |
| Trusted settled calls | 236 |
| Unresolved calls | 38 |

The session is marked active because it was stopped without a formal close transition. The evidence itself stopped changing. This should be corrected operationally in future runs so a stopped run is closed and versioned explicitly.

### Settlement integrity

- 236 calls were system-confirmed using `TRUSTED_TERMINAL_SCORE`.
- 29 calls were still waiting for feed completion.
- 9 were in settlement review.
- All trusted outcomes used terminal score evidence; there were no viewer-grade conflicts.
- Trusted settlement coverage was **86.13%**.
- The 38 unresolved calls must not be treated as wins, losses, or negatives during training.

Thirteen late calls were labeled only `ENSEMBLE` instead of the frozen R1 artifact and all 13 were unresolved. Future sessions must reject or quarantine calls whose artifact ID does not exactly match the session's pinned model.

## 3. Final all-call scorecard

This scorecard measures every frozen directional prediction with a trusted terminal result, including calls that did not become paper bets.

| Metric | Result | Interpretation |
|---|---:|---|
| Trusted predictions | 236 | Useful directional sample, but clustered |
| Correct / wrong | 129 / 107 | 54.66% accuracy |
| 95% Wilson interval | 48.29%–60.89% | Does not establish above-chance accuracy |
| Average stated probability | 57.07% | Slightly above the observed hit rate |
| Brier score | 0.2467 | Only modestly better than an uninformative 0.25 baseline |
| Log loss | 0.6863 | Close to the 0.693 random baseline |
| Approximate ECE | 3.36 pp | Aggregate calibration looks acceptable, but bins are not monotonic |
| Flat $1 returned | $204.42 | From $236 hypothetical stake |
| Flat $1 net | -$31.58 | Negative across the complete scorecard |
| Flat $1 ROI | -13.38% | Not a profitable decision policy |
| Approximate ROI interval | -24.05% to -2.71% | Positive ROI confidence was roughly 0.7% |

### Capture state

| State when first captured | N | Correct | Accuracy | Flat $1 ROI |
|---|---:|---:|---:|---:|
| Prematch | 230 | 126 | 54.78% | -12.65% |
| Live | 6 | 3 | 50.00% | -41.27% |

There is not enough live-first data to tune a separate in-play model. The six live observations should remain descriptive only.

## 4. Probability calibration

| Model-probability band | N | Observed win rate | Mean probability | Brier | Flat $1 ROI |
|---|---:|---:|---:|---:|---:|
| 50.0%–52.5% | 52 | 50.00% | 51.14% | 0.2513 | -7.29% |
| 52.5%–55.0% | 47 | 51.06% | 53.61% | 0.2503 | -9.15% |
| 55.0%–57.5% | 42 | 54.76% | 56.35% | 0.2468 | -11.16% |
| 57.5%–60.0% | 31 | 61.29% | 58.54% | 0.2394 | -12.21% |
| 60.0%–62.5% | 28 | 50.00% | 61.13% | 0.2632 | -31.91% |
| 62.5%–65.0% | 13 | 61.54% | 63.83% | 0.2388 | -16.27% |
| 65.0%–70.0% | 18 | 61.11% | 66.36% | 0.2421 | -19.38% |
| 70.0%+ | 5 | 80.00% | 74.65% | 0.1544 | -9.64% |

Conclusions:

- Calibration is acceptable in aggregate but not monotonic enough to justify a single new probability cutoff.
- The 60.0%–62.5% band was materially overconfident.
- Higher probability increased winner accuracy in broad terms, but not enough to overcome price.
- A probability threshold is not an edge threshold. A -300 favorite can be highly likely to win and still be a negative expected-value wager.

### Temperature check

A retrospective temperature-only scan on these 236 calls produced nearly identical results from 1.25 through 1.75:

| Temperature | Brier | Log loss |
|---:|---:|---:|
| 1.00 | 0.246703 | 0.686316 |
| 1.25 | 0.246078 | 0.685082 |
| 1.40 | 0.245990 | 0.684938 |
| 1.55 | 0.246002 | 0.684993 |
| 1.75 | 0.246100 | approximately 0.685 |
| 2.00 | 0.246281 | approximately 0.685 |

R2's temperature of **1.55** is close enough to the broad optimum to retain for a shadow test. It should not be called proven: this comparison is retrospective and temperature cannot repair winner direction, feature orientation, or betting ROI.

## 5. Hard Rock market comparison and the margin question

All market comparisons in this report use the two-sided **no-vig Hard Rock probability**, not raw implied probability. This removes the sportsbook margin before comparing the model to the market.

### Winner benchmark

| Direction source | Correct | Accuracy | Brier |
|---|---:|---:|---:|
| Frozen R1 model | 129 / 236 | 54.66% | 0.2467 |
| Hard Rock no-vig favorite | 137 / 236 | 58.05% | 0.2329 |

The model and market disagreed on 60 matches. The model was correct on 26 and the market was correct on 34. This eight-call advantage is not a standalone significance claim, but it aligns with the stronger market Brier score.

### Model-minus-market no-vig gap

The gap below is `model probability - Hard Rock no-vig probability` for the side selected by the model.

| Gap | N | Model-pick accuracy | Flat $1 ROI |
|---|---:|---:|---:|
| Below -10 pp | 42 | 80.95% | +3.63% |
| -10 to -6 pp | 25 | 56.00% | -22.10% |
| -6 to -3 pp | 25 | 60.00% | -12.31% |
| -3 to 0 pp | 37 | 56.76% | -13.62% |
| 0 to +2 pp | 24 | 41.67% | -30.22% |
| +2 to +4 pp | 12 | 58.33% | -4.99% |
| +4 to +6 pp | 17 | 47.06% | -13.76% |
| +6 to +8 pp | 7 | 28.57% | -43.44% |
| +8 to +10 pp | 11 | 45.45% | -9.49% |
| Above +10 pp | 36 | 36.11% | -14.41% |

This is the clearest warning against treating a positive model/market gap as validated edge. The strongest winner segment occurred when Hard Rock was **more confident than the model in the same selected player**. Large positive model disagreements were weak.

The below -10 pp cell cannot be promoted as a strategy from one in-sample run. It is mostly the model agreeing with a strongly priced sportsbook favorite, and the sportsbook price already charges for that probability. Its +3.63% observed ROI is fragile and needs an independent future sample.

### Blend diagnostic

Retrospectively blending model probability with Hard Rock's no-vig probability improved directional calibration:

| Model weight | Approx. accuracy | Brier |
|---:|---:|---:|
| 1.000 | 54.66% | 0.2467 |
| 0.750 | 56.78% | 0.2416 |
| 0.625 | 57.63% | 0.2394 |
| 0.500 | 58.47% | 0.2375 |
| 0.250 | 57.63% | 0.2347 |
| 0.000, market only | 58.05% | 0.2329 |

No blend produced positive flat-bet ROI. The correct implementation is to use market probability as:

- a required calibration benchmark;
- a consensus or abstention signal;
- a leakage-safe historical feature when timestamped pre-start data is available;
- a way to explain fair odds and sportsbook margin to the user.

It must not be used as closing-price training data for a pregame prediction. Closing prices are evaluation-only.

## 6. The player-order symmetry defect

### Observed asymmetry

| Outcome view | Result |
|---|---:|
| Predicted Player 1 | 88 calls |
| Predicted Player 2 | 148 calls |
| Accuracy when predicting Player 1 | 59.09% |
| Accuracy when predicting Player 2 | 52.03% |
| Player 1 winners correctly identified | 52 / 123 = 42.28% |
| Player 2 winners correctly identified | 77 / 113 = 68.14% |

Confusion counts:

| Actual / predicted | Predicted P1 | Predicted P2 |
|---|---:|---:|
| Actual P1 | 52 | 71 |
| Actual P2 | 36 | 77 |

Hard Rock's favorite was Player 1 in 124 matches and Player 2 in 112, with comparable favorite accuracy on each side. That makes a simple competition-side effect less likely and points toward model representation, ID mapping, or score orientation.

Score-shape performance reinforces the concern:

| Terminal score | N | Model accuracy |
|---|---:|---:|
| 0-3 | 28 | 82.14% |
| 1-3 | 38 | 68.42% |
| 2-3 | 47 | 59.57% |
| 3-0 | 27 | 55.56% |
| 3-1 | 49 | 46.94% |
| 3-2 | 47 | 29.79% |

### Likely mechanism

The R1 logistic artifact uses independent absolute features for `P1 Recent Form` and `P2 Recent Form`, with materially different learned coefficients:

- `P1 Recent Form`: approximately **+0.08346**
- `P2 Recent Form`: approximately **+0.00641**

The intercept is also nonzero. A model built this way is not mathematically guaranteed to satisfy:

`P(A beats B) + P(B beats A) = 1`

The asymmetry could be compounded by player ID/name ordering, feature sign inversion, or terminal score orientation. It must be investigated end to end rather than assumed to be only one coefficient.

### Required repair

1. Replace separate absolute P1/P2 form terms with antisymmetric deltas, or parameterize shared strength plus a signed difference that flips exactly when players are swapped.
2. Prefer zero-intercept antisymmetric logits for a neutral matchup, unless an explicit and justified side effect exists.
3. Train with paired augmentation: every `(A, B, y)` example is accompanied by `(B, A, 1-y)`.
4. Add a swap-consistency loss or hard constraint.
5. Property-test at least 10,000 randomized matchups:
   - probabilities complement within tolerance;
   - predicted winner flips;
   - fair odds swap correctly;
   - feature-contribution signs reverse;
   - player IDs, names, scores, and terminal winners remain aligned.
6. Fail model publication on any unexplained swap violation. Use a target tolerance of `1e-6` for deterministic baseline calculations and at most `1e-3` for the complete calibrated pipeline.

This is a release blocker.

## 7. Trigger evidence

Top-trigger performance is descriptive, not causal. A feature can be named the top trigger because it had the largest contribution in a call, while correlated features or market state produced the actual result.

| Top trigger | N | Correct | Accuracy | 95% Wilson interval | Flat $1 ROI | Assessment |
|---|---:|---:|---:|---:|---:|---|
| Schedule Strength | 118 | 64 | 54.24% | 45.26%–62.95% | -12.93% | Dominant but not discriminative |
| Opponent-Adjusted Form | 30 | 16 | 53.33% | 36.14%–69.77% | -16.34% | No demonstrated benefit |
| Head-to-Head | 23 | 13 | 56.52% | 36.81%–74.37% | -4.43% | Plausible, still small |
| P1 Recent Form | 21 | 6 | 28.57% | 13.81%–49.96% | -55.59% | Harmful and orientation-sensitive |
| Rater Consensus | 16 | 11 | 68.75% | 44.40%–85.84% | -3.51% | Promising direction, not enough data |
| Glicko Rating | 12 | 10 | 83.33% | 55.20%–95.30% | +13.38% | Strongest promising small segment |
| Rater Ensemble | 6 | 4 | 66.67% | Very wide | Negative | Research only |
| TS2 Rating | 4 | 3 | 75.00% | Very wide | Not stable | Research only |
| Glicko Probability | 3 | 2 | 66.67% | Very wide | Not stable | Research only |
| Glicko RD | 3 | 0 | 0.00% | Very wide | Negative | Too small; audit sign |

What is sufficiently supported:

- `P1 Recent Form` as an absolute trigger should be removed or transformed.
- Schedule strength is contributing too much relative to its directional accuracy.
- Glicko and rater-based information deserves preservation and controlled validation.
- No trigger has enough independent observations to justify a manual profitability weight.

## 8. Factor directionality and weight implications

| Factor | Eligible N | Directional accuracy | Mean absolute contribution | Finding |
|---|---:|---:|---:|---|
| Rater Ensemble | 202 | 57.43% | 0.0928 | Useful candidate |
| Glicko Probability | 199 | 57.29% | 0.0834 | Useful candidate |
| Schedule Strength | 197 | 48.73% | 0.2408 | Largest contribution, below-chance direction |
| P1 Recent Form | 190 | 48.95% | 0.1004 | Large, weak, and side-sensitive |
| TS2 Rating | 180 | 59.44% | Lower | Promising |
| Glicko Rating | 179 | 58.10% | Lower | Promising |
| Rater Consensus component | 154 | 38.96% | Material | Often acting as a counter-signal; inspect orientation |
| Opponent-Adjusted Form | 147 | 44.90% | Moderate | Shrink or reconstruct |
| Head-to-Head | 111 | 53.15% | Moderate | Retain with decay and sample guard |
| Recent Form Delta | 109 | 51.38% | Moderate | Neutral, safer than absolute side form |
| Weng-Lin Rating | 91 | 32.97% | Moderate | Sign/orientation audit required |
| Volatility | 54 | 51.85% | Small | Keep as uncertainty modifier |
| Form × H2H | 48 | 52.08% | Small | Insufficient |
| Glicko RD | 16 | 56.25% | Small | Insufficient |
| Elo | 8 | 37.50% | Small | No conclusion |

The frozen trained logistic coefficients support the same concerns:

- Schedule delta is the largest positive weight, approximately **+0.2183**.
- Glicko rating and probability are both positive, approximately **+0.1126** and **+0.1042**.
- Rater ensemble is positive, approximately **+0.0973**.
- Weng-Lin and rater consensus are negative, approximately **-0.0189** and **-0.0458**.
- The two absolute recent-form weights are unequal.

### Recommended treatment

Do not enter new exact weights by hand from this table. Instead:

- enforce antisymmetric feature construction;
- group correlated rating, form, schedule, and matchup features;
- apply group regularization or contribution caps;
- cap any single feature group at roughly 20%–30% of absolute per-call logit contribution during early candidates;
- shrink schedule, absolute recent-form, and opponent-adjusted form influence;
- allow Glicko, TS2, and rater ensemble to compete under regularization;
- quarantine Weng-Lin and the rater-consensus component until sign and player orientation are verified;
- retain H2H only with recency decay, minimum shared-match count, and uncertainty widening when thin.

## 9. Rating agreement and reliability telemetry

### Rating agreement

| Agreement band | N | Accuracy | Flat $1 ROI |
|---|---:|---:|---:|
| Below 0.50 | 101 | 54.46% | -9.78% |
| 0.50–0.60 | 24 | 50.00% | -28.12% |
| 0.60–0.65 | 15 | 53.33% | -16.05% |
| 0.65–0.75 | 35 | 60.00% | -11.59% |
| 0.75–0.90 | 56 | 53.57% | -14.75% |
| 0.90+ | 5 | 60.00% | Too small |

Interactions were more encouraging:

- Probability at least 55% and rating agreement at least 0.65: 35/53, **66.04%**, -2.77% ROI.
- Probability at least 60% and rating agreement at least 0.65: 17/24, **70.83%**, -5.37% ROI.

The accuracy improvement is useful, but the lack of positive ROI and non-monotonic bands mean rating agreement is a sensible **quality gate**, not a proven source of value. R2's 0.65 minimum should be tested unchanged in shadow.

### Reliability fields are not calibrated

The current `overallReliability` and `triggerReliability` scores were non-monotonic:

- `overallReliability` 0.8–0.9: 169 calls, 52.07% accuracy.
- `overallReliability` 0.7–0.8: 46 calls, 63.04% accuracy.
- `triggerReliability` 0.8–0.9: 177 calls, 51.98% accuracy.
- `triggerReliability` below 0.5: 26 calls, 73.08% accuracy.

These fields should not control production stake or promotion until their definitions are rebuilt and their bins demonstrate monotonic out-of-sample behavior. Rename them to `heuristicReliability` in the UI if their current construction is retained, or calibrate them against actual outcomes.

`signalQuality` was populated for only 14 of 274 calls and only two trusted settlements. `selectionScore` was missing for all 274. These are telemetry defects, not evidence of low model quality.

## 10. Paper-pick audit

| Pick | Selection | Result | Placed odds | Model P | Stored edge | Trigger |
|---:|---|---|---:|---:|---:|---|
| 1 | Grzegorz Marud | Loss | -105 | 61.11% | 9.89% | Schedule Strength |
| 2 | Krystian Gaik | Win | -110 | 61.80% | 9.42% | Glicko Rating |
| 3 | Krzysztof Kapik | Win | -175 | 67.41% | 3.78% | Schedule Strength |
| 4 | Krzysztof Schaniel | Loss | -140 | 60.64% | 2.30% | Head-to-Head |
| 5 | Michal Wolny | Loss | -140 | 64.75% | 6.41% | Schedule Strength |
| 6 | Jakub Glanowski | Loss | -175 | 66.12% | 2.48% | Schedule Strength |

Additional facts:

- Four schedule-triggered bets finished 1-3 and lost approximately 60.7% of their stake.
- The Glicko-triggered bet won; the one H2H bet lost.
- Closing-line coverage was 6/6.
- Stored CLV, defined as closing implied probability minus placed implied probability, averaged approximately **-0.066 percentage points**: effectively flat and slightly negative.
- Settlement took an average of about 71 minutes, ranging from 52 to 79 minutes.
- All six used targeted-completion settlement with high settlement confidence.

Five of these six bets would have failed R2's 0.65 rating-agreement guard. That makes the new guard reasonable to validate, but not proven by six post-hoc examples.

Keep $1 flat stakes. Do not enable Kelly staking or increase stake until there are at least 100–200 genuinely future paper bets with positive CLV and a positive ROI confidence interval.

## 11. Data dependence and generalization risk

The 236 trusted outcomes are not 236 fully independent experiments:

- They span only three capture dates: August 9, August 10, and one result on August 12.
- There were 77 unique players, with a median of five appearances and a maximum of ten.
- There were 189 unique player pairs.
- Forty-seven pairs repeated, accounting for 94 observations.

Daily accuracy was stable but not strong:

| Capture date | N | Accuracy |
|---|---:|---:|
| Aug 9 | 91 | 54.95% |
| Aug 10 | 144 | 54.17% |
| Aug 12 | 1 | Correct, not meaningful |

Sequential blocks also showed no evidence that the live run learned itself into a better model:

| Resolution-order block | Accuracy |
|---|---:|
| 1–40 | 55.00% |
| 41–80 | 50.00% |
| 81–120 | 55.00% |
| 121–160 | 55.00% |
| 161–200 | 52.50% |
| 201–236 | 61.11% |

The final block is encouraging but not enough to establish a trend. Live parameter application was disabled, so any apparent improvement is event mix, not confirmed online learning.

Future uncertainty estimates must use clustered resampling by date, player, and player pair. A simple Wilson interval should remain visible as a descriptive statistic but cannot be the promotion decision by itself.

## 12. Training artifact audit

The R1 registry artifact reported:

| Training field | Value |
|---|---:|
| Training window | Jul 11–Jul 23 |
| Calibration window | Jul 25–Jul 28 |
| Validation/test window | Jul 30–Aug 9 |
| Training dates | 24 |
| Training samples | 2,880 |
| Test samples | 600 |
| Held-out accuracy | 62.45% |
| Held-out Brier | 0.228228 |
| Held-out log loss | 0.64667 |
| Logistic ensemble weight | 1.0 |
| Advanced-model weight | 0.0 |
| Market benchmark | `UNAVAILABLE_NO_HISTORICAL_CLOSING_PRICE_JOIN` |
| Time-slice stability | Failed |
| Bootstrap stability | Failed |
| Promotion approved | No |
| Promotion reason | `FAILED_TEMPORAL_STABILITY` |

The future run fell from 62.45% reported validation accuracy to 54.66%. Part of that gap may be ordinary drift, dependence, or selection, but the validation window ending on the same date the live run began also creates a potential boundary/leakage risk. Future evaluation must begin strictly after the final validation timestamp with an embargo and as-of joins for every rating and market feature.

It was correct that R1 was not promotion-approved. The session intentionally froze it as a research artifact, and its evidence must not be described as production validation.

## 13. Decision and telemetry pipeline findings

There were 17,338 raw decision-sample rows across 274 events because the same events were evaluated repeatedly on polling ticks. The session's persisted summary counter recorded 17,312 rows scanned; the 26-row difference is an operational-counter discrepancy that should be instrumented, but it does not change the frozen-call or settled-outcome counts. Polling rows are operational observations, not independent model samples.

Most common polling decisions:

| Reason | Polling rows |
|---|---:|
| Edge below threshold | 5,983 |
| Model probability too low | 3,888 |
| Event not upcoming | 3,820 |
| American odds too high | 2,862 |
| Fair odds too long | 311 |
| Duplicate open event | 304 |
| Plus-money guard | 81 |
| Confidence interval too wide | 72 |
| Missing suggested edge | 11 |
| Placed fallback | 6 |

For learning and reporting, collapse these into one frozen pre-decision snapshot per event/version/policy, plus a separate time-series table for operational polling. Do not calculate trigger confidence or sample size from the repeated rows.

Frozen call decision reasons were dominated by `EVENT_NOT_UPCOMING` because calls continued to be observed outside the placement window. Six calls had paper picks even though all frozen-call decision statuses read `SKIPPED`; `hasPaperPick` held the real distinction. The UI should present separate fields:

- model call created;
- policy eligible at capture;
- paper bet placed;
- settlement state;
- learning eligibility.

This prevents “skipped” from appearing to contradict an actual paper bet.

## 14. Admin learning-audit mismatch

The admin learning audit currently reports 501 historic paper-learning samples, of which 43 are trusted and 458 excluded. It is not the same population as the 236 trusted all-call outcomes in this report.

Current exclusion summary:

| Exclusion | Count |
|---|---:|
| Non-binary outcome | 283 |
| Legacy low confidence | 166 |
| Contradictory evidence | 5 |
| Low-confidence settlement | 4 |

The two datasets answer different questions:

- **All-call outcome ledger:** How well did the frozen model rank every observed completed match?
- **Learning-eligible paper ledger:** Which historic paper decisions qualify to influence adaptive analysis?

The admin model page must label and display these separately. Its 8.58% eligible coverage, 43-sample ECE, and trigger tables cannot be shown as though they describe Session 72's 236 settled calls.

## 15. What R2 already changed but has not proven

The repository's current R2 settings were written after the early R1 analysis. They are sensible candidates, not live-validated results.

| Setting | R1 | Current R2 | Decision |
|---|---:|---:|---|
| Probability temperature | 1.25 | 1.55 | Retain for shadow validation |
| Uncertainty shrink | 0.55 | 0.62 | Retain provisionally |
| Consensus shrink | 0.35 | 0.45 | Retain provisionally |
| Max training dates | 24 | 30 | Retain; verify performance and runtime |
| Lambda candidates | 0 to 0.01, very light | 0.003 to 0.3 | Correct direction; retain |
| Exploration min edge / ROI | 1.5% | 2.0% | Retain; do not lower to manufacture bets |
| Absolute model/market guard | None | 10 pp | Retain as hard cap, add directional handling |
| Minimum rating agreement | None | 0.65 | Retain for shadow validation |
| Plus-money paper bets | Guarded out | Guarded out | Retain until separate evidence exists |
| Stake | Flat $1 | Flat $1 | Retain |
| Live/adaptive application | Off | Off | Retain |

R2 must not be described as having improved the final R1 scorecard; it never generated these calls.

## 16. Exact next-version implementation specification

### Stage A — R2.1 integrity patch: required before tuning

**Model identity**

- Generate one immutable artifact ID and pin it to the session.
- Reject generic `ENSEMBLE` fallback calls once a session is pinned.
- Persist artifact checksum, feature-schema checksum, calibration ID, policy ID, and code revision on every frozen call.

**Symmetry**

- Convert all directional inputs to canonical antisymmetric deltas.
- Remove independent P1/P2 coefficients unless a formally modeled, empirically verified side effect exists.
- Pair-augment training examples and add swap-consistency tests.
- Audit player IDs, names, feature signs, fair odds, scores, and winners through settlement.

**Market data**

- Persist both raw Hard Rock odds, overround, and each side's normalized no-vig probability at every timestamp.
- Join only snapshots available before the prediction timestamp.
- Store closing price separately for CLV evaluation; never feed it into a prior prediction.
- Make the historical market benchmark mandatory for promotion.

**Telemetry**

- Make `selectionScore` and `signalQuality` required on frozen calls.
- Persist raw and calibrated probability, uncertainty interval, rating agreement, model-market no-vig gap, feature contributions, and all gate results.
- Separate model call, policy decision, bet placement, settlement, and learning-eligibility statuses.
- Automatically close stopped sessions and preserve an immutable run summary.

### Stage B — R2 shadow policy

Run the already-configured R2 calibration changes with these additional constraints:

- Minimum calibrated model probability: keep 0.60 for the controlled paper lane.
- Minimum rating agreement: keep 0.65.
- Absolute model/market no-vig divergence cap: keep 10 pp.
- Directional disagreement treatment:
  - when model probability exceeds market no-vig probability by more than 4 pp, keep the call in all-call learning but make it research-only for paper placement until independently validated;
  - allow market-confirmed calls to enter the existing gates, but do not assume that confirmation creates positive value.
- Continue excluding plus-money paper bets until an independently sampled underdog lane is defined.
- Use one fixed $1 stake, at most one new placement per synchronization cycle.
- Keep Kelly staking, automatic calibration updates, and automatic weight updates off.

The +4 pp directional guard is deliberately conservative and should be registered before the run. It is based on the observed degradation in positive-gap bands but must be treated as a temporary research guard, not a permanent optimized threshold.

### Stage C — `accuracy-symmetric-market-r3` candidate

Train only after Stage A is complete:

- antisymmetric feature schema;
- paired training augmentation;
- strict chronological splits with an embargo;
- player/pair grouping so repeated matchups do not leak across folds;
- historical pre-start Hard Rock no-vig benchmark available;
- grouped regularization across rating, form, schedule, and matchup signals;
- calibration temperature selected on calibration data only from a small preregistered grid such as 1.25, 1.40, 1.55, and 1.75;
- market probability used as a benchmark and optional constrained calibration feature;
- schedule and form contribution caps;
- Weng-Lin and rater-consensus features disabled until orientation tests pass.

Do not initialize R3 by manually copying the apparent 83% Glicko trigger win rate. Let regularized future-only validation decide the weight.

## 17. New-run research design

Every completed match should produce a learning row whether or not it became a paper bet. Keep three lanes distinct:

1. **All-call lane:** every frozen model winner and probability; measures ranking and calibration.
2. **Policy lane:** calls that satisfied the preregistered paper policy; measures actionable selection.
3. **Stratified exploration lane:** a small, capped, fixed-$1 sample across predefined probability, market-gap, and trigger buckets; learns about rejected regions without loosening the production-style policy.

This answers the desire for more samples without corrupting the policy or pretending repeated polling decisions are new observations.

Suggested stratification dimensions:

- calibrated probability: 50–55, 55–60, 60–65, 65%+;
- model-minus-market no-vig gap: below -6, -6 to 0, 0 to +4, above +4 pp;
- agreement: below 0.65 versus at least 0.65;
- trigger family: rating, form, schedule, H2H, consensus;
- capture state: prematch versus live;
- side: canonical A/P1 versus B/P2;
- favorite/underdog and odds band.

Register the bucket limits and maximum exploration stake before starting. Do not change them mid-run based on early results.

## 18. Promotion gates

No candidate should become the default model unless all integrity and quality gates pass.

### Integrity gates

- 0 unexplained swap-invariance failures in at least 10,000 randomized tests.
- 100% session/model artifact pinning.
- At least 99% completeness for required call telemetry.
- At least 95% trusted terminal-result coverage.
- No unresolved match more than two hours past expected completion unless tied to a documented feed outage.
- 100% timestamp/as-of validation for market, rating, form, and H2H inputs.
- Historical market benchmark present; fail closed when absent.

### Model-quality gates

- At least five distinct future capture days and at least 500 trusted all-call outcomes before model promotion; 300 may support an interim checkpoint but not final promotion.
- Cluster-bootstrap intervals by date, player, and player pair.
- Brier and log loss no worse than the timestamp-matched Hard Rock no-vig benchmark.
- Calibration ECE at or below 3 percentage points with no severe high-confidence regression.
- Model improvement over the market demonstrated on strictly future data, not on the training or threshold-selection window.
- Player-side accuracy gap no larger than five percentage points after controlling for market probability.
- No trigger or feature group promoted from fewer than 100 independent outcomes; 30–99 remains exploratory.

### Betting-policy gates

- At least 100 future paper bets for a provisional policy read and preferably 200 before staking changes.
- Positive CLV with at least 90% closing-line coverage.
- Positive ROI with a confidence interval whose lower bound is above zero before any claim of profitability.
- Flat $1 staking until the above gates pass.
- Kelly remains disabled until probability calibration and ROI both pass independently.

## 19. Admin model hub requirements

The next admin model page should preserve each run as an immutable version card with:

- run label, dates, status, artifact ID, feature schema, calibration, and policy;
- all-call, policy, and exploration scorecards shown separately;
- accuracy, Brier, log loss, ECE, flat-$1 ROI, CLV, and confidence intervals;
- model-versus-market benchmark and disagreement matrix;
- calibration curve and sample counts per bin;
- side/orientation matrix and swap-test status;
- trigger and factor tables with minimum-sample badges;
- outcome ledger containing players, final score, selected winner, probability, Hard Rock raw and no-vig odds, trigger, policy decision, settlement source, and version;
- unresolved pipeline with age and exact blocking stage;
- excluded-label table and reason;
- model history comparison that never mixes all-call outcomes with paper-learning eligibility;
- explicit badges for `research`, `shadow`, `promotion failed`, and `approved`.

Confidence language should be generated from preregistered thresholds, not from whether a card is green. Recommended labels:

- fewer than 30 outcomes: **anecdotal**;
- 30–99: **exploratory**;
- 100–299: **directional**;
- 300–499 across multiple days: **provisional**;
- 500+ with cluster-aware gates passed: **decision-grade**.

## 20. What not to change from this run alone

- Do not lower the edge threshold merely to create more paper bets.
- Do not increase stakes or enable Kelly.
- Do not auto-apply online calibration or live feature weights.
- Do not promote Glicko solely from 10 wins in 12 top-trigger calls.
- Do not permanently remove H2H from 23 calls.
- Do not convert the below -10 pp market-confirmed cell into a betting rule.
- Do not train on unresolved outcomes or infer a winner from a last score unless it passes explicit terminal-score rules.
- Do not count every polling evaluation as a learning sample.
- Do not claim R2 improvement until R2 produces a clean future run.

## 21. Implementation order

1. Freeze and archive Session 72 as the final R1 research run.
2. Repair symmetry and add the complete orientation test suite.
3. Enforce model pinning and telemetry completeness.
4. Add leakage-safe historical Hard Rock no-vig joins and the market benchmark.
5. Split the admin all-call and paper-learning datasets.
6. Retrain R2.1 with the symmetric schema; do not reuse an incompatible artifact.
7. Run the corrected R2.1 artifact in shadow using the current R2 calibration settings, fixed $1 stakes, and preregistered gates.
8. Review at 100, 300, and 500 trusted all-call outcomes; do not tune between checkpoints.
9. Build R3 weights only from strict future validation and cluster-aware comparisons.
10. Promote only if every integrity, model-quality, and betting-policy gate passes.

## 22. Final conclusion

The run succeeded as research because it exposed where the system is trustworthy and where it is not:

- Terminal score settlement works well for the 236 resolved calls.
- The model captures some winner information, but not enough to establish improvement over chance or Hard Rock.
- Aggregate calibration is close, so probability shrinkage is not the main failure.
- The betting edge was not real in this sample after price and sportsbook margin.
- Hard Rock no-vig probability must be a first-class benchmark.
- The current feature design likely violates player-swap symmetry and must be repaired before tuning.
- Schedule and recent-form signals are over-influential; Glicko, TS2, and rater signals are promising but not proven.
- Reliability labels and parts of the decision telemetry currently overstate what is known.
- The six paper bets are useful pipeline tests, not a statistical strategy sample.

The correct next model is not “R1 with a looser threshold.” It is a symmetric, timestamp-safe, market-benchmarked candidate whose evidence is collected across all calls, while a small fixed-dollar policy and a capped exploration lane remain separate. That design gives the next run a genuine chance to tell us whether the model adds predictive information and whether any of that information survives the sportsbook price.
