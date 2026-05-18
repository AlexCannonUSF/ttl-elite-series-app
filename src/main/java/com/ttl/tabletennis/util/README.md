# Util Package

This package contains reusable helpers that do not own workflows.

## Files

- `CorrelationContext`
  - thread-scoped correlation-id helper used by request filters, schedulers, and `@PrePersist` hooks
- `ChartUtils`
  - chart generation helpers
- `MatchResultParser`
  - result-string parsing
- `NameUtils`
  - normalization and name utilities

If a helper begins to know too much about product behavior, move it into the owning service instead.
