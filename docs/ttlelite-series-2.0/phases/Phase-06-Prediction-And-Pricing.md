# Phase 06: Prediction And Pricing

## Status

Planned

## Why This Phase Exists

The current modeling stack is already useful. This phase makes it more disciplined, better calibrated, and more aware of sample size and live-state context.

## Current State

Foundation already landed:

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeatureService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`

Existing strengths:

- stabilized recent-form/H2H/opponent-adjusted features already exist
- multiple model families already exist
- calibration/live-learning ideas already exist

Needs hardening:

- stronger significance-aware shrinkage
- clearer prematch vs live regime separation
- more time-aware validation
- better calibration by score/price state

## Scope

- sample-size-aware shrinkage
- prematch vs live model separation
- stronger live-state features
- time-aware evaluation and calibration reporting

## Dependencies

- Phases 00 and 01 data integrity
- telemetry support from Phase 08 for long-term improvement

## Primary Files

- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeatureService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`
- `/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`

## Concrete Work

- [ ] Shrink low-sample H2H/form signals harder toward priors.
- [ ] Separate prematch and live behavior explicitly in the prediction layer.
- [ ] Add live-state features for set differential, point differential, and pressure state.
- [ ] Add source confidence or data freshness penalties where appropriate.
- [ ] Add walk-forward or chronological validation.
- [ ] Add calibration buckets by live stage and odds regime.

## Validation

- low-sample signals materially affect outcomes less than high-sample signals
- live-state pricing behaves differently from prematch pricing in a controlled way

## Done Definition

This phase is done when model outputs are more disciplined, more state-aware, and easier to defend quantitatively.
