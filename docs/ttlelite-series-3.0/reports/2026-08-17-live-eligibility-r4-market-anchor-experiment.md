# Live eligibility correction and R4 market-anchor experiment

Date: 2026-08-17
Status: implemented for forward shadow evaluation
Scope: completed runs 71, 72, and 76; current paper-trading eligibility; R4 experiment contract

## Executive decision

R3 remains the Champion. It has not demonstrated positive expected value and must not be described as profitable. The next materially different experiment is `market-anchor-residual-r4-20260817`, a shadow-only probability lane that starts from the timestamp-correct Hard Rock no-vig probability and permits R3 to contribute a small, disagreement-sensitive residual in log-odds space.

The production paper trader now treats a positively identified live event as timing-eligible. `onlyUpcoming=true` still rejects stale prematch rows and finished phases; it no longer incorrectly maps every live row to `EVENT_NOT_UPCOMING`. Live rows then pass through the real live gates: live enablement, edge, confidence, price, model-accuracy, exposure, and duplicate-event protection.

## What the evidence says

### Strongest completed R3 run: session 76

| Measure | Result | Interpretation |
|---|---:|---|
| Calls | 303 | Broad monitoring coverage |
| Trusted resolved calls | 287 | Sufficient for directional comparison, not permanent promotion |
| R3 winner accuracy | 58.89% (169-118) | Above chance, below the sportsbook favorite benchmark |
| R3 95% accuracy interval | 53.11%-64.42% | Material uncertainty remains |
| Brier score | 0.2395 | Calibrated enough to study, not strong enough to claim advantage |
| Flat-$1 R3 P&L | -$29.32 | Hypothetical only |
| Flat-$1 R3 ROI | -10.22% | 95% interval: -19.52% to -0.91% |
| Positive-ROI confidence | 1.57% | Evidence opposes profitability |
| Hard Rock favorite accuracy | 62.37% (179-108) | Market direction beat R3 |
| Hard Rock favorite flat-$1 ROI | -9.55% | Directional accuracy still did not overcome the book margin |

The central finding is not merely that R3 lost. Hard Rock's no-vig direction was better, and the most aggressive positive model-market gaps were not the best segment. Therefore, treating a larger disagreement as a larger edge is not supported by the current data.

### Prior-run progression

| Run | Model generation | Trusted directional result | Brier | Flat-$1 all-call ROI | Official paper picks |
|---|---|---:|---:|---:|---:|
| 71 | legacy ensemble | 60.81% | 0.2318 | about -7.0% | 47; resolved record 16-26, -18.38% ROI |
| 72 | Accuracy Guardrails R1 | 54.77% | 0.2478 | -13.09% | 6; record 2-4, -41.99% ROI |
| 76 | symmetric market-aware R3 GBT | 58.89% | 0.2395 | -10.22% | 0 |

These runs are not directly interchangeable experiments: model identities, sampling, and gates changed. They do show that strict paper-pick scarcity hid useful all-call learning, while looser policies exposed negative economics. The solution is not to erase gates. It is to evaluate every frozen winner call and multiple shadow portfolios while keeping the official paper lane disciplined.

## Trigger findings

Run 76 trigger slices were informative but not ready for automatic weight changes:

| Top trigger | Trusted n | Accuracy | Flat-$1 ROI | Decision |
|---|---:|---:|---:|---|
| Schedule Strength | 91 | 54.95% | -17.01% | Negative evidence; do not promote |
| Rater Ensemble | 83 | 63.86% | -8.80% | Direction useful, economics still negative |
| Head-to-Head (decayed) | 32 | 53.13% | -16.15% | Weak/thin |
| TrueSkill2 | 23 | 60.87% | +18.26% | Interesting but too thin and multiple-testing exposed |
| Glicko probability | 16 | 75.00% | +4.56% | Very thin; reliability was poor |
| Glicko RD | 15 | 40.00% | -21.45% | Negative evidence |

