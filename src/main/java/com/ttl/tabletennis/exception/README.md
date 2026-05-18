# Exception Package

This package handles API-facing error translation.

## Files

- `GlobalExceptionHandler`
  - turns exceptions into structured API responses
- `ResourceNotFoundException`
  - explicit not-found signal

## Rule

Keep business rules out of exception handlers. They should translate failures, not decide behavior.
