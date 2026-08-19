#!/usr/bin/env node
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { smokeTestTemplate } from "./src/template-builder-core.mjs";

const args = parseArgs(process.argv.slice(2));

if (args.help || !args.template || !args.clip) {
  printUsage();
  process.exit(args.help ? 0 : 2);
}

const templatePath = resolve(args.template);
const clipPath = resolve(args.clip);
const template = await readJson(templatePath);
const clip = await readJson(clipPath);
const report = smokeTestTemplate(template, clip, {
  limit: args.limit ?? 200,
  minConfidence: args.minConfidence ?? 0.75,
});

const lines = [
  `cv-template-builder smoke ${report.ok ? "PASS" : "FAIL"}`,
  `template: ${report.templateId || templatePath}`,
  `clip: ${report.fixtureId || clipPath}`,
  `frames: ${report.sampledFrames}/${report.totalFrames}`,
  `expectedEmissions: ${report.expectedEmissions}`,
];
for (const warning of report.warnings) {
  lines.push(`warning: ${warning}`);
}
for (const error of report.errors) {
  lines.push(`error: ${error}`);
}

console.log(lines.join("\n"));

if (args.out) {
  await writeFile(resolve(args.out), `${JSON.stringify(report, null, 2)}\n`, "utf8");
}

process.exit(report.ok ? 0 : 1);

async function readJson(path) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    console.error(`Unable to read JSON ${path}: ${error.message}`);
    process.exit(2);
  }
}

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--help" || arg === "-h") {
      parsed.help = true;
      continue;
    }
    if (arg.startsWith("--")) {
      const key = arg.slice(2);
      const value = argv[i + 1];
      if (!value || value.startsWith("--")) {
        parsed[key] = true;
      } else {
        parsed[key] = value;
        i += 1;
      }
    }
  }
  return parsed;
}

function printUsage() {
  console.log(`Usage:
  node tools/cv-template-builder/smoke-test.mjs --template cv-assets/roi/wstt.generic.v1/roi.json --clip cv-assets/fixtures/wstt.generic.v1-short-rally/clip.json [--limit 200] [--out report.json]

Options:
  --template        Path to roi.json
  --clip            Path to clip.json fixture or captured clip manifest
  --limit           Maximum sampled frames, default 200
  --minConfidence   Warning threshold for labelled frame confidence, default 0.75
  --out             Optional JSON report path
`);
}
