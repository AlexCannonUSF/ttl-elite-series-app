import {
  REQUIRED_FIELDS,
  buildTemplate,
  defaultDigitFields,
  formatTemplate,
  normalizeRect,
  smokeTestTemplate,
  validateTemplate,
} from "./template-builder-core.mjs";

const params = new URLSearchParams(window.location.search);
const DISPLAY_LABELS = new Map([
  ["topGames", "TG"],
  ["topPoints", "TP"],
  ["botGames", "BG"],
  ["botPoints", "BP"],
  ["roi", ""],
]);

const state = {
  templateId: params.get("templateId") || "new.template.v1",
  colorProfile: "bright-on-dark",
  frameWidth: 1280,
  frameHeight: 720,
  image: null,
  roi: { x: 48, y: 32, w: 260, h: 96 },
  digitFields: defaultDigitFields({ x: 48, y: 32, w: 260, h: 96 }),
  mode: "roi",
  dragging: null,
  json: "",
};

const canvas = document.querySelector("#frame-canvas");
const ctx = canvas.getContext("2d");
const fileInput = document.querySelector("#frame-file");
const clipInput = document.querySelector("#clip-file");
const templateIdInput = document.querySelector("#template-id");
const colorProfileInput = document.querySelector("#color-profile");
const widthInput = document.querySelector("#frame-width");
const heightInput = document.querySelector("#frame-height");
const jsonOutput = document.querySelector("#json-output");
const statusOutput = document.querySelector("#status-output");
const smokeOutput = document.querySelector("#smoke-output");

templateIdInput.value = state.templateId;
colorProfileInput.value = state.colorProfile;
widthInput.value = state.frameWidth;
heightInput.value = state.frameHeight;

fileInput.addEventListener("change", loadFrame);
clipInput.addEventListener("change", loadClip);
templateIdInput.addEventListener("input", () => {
  state.templateId = templateIdInput.value;
  sync();
});
colorProfileInput.addEventListener("change", () => {
  state.colorProfile = colorProfileInput.value;
  sync();
});
widthInput.addEventListener("input", () => resizeFrame(Number(widthInput.value), state.frameHeight));
heightInput.addEventListener("input", () => resizeFrame(state.frameWidth, Number(heightInput.value)));

