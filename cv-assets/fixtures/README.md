# Stream-CV Replay Fixtures

These fixtures are deterministic clip manifests for the Phase 02 Stream-CV replay harness. They intentionally store labelled frame tuples instead of binary video so the CI test can exercise the Java pipeline without large media files or external OCR dependencies.

Each `clip.json` contains:

- `templateId` pointing at `cv-assets/roi/<templateId>/roi.json`
- frame dimensions and a sampled frame sequence
- per-frame labelled OCR tuples
- expected consensus-backed `stream.frame` emissions

Run all fixtures with:

```bash
./scripts/cv-replay.sh all
```
