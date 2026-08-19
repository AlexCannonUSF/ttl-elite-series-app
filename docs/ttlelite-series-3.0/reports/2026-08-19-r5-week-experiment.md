# R5 seven-day experiment: evidence, design, and promotion contract

Date: 2026-08-19  
Status: pre-registered week experiment; not a claim of production superiority

## Executive decision

The next run separates three questions that the previous implementation mixed together:

1. **Forecast accuracy:** did the model's most-likely winner win?
2. **Paper-sampler health:** can the application collect a useful, bounded stream of resolved observations?
3. **Economic value after vig:** would the offered Hard Rock price have produced positive expected return and realized ROI?

R5 keeps a conservative fixed-$1 operational accuracy audit for sample collection and grades five independent, actual-price economic portfolios at 0%, 1%, 2%, 3%, and 5% expected-return thresholds. A forecast observation is never labeled a positive-value bet merely because it was admitted to the accuracy audit.

## Frozen evidence from the completed run

Run 93 is immutable and remains the primary recent holdout snapshot:

- 169 frozen calls; 149 trusted resolutions; 20 awaiting resolution.
- 95 correct and 54 incorrect: **63.76% directional accuracy**.
- Wilson 95% interval: **55.78% to 71.04%**.
- Mean model confidence: **54.09%**; Brier score: **0.2331**.
- Flat $1 at the offered price on the model winner: **-$4.33, -2.91% ROI**.
- Bootstrap ROI interval: **-15.67% to +9.86%**; profitability is not established.
- R3 GBT: Brier 0.2331, accuracy 63.76%.
- R4 market anchor: Brier 0.2108, accuracy 64.43%.
- Hard Rock no-vig favorite direction: accuracy 64.43%, realized ROI -8.12% at the offered price.
- R4 market-disagreement portfolio: 2-18 and -52.5%; disagreement with the market is quarantined.
- The prior balanced challenger had only 12 decisions, 6-6, and +0.87%; it is far below promotion scale.

The recent R3 sequence also improved but remains noisy:

| Run | Trusted resolutions | Accuracy | Brier |
|---|---:|---:|---:|
| 75 | 119 | 60.50% | 0.2401 |
| 76 | 287 | 58.89% | 0.2395 |
| 93 | 149 | 63.76% | 0.2331 |

These samples support a new experiment, not an automatic claim that a feature or threshold is proven.

## Critical logic finding

The model-call ledger grades the model's most-likely winner. The old paper trader instead followed `suggestedSide`, which is the side with the best calculated value and can be the opposite player. The old policy then demanded a model probability of at least 60% while frequently evaluating that longshot, and it prohibited plus-money prices. That combination was internally contradictory and explains the zero-paper-bet run.

The apparent +11.91% flat-winner ROI among rows rejected as `FAIR_ODDS_TOO_LONG` did **not** establish that the rejected longshots were profitable. It graded the model winner, which was often the favorite on the other side. R5 therefore does not loosen that conclusion into a longshot betting rule.

## What appears stable and what remains weak

Repeated attribution families across runs 75, 76, and 93 support retaining these as primary evidence:

- Rater ensemble agreement.
- Glicko win probability and rating level.
- TrueSkill probability and rating level.
- Elo probability and rating level.
- Nonlinear Elo/Glicko interaction terms.

These remain shrinkage targets rather than trusted standalone signals:

- Schedule strength.
- Opponent-adjusted recent form.
- Head-to-head history.
- Volatility.
- Glicko rating deviation.

H2H and recent form are still displayed for context, but R5 does not allow a small or unstable cohort to dominate a probability.

## R5 probability construction

R5 is a factor-aware market calibrator:

1. Remove the two-sided sportsbook overround to get a no-vig market probability.
2. Convert that probability to log-odds.
3. Compute the Champion model's residual from the market in log-odds space.
4. Shrink the residual by a base model weight of 0.40.
5. Apply an additional disagreement scale of 0.50.
6. Modulate the remaining residual with signal quality and rating-family agreement.
7. Apply a 0.70 multiplier when the leading factor belongs to a weak/unstable family.
8. Convert the calibrated log-odds back to probability.

The selected configuration was fitted on run 76 and checked on untouched run 93:

| Lane | Run 76 Brier | Run 93 Brier | Run 93 accuracy |
|---|---:|---:|---:|
| R3 Champion | 0.2395 | 0.2331 | 63.76% |
| R4 market anchor | — | 0.210761 | 64.43% |
| R5 factor-aware anchor | 0.235893 | 0.210691 | 64.43% |
| Market alone | 0.237690 | 0.209659 | 64.43% |

R5 slightly improves on R4 in the recent holdout, but it does **not** beat the market-alone Brier score. It is therefore a shadow challenger for the week and must earn promotion.

## Fresh training job

The application completed a new, temporally split training job before this experiment was started:

- Job: `train-20260819000427`.
- 7,200 antisymmetric training/evaluation samples over the 30-date bounded window.
- Training dates: 2026-07-14 through 2026-07-31.
- Untouched validation dates: 2026-08-13 through 2026-08-18.
- Selected family: GBT-like nonlinear expansion.
- Exact artifact: `factor-aware-market-r5-20260819000429-GBT_LIKE-29208`.
- Validation accuracy: 62.97%; log loss: 0.6486; Brier: 0.2284.
- Calibration: temperature grid 1.25; regularization lambda 0.30; five grouped folds.
- High-confidence validation accuracy: 77.36% across 104 symmetric orientations.
- Coin-flip-band accuracy: 52.78% and Brier 0.2493 across 692 orientations.

