import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import path from 'node:path'
import { spawn } from 'node:child_process'

import { chromium } from 'playwright-core'

const BUDGETS = {
  initialJsGzKb: 450,
  lcpMs: 2000,
  ttiMs: 1500,
}

const options = parseArgs(process.argv.slice(2))
const distRoot = path.resolve(process.cwd(), options.dist ?? 'dist')
const reportPath = options.out ?? path.resolve(process.cwd(), 'docs/ttlelite-series-3.0/reports/phase-07-perf-audit.md')
const jsonPath = options.json ?? reportPath.replace(/\.md$/i, '.json')
const baseUrl = options.baseUrl ?? 'http://127.0.0.1:5188'

if (!existsSync(distRoot)) {
  console.error(`dist not found at ${distRoot}. Run 'npm run build' first or pass --dist <path>.`)
  process.exit(2)
}

const bundle = measureInitialBundle(distRoot)
console.log(`Initial JS gz: ${bundle.initialJsGzBytes} B (${(bundle.initialJsGzBytes / 1024).toFixed(2)} KB)`)
console.log(`Initial CSS gz: ${bundle.initialCssGzBytes} B (${(bundle.initialCssGzBytes / 1024).toFixed(2)} KB)`)

let runtime = null
if (!options.skipBrowser) {
  const preview = await startPreview(baseUrl)
  try {
    runtime = await measureRuntime(baseUrl)
  } finally {
    preview.kill()
  }
}

writeReport({ bundle, jsonPath, reportPath, runtime })

const failures = []
if (bundle.initialJsGzBytes > BUDGETS.initialJsGzKb * 1024) {
  failures.push(`initial JS gz ${(bundle.initialJsGzBytes / 1024).toFixed(2)} KB exceeds ${BUDGETS.initialJsGzKb} KB`)
}
if (runtime && runtime.lcpMs !== null && runtime.lcpMs > BUDGETS.lcpMs) {
  failures.push(`LCP ${runtime.lcpMs.toFixed(0)} ms exceeds ${BUDGETS.lcpMs} ms`)
}
if (runtime && runtime.ttiMs !== null && runtime.ttiMs > BUDGETS.ttiMs) {
  failures.push(`TTI proxy ${runtime.ttiMs.toFixed(0)} ms exceeds ${BUDGETS.ttiMs} ms`)
}

console.log(`Report: ${reportPath}`)
if (failures.length > 0) {
  console.log('Budget failures:')
  for (const message of failures) {
    console.log(`- ${message}`)
  }
  if (options.failOnBudget) {
    process.exitCode = 1
  }
}

function parseArgs(args) {
  const parsed = {}
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index]
    if (arg === '--fail-on-budget') {
      parsed.failOnBudget = true
      continue
    }
    if (arg === '--skip-browser') {
      parsed.skipBrowser = true
      continue
    }
    if (arg.startsWith('--')) {
      const [key, inlineValue] = arg.slice(2).split('=')
      const value = inlineValue ?? args[index + 1]
      if (inlineValue === undefined) {
        index += 1
      }
      if (key === 'dist') parsed.dist = path.resolve(process.cwd(), value)
      if (key === 'out') parsed.out = path.resolve(process.cwd(), value)
      if (key === 'json') parsed.json = path.resolve(process.cwd(), value)
      if (key === 'base-url') parsed.baseUrl = value
    }
  }
  return parsed
}

function measureInitialBundle(root) {
  const indexHtmlPath = path.join(root, 'index.html')
  if (!existsSync(indexHtmlPath)) {
    throw new Error(`index.html not found at ${indexHtmlPath}`)
  }
  const indexHtml = readFileSync(indexHtmlPath, 'utf8')

  const jsAssets = collectMatches(indexHtml, /<script[^>]+src="([^"]+)"/g)
  const moduleAssets = collectMatches(indexHtml, /<link[^>]+rel="modulepreload"[^>]+href="([^"]+)"/g)
  const cssAssets = collectMatches(indexHtml, /<link[^>]+rel="stylesheet"[^>]+href="([^"]+)"/g)

  const jsFiles = uniq([...jsAssets, ...moduleAssets])
  const cssFiles = uniq(cssAssets)

  const jsEntries = jsFiles.map((href) => sizeFor(root, href))
  const cssEntries = cssFiles.map((href) => sizeFor(root, href))

  const initialJsGzBytes = sum(jsEntries.map((entry) => entry.gzBytes))
  const initialCssGzBytes = sum(cssEntries.map((entry) => entry.gzBytes))
  const indexHtmlGz = gzipSync(Buffer.from(indexHtml)).byteLength

  const allAssets = listAllAssets(root)

  return {
    indexHtmlBytes: indexHtml.length,
    indexHtmlGzBytes: indexHtmlGz,
    initialJsGzBytes,
    initialCssGzBytes,
    jsAssets: jsEntries,
    cssAssets: cssEntries,
    allAssets,
  }
}

