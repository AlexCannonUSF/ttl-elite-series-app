# Accuracy Consensus R2

## R1 evidence snapshot

This release is based on session 72 (`Accuracy Guardrails R1 - Fresh Run 2026-08-09`). The run remains in model history; R2 does not delete or rewrite it.

- 249 distinct model calls were recorded; 234 had trusted settled outcomes and 15 were awaiting a result at analysis time.
- Winner-call accuracy was 127/234 (54.27%, 95% interval 47.87%-60.54%). Average stated confidence was 56.96% and Brier score was 0.2479, showing mild overconfidence.
- A hypothetical flat $1 bet on every settled call lost $32.02 (-13.68% ROI, 95% interval -24.44% to -2.93%). Accuracy and bet value therefore must remain separate decisions.
- The paper trader placed six fixed-$1 bets. It finished 2-4 for -$2.52 (-42% ROI). Six bets are not enough to estimate a profitable threshold, so R2 does not fit its staking policy to that ROI.
- Average closing-line movement was -0.066 percentage points with complete coverage: approximately flat, not evidence of a repeatable edge.

## What the run taught us

### Stronger signals

- Glicko Rating Delta: 10/12 calls correct (83.3%) and +13.4% flat-$1 ROI. This is promising but still a small sample.
- Rater Consensus: 11/16 correct (68.8%), but -3.5% ROI. Useful for winner confidence, not yet proven as a price edge.
- High cross-rating agreement improved accuracy. Calls with model probability at least 60% and rating agreement at least 65% went 17/23 (73.9%).
- Strong market confirmation was the clearest accuracy result. When the selected model side was more than 10 percentage points below the Hard Rock no-vig probability, it won 33/41 (80.5%).

### Weaker signals

- Player 1 Recent Form went 6/21 (28.6%) with -55.6% flat-$1 ROI. It is a warning against using a recent-form lead without rating and market confirmation.
- Positive model/market disagreements were poor: the 6-8 point band went 2/7, the 8-10 point band 5/11, and the greater-than-10 point band 13/36.
- Plus-money calls were materially weaker than favorites. The existing no-plus-money paper guard remains appropriate.
- Signal quality was not monotonic in this run, so R2 does not blindly raise that threshold.

## R2 changes

- Probability temperature increases from 1.25 to 1.55 to reduce observed overconfidence. An offline grid search minimized run Brier score near 1.59; 1.55 is the conservative rounded setting.
- Uncertainty shrink increases from 0.55 to 0.62 and consensus shrink from 0.35 to 0.45.
- Training history expands from 24 to 30 dates. A 45-date shadow trial made feature construction impractically slow on the current embedded H2 store, so R2 takes a measured first step while preserving a responsive admin retrain path.
- Regularization candidates expand above the old 0.01 ceiling to `0.003, 0.01, 0.03, 0.1, 0.3`. R1 selected the old maximum and still failed temporal/bootstrap stability, which is direct evidence that the search range was too narrow.
- The paper lane now separately compares model probability with the two-sided Hard Rock no-vig probability. This fixes the old guard's use of the vig-inflated offered break-even probability as if it represented market consensus.
- Paper bets require at least 65% cross-rating agreement when that measurement is available.
- Exploration edge and expected-ROI floors increase from 1.5% to 2.0%. All non-bet calls continue to be recorded and settled for model learning.

## Promotion policy

R2 is accuracy-first and remains subject to the existing temporal, bootstrap, calibration, and market-benchmark promotion gates. A newly trained artifact must not be auto-promoted merely because its in-sample or single holdout score improves. The next review should separate:

1. all-call winner accuracy and calibration (target: at least 100 trusted outcomes),
2. rating-agreement and no-vig gap strata,
3. paper-bet ROI and CLV (target: materially more than six bets), and
4. pre-match versus live-first performance.