The release gate correctly returned `PROMOTION_FAILED / FAILED_TEMPORAL_STABILITY`. The artifact is available for frozen shadow inspection, but the operational Champion remains the previously pinned R3 artifact. R5's factor-aware market calibrator is the named forward challenger. This preserves release integrity while still testing the new probability construction on every covered opportunity.

## Operational paper sampler

The operational sampler is explicitly named `MODEL_MARKET_AGREEMENT_ACCURACY_AUDIT`.

- It follows the model's most-likely winner.
- The model and no-vig Hard Rock direction must agree.
- Minimum model probability is 52%.
- Plus-money selections remain quarantined.
- Minimum signal quality is 62%.
- Minimum rating agreement is 50%.
- Maximum raw and no-vig model/market gaps are 25 percentage points.
- A positive model-over-market no-vig gap above 3 points stays research-only.
- Fixed-$1 sampling is capped separately from economic-value portfolios.
- Duplicate event, exposure, bankroll, settlement, and open-position limits remain active.

The sampler may bypass only the staking-policy rejections `EDGE_BELOW_THRESHOLD`, `NEGATIVE_KELLY`, and `KELLY_CAP`, because its declared purpose is fixed-$1 forecast measurement rather than value staking. The override is accepted only when every staking rejection belongs to that allowlist. It does not bypass max exposure, drawdown, duplicate-event, market-quality, bankroll, settlement, or open-position controls.

### Live start receipt

Run 99 served as the final live preflight. It proved the repaired sampler and settlement path, but also exposed that a settled event could be sampled again after a line update when the value engine's displayed side changed. The release now builds the dedupe key from the player actually selected and enforces one event per run across every bet status. Run 99 was closed and preserved as preflight evidence.

The final IntelliJ one-click launch created Run 101 with label `WEEK-01 FINAL · R5 Factor-Aware · $1 Accuracy Audit + EV 0/1/2/3/5 · 2026-08-19` and a fresh $1,000 bankroll while preserving prior run history. Its first explicit synchronization scanned 18 markets and created one unique fixed-$1 audit position. The preflight plus final start proved the repaired path end to end:

- fixed-$1 audit positions were created instead of zero;
- preflight positions were automatically settled from decision-grade targeted score evidence;
- the final run started with a unique open event and no inherited positions;
- all five after-vig R5 EV ladders and the separate market-agreement accuracy portfolio were present in the run foundation;
- the operational Champion stayed pinned to the approved R3 artifact, while R5 remained a shadow challenger.

This receipt proves that the sampler, score tracking, and settlement path function. It is not evidence that the strategy is profitable; the seven-day checkpoints below remain binding.

## Economic portfolios after the Hard Rock margin

Every candidate is evaluated against the **actual offered decimal price**, not no-vig fair odds:

`expected return = model probability * offered decimal odds - 1`

R5 records independent $1 shadow portfolios at:

- expected return at least 0%;
- expected return at least 1%;
- expected return at least 2%;
- expected return at least 3%;
- expected return at least 5%.

This ladder answers which margin survives out of sample. It also prevents threshold shopping during the week because all thresholds are frozen and observed in parallel.

## Seven-day checkpoints

The admin UI exposes the following pre-registered checkpoints:

| Checkpoint | Minimum target | Why it exists |
|---|---:|---|
| Frozen calls | 500 | basic volume and coverage |
| Trusted resolved outcomes | 300 | first directional and calibration review |
| Operational paper samples | 30 | verifies the sampler actually functions |
| Trusted settlement coverage | 95% | guards against label-selection bias |
| Two-sided price coverage | 95% | ensures no-vig and expected-return math are valid |

No automatic factor reweighting or promotion occurs before the checkpoint review. At review time, report Brier score, log loss, calibration error, directional accuracy with interval, ROI with bootstrap interval, closing-line value coverage, settlement exclusions, and each factor/trigger cohort with sample size.

## Promotion contract

R5 can be considered for promotion only if all of the following hold on frozen out-of-sample decisions:

- no material Brier or log-loss regression against both R3 and market-only baselines;
- directional accuracy is at least non-inferior with uncertainty reported;
- sufficient trusted settlements and price coverage meet the checkpoints above;
- no severe loss is concentrated in a trigger, phase, odds band, or disagreement cohort;
- at least one after-vig economic portfolio has a credible positive ROI interval or repeatable CLV evidence;
- excluded, manual, inferred, and non-binary labels remain separately reported.

## Research basis

- Calibration, not raw accuracy alone, is especially important when converting probabilities into sports-betting decisions: [Machine learning for sports betting: Should model selection be based on accuracy or calibration?](https://www.sciencedirect.com/science/article/pii/S266682702400015X)
- Calibration must be evaluated on data disjoint from model fitting to avoid biased probabilities: [scikit-learn probability calibration documentation](https://scikit-learn.org/stable/modules/calibration.html)
- Sportsbook overround and favorite-longshot bias make raw offered implied probabilities unsuitable as fair probabilities: [Favourite-longshot bias and market efficiency](https://academic.oup.com/oep/article/78/1/90/8244336)