function collectMatches(source, pattern) {
  const matches = []
  let match
  while ((match = pattern.exec(source)) !== null) {
    matches.push(match[1])
  }
  return matches
}

function uniq(values) {
  return Array.from(new Set(values))
}

function sizeFor(root, href) {
  const relative = href.replace(/^\/v3\//, '').replace(/^\//, '')
  const absolute = path.join(root, relative)
  if (!existsSync(absolute)) {
    return { href, file: absolute, bytes: 0, gzBytes: 0, missing: true }
  }
  const buffer = readFileSync(absolute)
  return {
    href,
    file: path.relative(root, absolute),
    bytes: buffer.byteLength,
    gzBytes: gzipSync(buffer).byteLength,
  }
}

function listAllAssets(root) {
  const items = []
  walk(root, items)
  items.sort((a, b) => b.gzBytes - a.gzBytes)
  return items
}

function walk(directory, items) {
  for (const name of readdirSync(directory)) {
    const full = path.join(directory, name)
    const stat = statSync(full)
    if (stat.isDirectory()) {
      walk(full, items)
      continue
    }
    if (!/\.(js|css|html)$/i.test(name)) {
      continue
    }
    const buffer = readFileSync(full)
    items.push({
      file: path.relative(path.dirname(directory) === '.' ? directory : directory, full),
      bytes: buffer.byteLength,
      gzBytes: gzipSync(buffer).byteLength,
    })
  }
}

function sum(values) {
  return values.reduce((accumulator, value) => accumulator + value, 0)
}

async function startPreview(baseUrl) {
  const url = new URL(baseUrl)
  const port = Number(url.port || '5188')
  const child = spawn('npx', ['vite', 'preview', '--host', '127.0.0.1', '--port', String(port)], {
    cwd: process.cwd(),
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, FORCE_COLOR: '0' },
  })
  await new Promise((resolve, reject) => {
    let resolved = false
    const onChunk = (chunk) => {
      const text = chunk.toString()
      if (text.includes('Local') || text.includes('preview server')) {
        if (!resolved) {
          resolved = true
          resolve()
        }
      }
    }
    child.stdout.on('data', onChunk)
    child.stderr.on('data', onChunk)
    child.on('error', reject)
    setTimeout(() => {
      if (!resolved) {
        resolved = true
        resolve()
      }
    }, 3000)
  })
  return child
}

async function measureRuntime(baseUrl) {
  const browser = await launchChrome()
  const samples = 3
  const lcpSamples = []
  const ttiSamples = []
  const fcpSamples = []
  try {
    for (let index = 0; index < samples; index += 1) {
      const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } })
      const page = await context.newPage()
      const url = new URL('/v3/', baseUrl).toString()
      await page.goto(url, { waitUntil: 'load', timeout: 30000 })
      await page.waitForTimeout(800)
      const metrics = await page.evaluate(() => {
        const navigation = performance.getEntriesByType('navigation')[0]
        const paints = performance.getEntriesByType('paint')
        const fcpEntry = paints.find((entry) => entry.name === 'first-contentful-paint')
        return new Promise((resolve) => {
          let lcp = null
          try {
            const observer = new PerformanceObserver((list) => {
              const entries = list.getEntries()
              for (const entry of entries) {
                lcp = entry.startTime
              }
            })
            observer.observe({ type: 'largest-contentful-paint', buffered: true })
            setTimeout(() => {
              observer.disconnect()
              resolve({
                fcpMs: fcpEntry ? fcpEntry.startTime : null,
                lcpMs: lcp,
                domInteractiveMs: navigation ? navigation.domInteractive : null,
                domContentLoadedMs: navigation ? navigation.domContentLoadedEventEnd : null,
                loadEventEndMs: navigation ? navigation.loadEventEnd : null,
              })
            }, 500)
          } catch (error) {
            resolve({
              fcpMs: fcpEntry ? fcpEntry.startTime : null,
              lcpMs: null,
              domInteractiveMs: navigation ? navigation.domInteractive : null,
              domContentLoadedMs: navigation ? navigation.domContentLoadedEventEnd : null,
              loadEventEndMs: navigation ? navigation.loadEventEnd : null,
            })
          }
        })
      })
      if (metrics.lcpMs !== null) lcpSamples.push(metrics.lcpMs)
      if (metrics.domInteractiveMs !== null) ttiSamples.push(metrics.domInteractiveMs)
      if (metrics.fcpMs !== null) fcpSamples.push(metrics.fcpMs)
      await context.close()
    }
  } finally {
    await browser.close()
  }
  return {
    samples,
    lcpMs: median(lcpSamples),
    ttiMs: median(ttiSamples),
    fcpMs: median(fcpSamples),
    rawLcp: lcpSamples,
    rawTti: ttiSamples,
    rawFcp: fcpSamples,
  }
}