document.querySelector("#auto-fields").addEventListener("click", () => {
  state.digitFields = defaultDigitFields(state.roi);
  sync();
});
document.querySelector("#copy-json").addEventListener("click", async () => {
  await navigator.clipboard.writeText(state.json);
  transientStatus("Copied JSON");
});
document.querySelector("#save-json").addEventListener("click", () => {
  const blob = new Blob([state.json], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "roi.json";
  link.click();
  URL.revokeObjectURL(url);
});
document.querySelectorAll("[data-mode]").forEach((button) => {
  button.addEventListener("click", () => {
    state.mode = button.dataset.mode;
    document.querySelectorAll("[data-mode]").forEach((target) => {
      target.classList.toggle("active", target.dataset.mode === state.mode);
    });
    draw();
  });
});

canvas.addEventListener("pointerdown", (event) => {
  const point = canvasPoint(event);
  state.dragging = { start: point, end: point };
  canvas.setPointerCapture(event.pointerId);
});
canvas.addEventListener("pointermove", (event) => {
  if (!state.dragging) {
    return;
  }
  state.dragging.end = canvasPoint(event);
  draw();
});
canvas.addEventListener("pointerup", (event) => {
  if (!state.dragging) {
    return;
  }
  state.dragging.end = canvasPoint(event);
  applyDrag();
  state.dragging = null;
  canvas.releasePointerCapture(event.pointerId);
  sync();
});

sync();

function loadFrame() {
  const file = fileInput.files?.[0];
  if (!file) {
    return;
  }
  const image = new Image();
  image.onload = () => {
    state.image = image;
    resizeFrame(image.naturalWidth, image.naturalHeight);
  };
  image.src = URL.createObjectURL(file);
}

async function loadClip() {
  const file = clipInput.files?.[0];
  if (!file) {
    return;
  }
  try {
    const clip = JSON.parse(await file.text());
    const report = smokeTestTemplate(currentTemplate(), clip, { limit: 200 });
    smokeOutput.textContent = [
      report.ok ? "PASS" : "FAIL",
      `${report.sampledFrames}/${report.totalFrames} frames`,
      ...report.warnings.map((warning) => `warning: ${warning}`),
      ...report.errors.map((error) => `error: ${error}`),
    ].join("\n");
  } catch (error) {
    smokeOutput.textContent = `FAIL\nerror: ${error.message}`;
  }
}

function resizeFrame(width, height) {
  state.frameWidth = Math.max(1, Math.round(width || 1280));
  state.frameHeight = Math.max(1, Math.round(height || 720));
  widthInput.value = state.frameWidth;
  heightInput.value = state.frameHeight;
  if (!state.roi || state.roi.x + state.roi.w > state.frameWidth || state.roi.y + state.roi.h > state.frameHeight) {
    state.roi = { x: 0, y: 0, w: Math.min(260, state.frameWidth), h: Math.min(96, state.frameHeight) };
    state.digitFields = defaultDigitFields(state.roi);
  }
  sync();
}

function applyDrag() {
  const rect = normalizeRect(state.dragging.start, state.dragging.end, state.frameWidth, state.frameHeight);
  if (state.mode === "roi") {
    state.roi = rect;
    state.digitFields = defaultDigitFields(state.roi);
    return;
  }
  const rel = [
    rect.x - state.roi.x,
    rect.y - state.roi.y,
    rect.w,
    rect.h,
  ];
  const existing = new Map(state.digitFields.map((field) => [field.name, field]));
  existing.set(state.mode, { name: state.mode, rel });
  state.digitFields = REQUIRED_FIELDS.map((name) => existing.get(name)).filter(Boolean);
}

function currentTemplate() {
  return buildTemplate({
    templateId: state.templateId,
    frameWidth: state.frameWidth,
    frameHeight: state.frameHeight,
    roi: state.roi,
    colorProfile: state.colorProfile,
    digitFields: state.digitFields,
  });
}

function sync() {
  const template = currentTemplate();
  state.json = formatTemplate(template);
  jsonOutput.value = state.json;
  const validation = validateTemplate(template);
  statusOutput.textContent = [
    validation.ok ? "VALID" : "INVALID",
    ...validation.warnings.map((warning) => `warning: ${warning}`),
    ...validation.errors.map((error) => `error: ${error}`),
  ].join("\n");
  draw();
}

function draw() {
  canvas.width = state.frameWidth;
  canvas.height = state.frameHeight;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if (state.image) {
    ctx.drawImage(state.image, 0, 0, canvas.width, canvas.height);
  } else {
    ctx.fillStyle = "#111827";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = "rgba(255,255,255,.12)";
    for (let x = 0; x < canvas.width; x += 80) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, canvas.height);
      ctx.stroke();
    }
    for (let y = 0; y < canvas.height; y += 80) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(canvas.width, y);
      ctx.stroke();
    }
  }
  drawRect(state.roi, "#22c55e", "roi");
  for (const field of state.digitFields) {
    const [x, y, w, h] = field.rel;
    drawRect({ x: state.roi.x + x, y: state.roi.y + y, w, h }, field.name === state.mode ? "#f59e0b" : "#38bdf8", field.name);
  }
  if (state.dragging) {
    drawRect(normalizeRect(state.dragging.start, state.dragging.end, state.frameWidth, state.frameHeight), "#f43f5e", state.mode);
  }
}

function drawRect(rect, color, label) {
  ctx.save();
  ctx.lineWidth = Math.max(2, Math.round(canvas.width / 640));
  ctx.strokeStyle = color;
  ctx.fillStyle = `${color}22`;
  ctx.fillRect(rect.x, rect.y, rect.w, rect.h);
  ctx.strokeRect(rect.x, rect.y, rect.w, rect.h);
  ctx.font = `${Math.max(12, Math.round(canvas.width / 95))}px system-ui`;
  ctx.fillStyle = color;
  const displayLabel = DISPLAY_LABELS.has(label) ? DISPLAY_LABELS.get(label) : label;
  if (displayLabel) {
    ctx.fillText(displayLabel, rect.x + 5, rect.y + 15);
  }
  ctx.restore();
}

function canvasPoint(event) {
  const bounds = canvas.getBoundingClientRect();
  return {
    x: ((event.clientX - bounds.left) / bounds.width) * state.frameWidth,
    y: ((event.clientY - bounds.top) / bounds.height) * state.frameHeight,
  };
}

function transientStatus(message) {
  const original = statusOutput.textContent;
  statusOutput.textContent = `${message}\n${original}`;
  window.setTimeout(() => {
    statusOutput.textContent = original;
  }, 1200);
}
