# Analytics Package

This package contains smaller algorithmic building blocks used by services.

## Files

- `EloRatingSystem`
  - Elo math helper
- `Glicko2`
  - Glicko update logic
- `MatchPredictor`
  - supporting prediction math
- `RecommendationEngine`
  - recommendation helper logic

## Rule

Keep orchestration out of this package. Cross-cutting workflow ownership belongs in `service`.