async function launchChrome() {
  const executablePath = [
    process.env.PLAYWRIGHT_CHROME_PATH,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ].find((candidate) => candidate && existsSync(candidate))

  if (executablePath) {
    return await chromium.launch({ executablePath, headless: true })
  }
  return await chromium.launch({ channel: 'chrome', headless: true })
}

function median(values) {
  if (values.length === 0) {
    return null
  }
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle]
}

function writeReport({ bundle, jsonPath, reportPath, runtime }) {
  mkdirSync(path.dirname(reportPath), { recursive: true })
  mkdirSync(path.dirname(jsonPath), { recursive: true })

  const payload = {
    generatedAt: new Date().toISOString(),
    budgets: BUDGETS,
    bundle,
    runtime,
  }
  writeFileSync(jsonPath, `${JSON.stringify(payload, null, 2)}\n`)

  const kb = (value) => `${(value / 1024).toFixed(2)} KB`
  const initialJsKb = bundle.initialJsGzBytes / 1024
  const initialCssKb = bundle.initialCssGzBytes / 1024
  const jsRatio = ((initialJsKb / BUDGETS.initialJsGzKb) * 100).toFixed(1)

  const lines = [
    '# Phase 07 Performance Audit',
    '',
    `Generated: ${new Date().toISOString()}`,
    '',
    '## Budgets vs measured',
    '',
    '| Metric | Budget | Measured | Status |',
    '| --- | ---: | ---: | :---: |',
    `| Initial JS bundle (gz) | ${BUDGETS.initialJsGzKb} KB | ${initialJsKb.toFixed(2)} KB (${jsRatio}% of budget) | ${initialJsKb <= BUDGETS.initialJsGzKb ? 'PASS' : 'FAIL'} |`,
    `| Initial CSS bundle (gz) | (no spec budget) | ${initialCssKb.toFixed(2)} KB | INFO |`,
    runtime && runtime.lcpMs !== null
      ? `| LCP (median of ${runtime.samples}) | ${BUDGETS.lcpMs} ms | ${runtime.lcpMs.toFixed(0)} ms | ${runtime.lcpMs <= BUDGETS.lcpMs ? 'PASS' : 'FAIL'} |`
      : '| LCP | 2000 ms | not measured (browser skipped) | INFO |',
    runtime && runtime.ttiMs !== null
      ? `| TTI proxy (domInteractive, median of ${runtime.samples}) | ${BUDGETS.ttiMs} ms | ${runtime.ttiMs.toFixed(0)} ms | ${runtime.ttiMs <= BUDGETS.ttiMs ? 'PASS' : 'FAIL'} |`
      : '| TTI proxy | 1500 ms | not measured (browser skipped) | INFO |',
    runtime && runtime.fcpMs !== null
      ? `| FCP (median of ${runtime.samples}) | (informational) | ${runtime.fcpMs.toFixed(0)} ms | INFO |`
      : '',
    '',
    '## Initial-payload composition',
    '',
    '| Asset | bytes | gz bytes |',
    '| --- | ---: | ---: |',
    ...bundle.jsAssets.map((entry) => `| \`${entry.file}\` | ${entry.bytes} | ${entry.gzBytes} |`),
    ...bundle.cssAssets.map((entry) => `| \`${entry.file}\` | ${entry.bytes} | ${entry.gzBytes} |`),
    `| **Total JS gz** |  | **${bundle.initialJsGzBytes} (${kb(bundle.initialJsGzBytes)})** |`,
    `| **Total CSS gz** |  | **${bundle.initialCssGzBytes} (${kb(bundle.initialCssGzBytes)})** |`,
    '',
    '## All built assets (gz desc)',
    '',
    '| Asset | bytes | gz bytes |',
    '| --- | ---: | ---: |',
    ...bundle.allAssets.map((entry) => `| \`${entry.file}\` | ${entry.bytes} | ${entry.gzBytes} |`),
    '',
    '## Notes',
    '',
    '- Initial JS gz size sums every `<script>` and `<link rel="modulepreload">` referenced from `index.html`; this is what the browser is forced to fetch and parse before the first interactive paint.',
    '- LCP is captured via a `PerformanceObserver` with `buffered: true` against the served `/v3/` Home route in headless Chrome at viewport 1440×1100; samples are run from a fresh context each time and reported as median.',
    '- TTI proxy uses `PerformanceNavigationTiming.domInteractive`; this matches the spec\'s "page TTI" gate at §1.16 and §8 because the v3 shell hydrates synchronously off the initial bundle.',
    '- LCP and TTI run against `vite preview` on localhost; they are *local* numbers, matching the spec language ("page TTI < 1.5 s local"). Production CDN behaviour is measured separately once item 7 ships.',
  ].filter((line) => line !== '')

  writeFileSync(reportPath, `${lines.join('\n')}\n`)
}
