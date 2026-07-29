import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'

import { chromium } from 'playwright-core'

const require = createRequire(import.meta.url)
const axe = require('axe-core')

const defaultRoutes = [
  { name: 'Home', path: '/v3/' },
  { name: 'Home Command Palette', path: '/v3/', openCommandPalette: true },
  { name: 'Live Board', path: '/v3/live-board' },
  { name: 'Ops Console', path: '/v3/ops' },
  { name: 'Ops Feeds', path: '/v3/ops/feeds' },
  { name: 'Stream Workers', path: '/v3/ops/feeds/streams' },
  { name: 'Ops Ingest', path: '/v3/ops/ingest' },
  { name: 'Settlement Diffs', path: '/v3/ops/diffs' },
  { name: 'Review Queue', path: '/v3/review' },
  { name: 'ML Quality', path: '/v3/ml/quality' },
]

const options = parseArgs(process.argv.slice(2))
const baseUrl = options.baseUrl ?? 'http://127.0.0.1:5174'
const routes = options.routesFile ? loadRoutes(options.routesFile) : defaultRoutes
const reportPath = options.out ?? path.resolve(process.cwd(), 'docs/ttlelite-series-3.0/reports/phase-07-a11y-audit.md')
const jsonPath = options.json ?? reportPath.replace(/\.md$/i, '.json')

const browser = await launchChrome()
const page = await browser.newPage({
  colorScheme: 'light',
  reducedMotion: options.reducedMotion ? 'reduce' : 'no-preference',
  viewport: { width: 1440, height: 1100 },
})

const results = []
try {
  for (const route of routes) {
    const url = new URL(route.path, baseUrl).toString()
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
    await page.waitForTimeout(Number(options.settleMs ?? 1200))

    if (route.openCommandPalette) {
      await page.keyboard.press(process.platform === 'darwin' ? 'Meta+K' : 'Control+K')
      await page.waitForSelector('[role="dialog"]', { timeout: 5000 })
      await page.waitForTimeout(300)
    }

    await page.addScriptTag({ content: axe.source })
    const axeResult = await page.evaluate(async () => {
      return await window.axe.run(document, {
        resultTypes: ['violations'],
        runOnly: {
          type: 'tag',
          values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'],
        },
      })
    })

    results.push({
      name: route.name ?? route.path,
      path: route.path,
      url: page.url(),
      violations: axeResult.violations.map((violation) => ({
        help: violation.help,
        id: violation.id,
        impact: violation.impact ?? 'unknown',
        nodes: violation.nodes.map((node) => ({
          failureSummary: node.failureSummary,
          html: node.html,
          target: node.target,
        })),
      })),
    })
  }
} finally {
  await browser.close()
}

writeReport({ baseUrl, jsonPath, reducedMotion: Boolean(options.reducedMotion), reportPath, results })

const violations = results.flatMap((result) => result.violations.map((violation) => ({ ...violation, route: result.name })))
const critical = violations.filter((violation) => violation.impact === 'critical')
const serious = violations.filter((violation) => violation.impact === 'serious')

console.log(`A11y audit complete: ${results.length} routes, ${violations.length} WCAG violations.`)
console.log(`Report: ${reportPath}`)
if (violations.length > 0) {
  for (const violation of violations) {
    console.log(`- [${violation.impact}] ${violation.route}: ${violation.id} (${violation.nodes.length} node(s))`)
  }
}

if (critical.length > 0 || (options.failOnSerious && serious.length > 0) || options.failOnAny && violations.length > 0) {
  process.exitCode = 1
}

function parseArgs(args) {
  const parsed = {}
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index]
    if (arg === '--reduced-motion') {
      parsed.reducedMotion = true
      continue
    }
    if (arg === '--fail-on-serious') {
      parsed.failOnSerious = true
      continue
    }
    if (arg === '--fail-on-any') {
      parsed.failOnAny = true
      continue
    }
    if (arg.startsWith('--')) {
      const [key, inlineValue] = arg.slice(2).split('=')
      const value = inlineValue ?? args[index + 1]
      if (inlineValue === undefined) {
        index += 1
      }
      if (key === 'base-url') parsed.baseUrl = value
      if (key === 'routes') parsed.routesFile = value
      if (key === 'out') parsed.out = path.resolve(process.cwd(), value)
      if (key === 'json') parsed.json = path.resolve(process.cwd(), value)
      if (key === 'settle-ms') parsed.settleMs = value
    }
  }
  return parsed
}

function loadRoutes(filePath) {
  const resolved = path.resolve(process.cwd(), filePath)
  const value = require(resolved)
  if (!Array.isArray(value)) {
    throw new Error(`Route file must export a JSON array: ${resolved}`)
  }
  return value.map((entry) => typeof entry === 'string' ? { path: entry } : entry)
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

function writeReport({ baseUrl, jsonPath, reducedMotion, reportPath, results }) {
  mkdirSync(path.dirname(reportPath), { recursive: true })
  mkdirSync(path.dirname(jsonPath), { recursive: true })
  writeFileSync(
    jsonPath,
    `${JSON.stringify({ baseUrl, generatedAt: new Date().toISOString(), reducedMotion, results }, null, 2)}\n`,
  )

  const totalViolations = results.reduce((sum, result) => sum + result.violations.length, 0)
  const criticalViolations = results.reduce(
    (sum, result) => sum + result.violations.filter((violation) => violation.impact === 'critical').length,
    0,
  )
  const lines = [
    '# Phase 07 A11y Audit',
    '',
    `Generated: ${new Date().toISOString()}`,
    `Base URL: ${baseUrl}`,
    `Reduced motion: ${reducedMotion ? 'enabled' : 'not requested'}`,
    `Routes audited: ${results.length}`,
    `WCAG violations: ${totalViolations}`,
    `Critical violations: ${criticalViolations}`,
    '',
    '## Route Summary',
    '',
    '| Route | URL | Violations |',
    '| --- | --- | ---: |',
    ...results.map((result) => `| ${result.name} | ${result.url} | ${result.violations.length} |`),
    '',
    '## Findings',
    '',
  ]

  if (totalViolations === 0) {
    lines.push('No WCAG 2.x/2.2 AA axe-core violations were found on the audited canonical v3 routes.')
  } else {
    for (const result of results) {
      if (result.violations.length === 0) {
        continue
      }
      lines.push(`### ${result.name}`, '')
      for (const violation of result.violations) {
        lines.push(`- ${violation.id} (${violation.impact}): ${violation.help}; nodes: ${violation.nodes.length}`)
      }
      lines.push('')
    }
  }

  writeFileSync(reportPath, `${lines.join('\n')}\n`)
}
