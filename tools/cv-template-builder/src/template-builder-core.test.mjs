import assert from "node:assert/strict";
import {
  buildTemplate,
  defaultDigitFields,
  formatTemplate,
  normalizeRect,
  smokeTestTemplate,
  validateTemplate,
} from "./template-builder-core.mjs";

const roi = normalizeRect({ x: 36, y: 28 }, { x: 320, y: 132 }, 1280, 720);
assert.deepEqual(roi, { x: 36, y: 28, w: 284, h: 104 });

const fields = defaultDigitFields(roi);
assert.equal(fields.length, 4);
assert.deepEqual(fields.map((field) => field.name), ["topGames", "topPoints", "botGames", "botPoints"]);

const template = buildTemplate({
  templateId: "ttcup.table1.v3",
  frameWidth: 1280,
  frameHeight: 720,
  roi,
  colorProfile: "bright-on-dark",
  digitFields: fields,
});
const validation = validateTemplate(template);
assert.equal(validation.ok, true, validation.errors.join(", "));
assert.match(formatTemplate(template), /"templateId": "ttcup\.table1\.v3"/);

const clip = {
  fixtureId: "synthetic",
  templateId: "ttcup.table1.v3",
  frame: { width: 1280, height: 720 },
  frames: [
    { sequence: 1, score: { topGames: 0, botGames: 0, topPoints: 1, botPoints: 0 }, confidence: 0.91 },
    { sequence: 2, score: { topGames: 0, botGames: 0, topPoints: 1, botPoints: 1 }, confidence: 0.90 },
  ],
  expectedEmissions: [],
};
const smoke = smokeTestTemplate(template, clip, { limit: 200 });
assert.equal(smoke.ok, true, smoke.errors.join(", "));
assert.equal(smoke.sampledFrames, 2);

const badSmoke = smokeTestTemplate(
  { ...template, digitFields: template.digitFields.slice(0, 3) },
  clip,
);
assert.equal(badSmoke.ok, false);
assert.ok(badSmoke.errors.some((error) => error.includes("botPoints")));

console.log("cv-template-builder core tests passed");
