export const REQUIRED_FIELDS = ["topGames", "topPoints", "botGames", "botPoints"];

export function cleanTemplateId(value) {
  const cleaned = String(value || "").trim();
  return cleaned || "new.template.v1";
}

export function clampInteger(value, min, max) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    return min;
  }
  return Math.min(max, Math.max(min, parsed));
}

export function normalizeRect(start, end, width, height) {
  const maxX = Math.max(0, width - 1);
  const maxY = Math.max(0, height - 1);
  const x1 = clampInteger(Math.round(start.x), 0, maxX);
  const y1 = clampInteger(Math.round(start.y), 0, maxY);
  const x2 = clampInteger(Math.round(end.x), 0, maxX);
  const y2 = clampInteger(Math.round(end.y), 0, maxY);
  const x = Math.min(x1, x2);
  const y = Math.min(y1, y2);
  return {
    x,
    y,
    w: Math.max(1, Math.abs(x2 - x1)),
    h: Math.max(1, Math.abs(y2 - y1)),
  };
}

export function rectFits(rect, width, height) {
  return Boolean(
    rect
      && Number.isFinite(rect.x)
      && Number.isFinite(rect.y)
      && Number.isFinite(rect.w)
      && Number.isFinite(rect.h)
      && rect.x >= 0
      && rect.y >= 0
      && rect.w > 0
      && rect.h > 0
      && rect.x + rect.w <= width
      && rect.y + rect.h <= height,
  );
}

export function defaultDigitFields(roi) {
  const w = Math.max(4, roi?.w || 260);
  const h = Math.max(4, roi?.h || 96);
  const gap = Math.max(2, Math.round(w * 0.015));
  const gameWidth = Math.max(8, Math.round(w * 0.17));
  const pointsWidth = Math.max(12, Math.round(w * 0.31));
  const firstPointsX = gameWidth + gap;
  const botGamesX = firstPointsX + pointsWidth + Math.max(gap, Math.round(w * 0.09));
  const botPointsX = botGamesX + gameWidth + gap;
  return [
    { name: "topGames", rel: [0, 0, gameWidth, h] },
    { name: "topPoints", rel: [firstPointsX, 0, pointsWidth, h] },
    { name: "botGames", rel: [botGamesX, 0, gameWidth, h] },
    { name: "botPoints", rel: [botPointsX, 0, Math.max(8, w - botPointsX), h] },
  ].map((field) => ({
    name: field.name,
    rel: clampRelativeRect(field.rel, w, h),
  }));
}

export function clampRelativeRect(rel, width, height) {
  const x = clampInteger(rel?.[0] ?? 0, 0, Math.max(0, width - 1));
  const y = clampInteger(rel?.[1] ?? 0, 0, Math.max(0, height - 1));
  const w = clampInteger(rel?.[2] ?? 1, 1, Math.max(1, width - x));
  const h = clampInteger(rel?.[3] ?? 1, 1, Math.max(1, height - y));
  return [x, y, w, h];
}

export function buildTemplate({ templateId, frameWidth, frameHeight, roi, colorProfile, digitFields }) {
  const effectiveRoi = roi || { x: 0, y: 0, w: frameWidth, h: frameHeight };
  const fields = (digitFields && digitFields.length > 0 ? digitFields : defaultDigitFields(effectiveRoi))
    .map((field) => ({
      name: String(field.name || "").trim(),
      rel: clampRelativeRect(field.rel, effectiveRoi.w, effectiveRoi.h),
    }));
  return {
    templateId: cleanTemplateId(templateId),
    frameWidth: clampInteger(frameWidth, 1, 100000),
    frameHeight: clampInteger(frameHeight, 1, 100000),
    roi: {
      x: clampInteger(effectiveRoi.x, 0, Math.max(0, frameWidth - 1)),
      y: clampInteger(effectiveRoi.y, 0, Math.max(0, frameHeight - 1)),
      w: clampInteger(effectiveRoi.w, 1, Math.max(1, frameWidth - effectiveRoi.x)),
      h: clampInteger(effectiveRoi.h, 1, Math.max(1, frameHeight - effectiveRoi.y)),
    },
    colorProfile: colorProfile === "dark-on-bright" ? "dark-on-bright" : "bright-on-dark",
    digitFields: fields,
  };
}

