# Edge engine (Phase 05 item 7)

`EdgeEngine.compute(pModelTop, deviggedMarket, dq, uncertaintyLabel)`
implements Prediction Engine Spec §9.2:

```
rawEdgeTop = pModelTop - pConsensusTop   // from DeviggingService
shrink     = 1.0
if dq.raterDisagreement || dq.featureCompleteness < 0.8 → shrink *= 0.7
if uncertainty in {AMBIGUOUS, ANOMALOUS}                → shrink *= 0.5
edgeTop = rawEdgeTop * shrink
```

The result is an `Edge` record carrying both raw and shrunken edges, the
final factor, the list of shrinkers that fired (for audit), and the
uncertainty label. The output of this engine is what `StakingPolicy`
(Phase 06) consumes when deciding bet size and threshold gating.

## Shrinker matrix

| dq.rater_disagreement | dq.feature_completeness | uncertainty | shrink |
| --- | --- | --- | --- |
| `false` | `≥ 0.80` | CONFIDENT / unknown | `1.00` |
| `true` or | `< 0.80` | CONFIDENT / unknown | `0.70` |
| `false` | `≥ 0.80` | `AMBIGUOUS` / `ANOMALOUS` | `0.50` |
| `true` or | `< 0.80` | `AMBIGUOUS` / `ANOMALOUS` | `0.35` |

## Wiring

`EdgeEngine` is a stateless `@Service`. Inject alongside
`DeviggingService` (Phase 05 item 6). When the `Prediction` shape from
§10 lands, the facade will call:

```java
DeviggedMarket market = devig.devig(oddsTop, oddsBot);
Edge edge = edgeEngine.compute(p_top, market, dq, uncertainty.label());
```