Top-trigger attribution is not causal attribution. Features are correlated, and selecting the best-looking slice after the fact creates winner's-curse bias. R4 therefore does not reweight these triggers in production. They remain monitored hypotheses with effective-sample and stability gates.

## R4 formulation

For player 1:

```text
gap = abs(p_champion - p_market_no_vig)
effective_model_weight = 0.21 * exp(-gap / 0.50)
logit(p_r4) = logit(p_market_no_vig)
              + effective_model_weight
              * (logit(p_champion) - logit(p_market_no_vig))
```

Player 2 is exactly `1 - p_r4`. This preserves player-order symmetry. Inputs and outputs are clamped only for numerical stability. R4 records the anchor, Champion probability, absolute disagreement, effective weight, and resulting probability on every evaluation.

The 0.21 maximum residual weight and 0.50 disagreement scale came from a grid search using 400 recent trusted/priced calls from runs 71 and 72, optimizing log loss. The last 200 trusted/priced calls from run 76 were held out:

| Holdout calculation | Accuracy | Brier | Log loss |
|---|---:|---:|---:|
| Hard Rock no-vig direction | 59.5% | 0.2383 | 0.6694 |
| R4 market-anchored residual | 59.0% | 0.2366 | 0.6654 |

The holdout improvement is small and limited to proper probability scores; accuracy was slightly lower. This is exactly why R4 is a challenger, not a promotion.

## Portfolio and transaction-cost logic

The R4 lane is evaluated on every opportunity with valid Champion and two-sided market probabilities. Its counterfactual `MARKET_ANCHORED_R4` portfolio records a one-dollar action only when both conditions pass:

1. anchored probability exceeds the sportsbook no-vig probability by at least 0.2 percentage points; and
2. expected value calculated from the actual offered American price is at least +0.5% after the book margin.

This separates probability disagreement from a bettable price. A no-vig edge alone is not enough. The R4 portfolio is shadow-only and cannot create a real or official paper bet.

## Forward one-week contract

Do not promote R4 until all of the following are true:

1. At least 300 trusted forward outcomes exist for R3, R4, and the Hard Rock benchmark on synchronized opportunities.
2. R4 improves log loss and Brier score without a material accuracy regression.
3. Calibration remains stable across probability bins, live/prematch capture, favorite/underdog, time-of-day, and player-frequency slices.
4. Any economic improvement survives the actual offered price and has a confidence interval that is not driven by one player, trigger, or short regime.
5. Artifact identity, source time, market quote age, settlement evidence, and event identity remain complete.
6. Swap-invariance and paired-player symmetry tests continue to pass.

## Operational corrections and safeguards

- Live events are timing-eligible; finished phases and stale prematch rows are not.
- Live still has its own explicit enablement and risk gates.
- A live row cannot open a second position for the same player pair while one is already open, even if the feed changes the event key during promotion.
- Distinct future rematches for the same players remain valid.
- Every call, including non-paper calls, remains available for directional accuracy, Brier, calibration, benchmark, trigger, and factor analysis after trusted settlement.
- All graph surfaces now state the measure, axes, units, sample context, and simulation status rather than relying on unlabeled decorative lines.

## Known limitations

- The R4 tuning audit used the 200 most recent exposed results per run because that is the bounded historical scorecard contract. It is a disciplined 400/200 split, not a full raw-dataset retraining study.
- Runs 71 and 72 were generated by older model versions. Their use here is appropriate for a conservative market-residual prior, but it does not establish stationarity.
- A sportsbook benchmark can be accurate and still lose at offered prices because of the hold. R4 must be evaluated separately for probability quality and executable economics.
- A one-week calendar interval is not itself enough; the 300-outcome and integrity requirements control readiness.

## Next decision after the forward run

Compare Champion R3, R4, Logistic Shadow, Ensemble Shadow, and Hard Rock on the same synchronized opportunity set. If R4 does not improve proper scoring out of sample, retire it. If it does improve probability quality but still produces no after-vig opportunities, retain it as a calibrated forecasting benchmark rather than a betting policy.
