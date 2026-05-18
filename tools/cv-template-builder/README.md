# CV Template Builder

Static operator tool for creating Stream-CV ROI templates.

Open it with:

```bash
./scripts/cv-template-new.sh new.template.v1
```

The opener serves this directory on `127.0.0.1:${CV_TEMPLATE_BUILDER_PORT:-8765}` so the browser can load ES modules without adding a frontend build step.

Workflow:

1. Load a still frame.
2. Drag the scoreboard ROI.
3. Use `Auto Fields`, then adjust `TG`, `TP`, `BG`, and `BP` rectangles if needed.
4. Save the generated `roi.json` under `cv-assets/roi/<templateId>/roi.json`.
5. Smoke-test against a captured clip manifest:

```bash
node tools/cv-template-builder/smoke-test.mjs \
  --template cv-assets/roi/wstt.generic.v1/roi.json \
  --clip cv-assets/fixtures/wstt.generic.v1-short-rally/clip.json \
  --limit 200
```

The generated schema matches `RoiTemplateCatalog`: `templateId`, `frameWidth`, `frameHeight`, `roi`, `colorProfile`, and four required `digitFields`.