export function validateTemplate(template) {
  const errors = [];
  const warnings = [];
  if (!template || typeof template !== "object") {
    return { ok: false, errors: ["template must be an object"], warnings };
  }
  if (!String(template.templateId || "").trim()) {
    errors.push("templateId must not be blank");
  }
  if (!positiveInteger(template.frameWidth) || !positiveInteger(template.frameHeight)) {
    errors.push("frameWidth and frameHeight must be positive integers");
  }
  if (positiveInteger(template.frameWidth) && positiveInteger(template.frameHeight)
      && !rectFits(template.roi, template.frameWidth, template.frameHeight)) {
    errors.push("roi must fit inside frameWidth/frameHeight");
  }
  const fields = Array.isArray(template.digitFields) ? template.digitFields : [];
  const names = new Set(fields.map((field) => String(field.name || "").trim()));
  for (const required of REQUIRED_FIELDS) {
    if (!names.has(required)) {
      errors.push(`missing required digit field ${required}`);
    }
  }
  if (fields.length > REQUIRED_FIELDS.length) {
    warnings.push("extra digit fields will be ignored by ScoreboardTextReader");
  }
  if (template.roi) {
    for (const field of fields) {
      const rel = Array.isArray(field.rel) ? field.rel : [];
      const rect = { x: rel[0], y: rel[1], w: rel[2], h: rel[3] };
      if (!rectFits(rect, template.roi.w, template.roi.h)) {
        errors.push(`digit field ${field.name || "(blank)"} must fit inside roi`);
      }
    }
  }
  return { ok: errors.length === 0, errors, warnings };
}

export function smokeTestTemplate(template, clip, options = {}) {
  const validation = validateTemplate(template);
  const errors = [...validation.errors];
  const warnings = [...validation.warnings];
  const limit = clampInteger(options.limit ?? 200, 1, 10000);
  const frames = Array.isArray(clip?.frames) ? clip.frames : [];
  const sampledFrames = frames.slice(0, limit);

  if (!clip || typeof clip !== "object") {
    errors.push("clip must be an object");
  }
  if (clip?.templateId && clip.templateId !== template.templateId) {
    warnings.push(`clip templateId ${clip.templateId} differs from ${template.templateId}`);
  }
  if (clip?.frame?.width && clip.frame.width !== template.frameWidth) {
    warnings.push(`clip frame width ${clip.frame.width} differs from template frameWidth ${template.frameWidth}`);
  }
  if (clip?.frame?.height && clip.frame.height !== template.frameHeight) {
    warnings.push(`clip frame height ${clip.frame.height} differs from template frameHeight ${template.frameHeight}`);
  }
  if (frames.length === 0) {
    errors.push("clip must contain at least one frame");
  }
  if (frames.length > limit) {
    warnings.push(`smoke test sampled first ${limit} of ${frames.length} frames`);
  }

  const missingScores = [];
  for (const frame of sampledFrames) {
    const score = frame?.score || {};
    for (const field of REQUIRED_FIELDS) {
      if (!Number.isFinite(Number(score[field]))) {
        missingScores.push(`${frame?.sequence ?? "?"}.${field}`);
      }
    }
  }
  if (missingScores.length > 0) {
    errors.push(`sampled frames missing labelled scores: ${missingScores.slice(0, 8).join(", ")}`);
  }

  const minConfidence = Number.isFinite(Number(options.minConfidence)) ? Number(options.minConfidence) : 0.75;
  const lowConfidence = sampledFrames.filter((frame) => Number(frame?.confidence ?? 1) < minConfidence);
  if (lowConfidence.length > 0) {
    warnings.push(`${lowConfidence.length} sampled frame(s) below confidence ${minConfidence}`);
  }

  return {
    ok: errors.length === 0,
    templateId: template?.templateId || "",
    fixtureId: clip?.fixtureId || "",
    sampledFrames: sampledFrames.length,
    totalFrames: frames.length,
    expectedEmissions: Array.isArray(clip?.expectedEmissions) ? clip.expectedEmissions.length : 0,
    errors,
    warnings,
  };
}

export function formatTemplate(template) {
  return `${JSON.stringify(template, null, 2)}\n`;
}

function positiveInteger(value) {
  return Number.isInteger(Number(value)) && Number(value) > 0;
}
