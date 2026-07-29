# Devigging (Phase 05 item 6)

Pure-Java devigger for 2-way table-tennis markets per Prediction Engine
Spec §9.1.

`DeviggingService.devig(decimalOddsTop, decimalOddsBot)` returns a
`DeviggedMarket` record with three independent fair-probability estimates
plus the per-side median consensus:

| Method | What it does | Best when |
| --- | --- | --- |
| `multiplicative` | `π_i / Σπ_j` | overround is symmetric — cheap baseline |
| `power` | `π_i^k` with `k` solved so `Σπ_i^k = 1` | overround is symmetric but bookmaker rounds |
| `shin` | Shin (1992) insider-trader correction | overround leans toward one side |

The consensus is the per-side median of the three pTop estimates. We
store all three plus `shinZ` and `powerK` on the record so audit
queries can compare estimators after the fact.

## Solver notes

- **Power** bisects `k ∈ [1e-9, 50]` on a monotone function — converges
  in ~40 iterations to 1e-9 tolerance.
- **Shin** bisects `z ∈ [0, 1)` looking for the descending crossing of
  `Σ√(z² + 4(1-z)π_i²/B) = 2`. The function is not globally monotone
  (it dips below 2 in the middle of the interval and comes back to 2 at
  `z=1`), but the first crossing on the descending portion is the
  legitimate root and bisection from the left edge always lands there.

## Wiring

`DeviggingService` is a Spring `@Service` with no state; safe to inject
into request paths. It is wired into the future `EdgeEngine` (Phase 05
item 7, checklist line 99) which combines `Prediction.pTop` with
`p_fair_top` to produce an `Edge`.
