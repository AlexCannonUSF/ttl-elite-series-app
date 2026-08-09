import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  BarChart3,
  BrainCircuit,
  CheckCircle2,
  CircleDollarSign,
  Clock3,
  FlaskConical,
  RefreshCcw,
  ShieldCheck,
  Target,
  TrendingUp,
  XCircle,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveRunAnalytics, fetchLiveSession, fetchModelCallMonitor } from '@/features/live-studio/api'
import type {
  LiveRunAnalytics,
  LiveRunFactorPerformance,
  LiveRunSegmentPerformance,
  LiveRunTrendPoint,
  ModelCallTracking,
  ModelCallMonitor,
  PaperTradingSession,
} from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_MS = 15_000

type MatchFilter = 'ALL' | 'RESOLVED' | 'CORRECT' | 'WRONG' | 'LIVE' | 'AWAITING'

export function AdminLiveRunRoute() {
  const [analytics, setAnalytics] = useState<LiveRunAnalytics | null>(null)
  const [monitor, setMonitor] = useState<ModelCallMonitor | null>(null)
  const [session, setSession] = useState<PaperTradingSession | null>(null)
  const [filter, setFilter] = useState<MatchFilter>('ALL')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const load = useCallback(async (background: boolean) => {
    background ? setRefreshing(true) : setLoading(true)
    try {
      const [nextAnalytics, nextMonitor, nextSession] = await Promise.all([
        fetchLiveRunAnalytics(500),
        fetchModelCallMonitor(500),
        fetchLiveSession(),
      ])
      setAnalytics(nextAnalytics)
      setMonitor(nextMonitor)
      setSession(nextSession)
      setError(null)
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Unable to load the active run audit.')
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    void load(false)
    const interval = window.setInterval(() => void load(true), REFRESH_MS)
    return () => window.clearInterval(interval)
  }, [load])

  const calls = useMemo(() => filterCalls(monitor?.calls ?? [], filter), [monitor?.calls, filter])
  const evidenceTone = analytics?.evidenceLabel === 'DECISION_GRADE'
    ? 'text-emerald-200'
    : analytics?.evidenceLabel === 'DIRECTIONAL'
      ? 'text-cyan-200'
      : 'text-amber-200'

  return (
    <V3Shell
      title="Live Run Intelligence"
      description="Every frozen model winner call—paper pick or pass—graded against trusted match outcomes with trigger, factor, market-price, and flat-$1 evidence."
      badges={(
        <>
          <Badge variant="accent">All model calls</Badge>
          <Badge>{pretty(analytics?.evidenceLabel ?? 'collecting')}</Badge>
          <Badge>Auto 15s</Badge>
        </>
      )}
      actions={(
        <Button variant="secondary" onClick={() => void load(true)} disabled={loading || refreshing}>
          <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
          Refresh
        </Button>
      )}
    >
      {error ? <InlineAlert><AlertTriangle className="size-4" />{error}</InlineAlert> : null}

      <section className="admin-hero overflow-hidden rounded-[30px] border border-blue-300/15 p-5 text-white shadow-2xl shadow-black/20 sm:p-7">
        <div className="grid gap-7 xl:grid-cols-[1.1fr_0.9fr] xl:items-end">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="inline-flex items-center gap-2 rounded-full border border-blue-300/20 bg-blue-300/10 px-3 py-1.5 text-[10px] font-semibold uppercase tracking-[0.22em] text-blue-200">
                <TrendingUp className="size-3.5" /> $1 every model lean
              </span>
              <span className={cn('text-xs font-semibold uppercase tracking-[0.18em]', evidenceTone)}>
                {pretty(analytics?.evidenceLabel ?? 'collecting')}
              </span>
            </div>
            <h2 className="mt-5 text-4xl font-semibold tracking-[-0.05em] sm:text-5xl">
              {analytics ? `${analytics.flatStakeWins}–${analytics.flatStakeLosses}` : '—'}
              <span className="ml-3 text-xl font-medium text-slate-400 sm:text-2xl">flat-$1 record</span>
            </h2>
            <p className="mt-4 max-w-3xl text-sm leading-6 text-slate-300">
              This benchmark pretends $1 was placed on the model’s more likely winner at the captured Hard Rock price,
              even when the risk policy made no pick. It measures prediction and executable-price performance separately
              from official paper-bet learning.
            </p>
            <div className="mt-6">
              <div className="flex items-end justify-between gap-4 text-xs">
                <span className="text-slate-400">All-call readiness · {analytics?.settledCalls ?? 0} of {analytics?.readinessTarget ?? 100} trusted resolutions</span>
                <span className="font-mono font-bold text-white">{formatPctPoints(analytics?.readinessPct)}</span>
              </div>
              <div className="mt-2 h-2 overflow-hidden rounded-full bg-white/10">
                <div className="h-full rounded-full bg-gradient-to-r from-blue-500 via-cyan-400 to-emerald-400" style={{ width: `${analytics?.readinessPct ?? 0}%` }} />
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <DarkMetric label="$1 net" value={formatSignedCurrency(analytics?.flatStakeNetProfit)} tone={(analytics?.flatStakeNetProfit ?? 0) >= 0 ? 'good' : 'bad'} />
            <DarkMetric label="$1 ROI" value={formatSignedPctPoints(analytics?.flatStakeRoiPct)} tone={(analytics?.flatStakeRoiPct ?? 0) >= 0 ? 'good' : 'bad'} />
            <DarkMetric label="Winner accuracy" value={formatPctPoints(analytics?.accuracyPct)} />
            <DarkMetric label="Accuracy 95% CI" value={formatInterval(analytics?.accuracyCiLowPct, analytics?.accuracyCiHighPct)} />
            <DarkMetric label="Paper picks resolved" value={String(analytics?.settledPaperPicks ?? 0)} />
            <DarkMetric label="Model-only resolved" value={String(analytics?.settledModelOnlyCalls ?? 0)} />
          </div>
        </div>
      </section>

      <section className="mt-5 grid gap-5 xl:grid-cols-[1.28fr_0.72fr]">
        <Card>
          <CardHeader>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <Badge variant="accent" className="w-fit"><BarChart3 className="mr-1 size-3" /> Run trend</Badge>
              <span className="text-xs text-[var(--ink-muted)]">One point per trusted resolution</span>
            </div>
            <CardTitle>Cumulative $1 profit and outright accuracy</CardTitle>
            <CardDescription>
              Profit uses the actual captured Hard Rock price. Accuracy answers only whether the more-likely player won.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <RunTrendChart points={analytics?.trend ?? []} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit"><ShieldCheck className="mr-1 size-3" /> Sample confidence</Badge>
            <CardTitle>What can be trusted today</CardTitle>
            <CardDescription>Intervals stay visible so a hot first run cannot masquerade as a proven edge.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            <EvidenceRow label="Observed accuracy" value={formatPctPoints(analytics?.accuracyPct)} detail={`95% CI ${formatInterval(analytics?.accuracyCiLowPct, analytics?.accuracyCiHighPct)}`} />
            <EvidenceRow label="Flat-$1 ROI" value={formatSignedPctPoints(analytics?.flatStakeRoiPct)} detail={`95% mean-return CI ${formatInterval(analytics?.flatStakeRoiCiLowPct, analytics?.flatStakeRoiCiHighPct)}`} />
            <EvidenceRow label="Chance mean ROI is positive" value={analytics?.positiveRoiConfidencePct == null ? 'Needs 5+' : formatPctPoints(analytics.positiveRoiConfidencePct)} detail="Exploratory normal approximation; not a guarantee" />
            <EvidenceRow label="Brier score" value={analytics?.brierScore == null ? '—' : analytics.brierScore.toFixed(3)} detail="Lower is better; probability calibration" />
            <EvidenceRow label="Average stated confidence" value={formatPctPoints(analytics?.averageConfidencePct)} detail={`${analytics?.awaitingCalls ?? 0} calls still awaiting a trusted result`} />
            <div className="rounded-[18px] border border-amber-200 bg-amber-50 p-3 text-xs leading-5 text-amber-950">
              At {analytics?.settledCalls ?? 0} outcomes this is <strong>{pretty(analytics?.evidenceLabel ?? 'collecting')}</strong> evidence.
              Trigger rows need 30 outcomes each; factor direction needs 50 before it is marked ready.
            </div>
          </CardContent>
        </Card>
      </section>

      <section className="mt-5 grid gap-5 xl:grid-cols-2">
        <PerformanceTable
          badge="Trigger ledger"
          title="Which prediction triggers are holding up"
          description="All resolved calls grouped by the frozen top trigger, including passes. Reliability and readiness remain beside ROI."
          empty="Trigger snapshots will populate as newly captured calls settle."
          rows={analytics?.triggers ?? []}
        />
        <PerformanceTable
          badge="Gate counterfactual"
          title="What happened to matches the policy passed on"
          description="The model’s winner call is graded by skip reason, exposing whether a strict gate is filtering value or merely starving the sample."
          empty="Decision-reason evidence will appear after calls settle."
          rows={analytics?.decisionReasons ?? []}
        />
      </section>

      <section className="mt-5 grid gap-5 xl:grid-cols-[1fr_0.62fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit"><BrainCircuit className="mr-1 size-3" /> Factor attribution</Badge>
            <CardTitle>What is helping, hurting, and still unknown</CardTitle>
            <CardDescription>
              Contributions are aligned to the called winner. Direction score checks whether each factor’s sign matched the eventual outcome.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <FactorGrid factors={analytics?.factors ?? []} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit"><FlaskConical className="mr-1 size-3" /> Sampling health</Badge>
            <CardTitle>Paper-trade opportunity funnel</CardTitle>
            <CardDescription>Production picks and bounded exploration are tracked separately from the all-call $1 benchmark.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            <EvidenceRow label="Rows considered" value={formatInt(session?.decisionTelemetry?.consideredCount)} detail={`${formatInt(session?.decisionTelemetry?.skippedCount)} skipped evaluations`} />
            <EvidenceRow label="Paper positions" value={formatInt(session?.decisionTelemetry?.placedCount)} detail={`${formatPctPoints(session?.decisionTelemetry?.placementRatePct)} placement rate`} />
            <EvidenceRow label="Exploration positions" value={formatInt(session?.decisionTelemetry?.fallbackPlacedCount)} detail="Capped at one new exploratory position per sync" />
            <EvidenceRow label="Average skipped edge" value={formatPctPoints(session?.decisionTelemetry?.avgSkippedEdgePct)} detail="Edge is stored for counterfactual gate analysis" />
            <div className="rounded-[18px] border border-[var(--line)] bg-white/55 p-3">
              <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">Top skip pressure</p>
              <div className="mt-2 flex flex-wrap gap-2">
                {(session?.decisionTelemetry?.topSkipReasons ?? []).slice(0, 5).map((item) => (
                  <span className="rounded-full border border-[var(--line)] bg-white px-2.5 py-1 text-[11px] font-semibold text-[var(--ink-strong)]" key={item.reason}>
                    {pretty(item.reason)} · {item.count}
                  </span>
                ))}
              </div>
            </div>
          </CardContent>
        </Card>
      </section>

      <Card className="mt-5">
        <CardHeader>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <Badge variant="accent" className="w-fit"><Activity className="mr-1 size-3" /> Match audit</Badge>
              <CardTitle className="mt-3">Every call, score, price, signal, and pipeline state</CardTitle>
              <CardDescription>Open any row for the full score timeline and viewer-approval controls.</CardDescription>
            </div>
            <div className="flex flex-wrap gap-1.5">
              {(['ALL', 'RESOLVED', 'CORRECT', 'WRONG', 'LIVE', 'AWAITING'] as MatchFilter[]).map((item) => (
                <button
                  className={cn(
                    'rounded-full border px-3 py-1.5 text-[10px] font-semibold uppercase tracking-[0.14em] transition',
                    filter === item
                      ? 'border-blue-300 bg-blue-50 text-blue-800'
                      : 'border-[var(--line)] bg-white/60 text-[var(--ink-muted)] hover:text-[var(--ink-strong)]',
                  )}
                  key={item}
                  onClick={() => setFilter(item)}
                >{item}</button>
              ))}
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <MatchAuditTable calls={calls} loading={loading} />
        </CardContent>
      </Card>
    </V3Shell>
  )
}

function RunTrendChart({ points }: { points: LiveRunTrendPoint[] }) {
  if (!points.length) return <EmptyState label="Waiting for the first trusted result." />
  const width = 780
  const height = 270
  const padX = 42
  const padY = 28
  const netValues = points.map((point) => point.cumulativeNetProfit)
  const netLow = Math.min(0, ...netValues)
  const netHigh = Math.max(0, ...netValues)
  const netRange = Math.max(1, netHigh - netLow)
  const x = (index: number) => padX + index * ((width - padX * 2) / Math.max(1, points.length - 1))
  const netY = (value: number) => height - padY - ((value - netLow) / netRange) * (height - padY * 2)
  const accuracyY = (value: number) => height - padY - (value / 100) * (height - padY * 2)
  const netPath = points.map((point, index) => `${index ? 'L' : 'M'} ${x(index)} ${netY(point.cumulativeNetProfit)}`).join(' ')
  const accuracyPath = points.map((point, index) => `${index ? 'L' : 'M'} ${x(index)} ${accuracyY(point.runningAccuracyPct)}`).join(' ')
  return (
    <div>
      <div className="flex flex-wrap items-center gap-4 text-[11px] font-semibold text-[var(--ink-muted)]">
        <span className="inline-flex items-center gap-2"><span className="h-0.5 w-6 bg-emerald-500" /> Cumulative $1 net</span>
        <span className="inline-flex items-center gap-2"><span className="h-0.5 w-6 bg-blue-500" /> Running accuracy</span>
        <span className="ml-auto">Latest: {formatSignedCurrency(points.at(-1)?.cumulativeNetProfit)} · {formatPctPoints(points.at(-1)?.runningAccuracyPct)}</span>
      </div>
      <svg aria-label="Cumulative flat-dollar profit and accuracy trend" className="mt-4 w-full" role="img" viewBox={`0 0 ${width} ${height}`}>
        {[0, 0.25, 0.5, 0.75, 1].map((ratio) => {
          const y = padY + ratio * (height - padY * 2)
          return <line key={ratio} x1={padX} x2={width - padX} y1={y} y2={y} stroke="rgba(100,116,139,.18)" strokeWidth="1" />
        })}
        <line x1={padX} x2={width - padX} y1={netY(0)} y2={netY(0)} stroke="rgba(15,23,42,.35)" strokeDasharray="5 5" />
        <path d={netPath} fill="none" stroke="rgb(16 185 129)" strokeLinecap="round" strokeLinejoin="round" strokeWidth="3" />
        <path d={accuracyPath} fill="none" stroke="rgb(59 130 246)" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2.5" />
        {points.map((point, index) => (
          <g key={point.callId}>
            <circle cx={x(index)} cy={netY(point.cumulativeNetProfit)} fill={point.correct ? 'rgb(16 185 129)' : 'rgb(244 63 94)'} r="3.5">
              <title>{`#${point.sample} ${point.eventName}: ${point.correct ? 'correct' : 'wrong'}, net ${formatSignedCurrency(point.cumulativeNetProfit)}`}</title>
            </circle>
          </g>
        ))}
        <text x={padX} y={height - 5} className="fill-slate-500 text-[10px]">1</text>
        <text x={width - padX} y={height - 5} textAnchor="end" className="fill-slate-500 text-[10px]">{points.length} resolved</text>
        <text x="4" y={padY + 4} className="fill-emerald-700 text-[10px]">${netHigh.toFixed(1)}</text>
        <text x={width - 4} y={padY + 4} textAnchor="end" className="fill-blue-700 text-[10px]">100%</text>
      </svg>
    </div>
  )
}

function PerformanceTable({ badge, title, description, empty, rows }: {
  badge: string
  title: string
  description: string
  empty: string
  rows: LiveRunSegmentPerformance[]
}) {
  return (
    <Card>
      <CardHeader>
        <Badge variant="accent" className="w-fit">{badge}</Badge>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        {!rows.length ? <EmptyState label={empty} /> : (
          <div className="overflow-x-auto rounded-[20px] border border-[var(--line)]">
            <table className="w-full min-w-[760px] text-left text-xs">
              <thead className="bg-[var(--panel-soft)] text-[10px] uppercase tracking-[0.14em] text-[var(--ink-muted)]">
                <tr><th className="px-4 py-3">Segment</th><th>Record</th><th>Accuracy / 95% CI</th><th>Cal. gap</th><th>$1 net / ROI</th><th className="pr-4">Ready</th></tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr className="border-t border-[var(--line)]" key={row.segment}>
                    <td className="px-4 py-3">
                      <p className="max-w-[220px] font-semibold text-[var(--ink-strong)]">{pretty(row.segment)}</p>
                      <p className="mt-1 text-[10px] text-[var(--ink-muted)]">Reliability {formatPctPoints(row.averageReliabilityPct)}</p>
                    </td>
                    <td className="font-mono font-semibold">{row.wins}–{row.losses}</td>
                    <td><p className="font-semibold">{formatPctPoints(row.accuracyPct)}</p><p className="text-[10px] text-[var(--ink-muted)]">{formatInterval(row.accuracyCiLowPct, row.accuracyCiHighPct)}</p></td>
                    <td className={row.calibrationGapPct > 8 ? 'text-amber-700' : 'text-[var(--ink-strong)]'}>{formatSignedPctPoints(row.calibrationGapPct)}</td>
                    <td className={row.flatStakeNetProfit >= 0 ? 'font-semibold text-emerald-700' : 'font-semibold text-rose-700'}>{formatSignedCurrency(row.flatStakeNetProfit)} · {formatSignedPctPoints(row.flatStakeRoiPct)}</td>
                    <td className="pr-4">
                      <p className="font-mono font-semibold">n {row.sampleSize}/{row.readinessTarget}</p>
                      <div className="mt-1 h-1.5 w-20 rounded-full bg-slate-200"><div className="h-full rounded-full bg-blue-500" style={{ width: `${row.readinessPct}%` }} /></div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function FactorGrid({ factors }: { factors: LiveRunFactorPerformance[] }) {
  if (!factors.length) return <EmptyState label="Factor snapshots will populate as newly captured calls settle." />
  const max = Math.max(...factors.map((factor) => factor.meanAbsoluteContribution), 0.001)
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {factors.slice(0, 12).map((factor) => (
        <div className="rounded-[20px] border border-[var(--line)] bg-white/55 p-4" key={factor.factor}>
          <div className="flex items-start justify-between gap-3">
            <div><p className="font-semibold text-[var(--ink-strong)]">{pretty(factor.factor)}</p><p className="mt-1 text-[10px] uppercase tracking-[0.14em] text-[var(--ink-muted)]">n {factor.sampleSize}/{factor.readinessTarget}</p></div>
            <span className={cn('rounded-full px-2 py-1 text-[10px] font-bold', factor.readinessPct >= 100 ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-600')}>{factor.readinessPct >= 100 ? 'READY' : `${Math.round(factor.readinessPct)}%`}</span>
          </div>
          <div className="mt-3 h-2 overflow-hidden rounded-full bg-slate-200"><div className="h-full rounded-full bg-gradient-to-r from-blue-600 to-cyan-400" style={{ width: `${factor.meanAbsoluteContribution / max * 100}%` }} /></div>
          <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
            <KeyValue label="Direction" value={formatPctPoints(factor.directionalAccuracyPct)} />
            <KeyValue label="Mean |impact|" value={factor.meanAbsoluteContribution.toFixed(3)} />
            <KeyValue label="When correct" value={formatSigned(factor.meanContributionWhenCorrect, 3)} />
            <KeyValue label="When wrong" value={formatSigned(factor.meanContributionWhenWrong, 3)} />
          </div>
        </div>
      ))}
    </div>
  )
}

function MatchAuditTable({ calls, loading }: { calls: ModelCallTracking[]; loading: boolean }) {
  if (loading && !calls.length) return <EmptyState label="Loading match audit…" />
  if (!calls.length) return <EmptyState label="No matches match this filter." />
  return (
    <div className="overflow-x-auto rounded-[22px] border border-[var(--line)]">
      <table className="w-full min-w-[1280px] text-left text-xs">
        <thead className="bg-[var(--panel-soft)] text-[10px] uppercase tracking-[0.14em] text-[var(--ink-muted)]">
          <tr><th className="px-4 py-3">State / score</th><th>Match</th><th>Model call</th><th>Fair vs Hard Rock</th><th>Trigger / quality</th><th>$1 result</th><th className="pr-4">Decision</th></tr>
        </thead>
        <tbody>
          {calls.map((call) => {
            const profit = flatProfit(call)
            return (
              <tr className="border-t border-[var(--line)] align-top transition hover:bg-blue-50/45" key={call.callId}>
                <td className="px-4 py-3">
                  <OutcomeBadge call={call} />
                  <p className="mt-2 font-mono font-bold text-[var(--ink-strong)]">{call.systemScore ?? call.latestScore ?? '—'}</p>
                  <p className="mt-1 text-[10px] text-[var(--ink-muted)]">{pretty(call.latestPhase ?? call.pipelineStage)}</p>
                </td>
                <td className="py-3">
                  <Link className="group inline-flex items-center gap-1 font-semibold text-[var(--ink-strong)] hover:text-blue-700" to={`/admin/pipeline/${call.callId}`}>
                    {call.player1Name} vs {call.player2Name}<ArrowRight className="size-3 transition group-hover:translate-x-0.5" />
                  </Link>
                  <p className="mt-1 text-[10px] text-[var(--ink-muted)]">{formatDateTime(call.startTimeIso)} · {pretty(call.captureType)}</p>
                  <p className="mt-1 max-w-[260px] text-[10px] text-[var(--ink-muted)]">{call.pipelineDetail}</p>
                </td>
                <td className="py-3">
                  <p className="font-semibold text-[var(--ink-strong)]">{call.predictedWinnerName ?? 'No lean'}</p>
                  <p className="mt-1 font-mono">{formatProbability(call.modelProbability)} model probability</p>
                  <p className="mt-1 text-[10px] text-[var(--ink-muted)]">Actual: {call.systemWinnerName ?? call.viewerWinnerName ?? 'awaiting'}</p>
                </td>
                <td className="py-3">
                  <p><span className="text-[var(--ink-muted)]">Fair</span> <strong>{formatOdds(call.modelFairAmericanOdds)}</strong></p>
                  <p className="mt-1"><span className="text-[var(--ink-muted)]">Hard Rock</span> <strong>{formatOdds(call.hardRockAmericanOdds)}</strong></p>
                  <p className="mt-1 text-[10px] text-[var(--ink-muted)]">No-vig {formatProbability(call.hardRockNoVigProbability)} · hold {formatPctPoints(call.hardRockMarginPct)}</p>
                  <p className="mt-1 text-[10px] font-semibold text-blue-700">Model − market {formatSignedPctPoints(modelMarketGap(call))}</p>
                </td>
                <td className="py-3">
                  <p className="font-semibold text-[var(--ink-strong)]">{pretty(call.topTrigger ?? 'unknown')}</p>
                  <p className="mt-1 text-[10px] text-[var(--ink-muted)]">Trigger {formatProbability(call.triggerReliability)} · overall {formatProbability(call.overallReliability)}</p>
                  <p className="mt-1 text-[10px] text-[var(--ink-muted)]">Signal {formatProbability(call.signalQuality)} · width {formatProbability(call.confidenceWidth)}</p>
                  <p className="mt-1 max-w-[230px] truncate font-mono text-[9px] text-[var(--ink-muted)]" title={call.featureContributions ?? undefined}>{factorPreview(call.featureContributions)}</p>
                </td>
                <td className="py-3">
                  {profit == null ? <span className="text-[var(--ink-muted)]">Awaiting</span> : (
                    <><p className={cn('font-mono text-base font-bold', profit >= 0 ? 'text-emerald-700' : 'text-rose-700')}>{formatSignedCurrency(profit)}</p><p className="mt-1 text-[10px] text-[var(--ink-muted)]">on $1 at {formatOdds(call.hardRockAmericanOdds)}</p></>
                  )}
                </td>
                <td className="py-3 pr-4">
                  <span className={cn('rounded-full px-2 py-1 text-[9px] font-bold uppercase tracking-[0.12em]', call.paperPickPlaced ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-600')}>{call.paperPickPlaced ? 'Paper pick' : 'Model only'}</span>
                  <p className="mt-2 font-semibold text-[var(--ink-strong)]">{pretty(call.decisionReason ?? 'unknown')}</p>
                  <p className="mt-1 text-[10px] text-[var(--ink-muted)]">Edge {formatProbability(call.suggestedEdge)} · score {call.selectionScore?.toFixed(2) ?? '—'}</p>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function OutcomeBadge({ call }: { call: ModelCallTracking }) {
  if (call.effectiveOutcome === 'CORRECT') return <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-1 text-[9px] font-bold uppercase tracking-[0.12em] text-emerald-800"><CheckCircle2 className="size-3" /> Correct</span>
  if (call.effectiveOutcome === 'INCORRECT') return <span className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2 py-1 text-[9px] font-bold uppercase tracking-[0.12em] text-rose-800"><XCircle className="size-3" /> Wrong</span>
  if (call.latestLive) return <span className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2 py-1 text-[9px] font-bold uppercase tracking-[0.12em] text-rose-800"><Activity className="size-3 animate-pulse" /> Live</span>
  return <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-1 text-[9px] font-bold uppercase tracking-[0.12em] text-slate-600"><Clock3 className="size-3" /> {pretty(call.pipelineStage)}</span>
}

function filterCalls(calls: ModelCallTracking[], filter: MatchFilter) {
  if (filter === 'ALL') return calls
  if (filter === 'RESOLVED') return calls.filter((call) => ['CORRECT', 'INCORRECT', 'NO_LEAN'].includes(call.effectiveOutcome))
  if (filter === 'CORRECT') return calls.filter((call) => call.effectiveOutcome === 'CORRECT')
  if (filter === 'WRONG') return calls.filter((call) => call.effectiveOutcome === 'INCORRECT')
  if (filter === 'LIVE') return calls.filter((call) => call.latestLive || call.pipelineStage === 'LIVE_MONITORING')
  return calls.filter((call) => call.effectiveOutcome === 'AWAITING')
}

function flatProfit(call: ModelCallTracking) {
  if (!['CORRECT', 'INCORRECT'].includes(call.effectiveOutcome) || !call.hardRockAmericanOdds) return null
  if (call.effectiveOutcome === 'INCORRECT') return -1
  return call.hardRockAmericanOdds > 0 ? call.hardRockAmericanOdds / 100 : 100 / Math.abs(call.hardRockAmericanOdds)
}

function modelMarketGap(call: ModelCallTracking) {
  if (call.modelProbability == null || call.hardRockNoVigProbability == null) return null
  return (call.modelProbability - call.hardRockNoVigProbability) * 100
}

function factorPreview(encoded: string | null) {
  if (!encoded) return 'Factor snapshot pending'
  return encoded.split('|').slice(0, 3).map((item) => pretty(item.replace('=', ' '))).join(' · ')
}

function DarkMetric({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' }) {
  return (
    <div className="rounded-[18px] border border-white/10 bg-white/[0.055] p-3.5">
      <p className="text-[9px] font-semibold uppercase tracking-[0.18em] text-slate-400">{label}</p>
      <p className={cn('mt-2 font-mono text-lg font-bold', tone === 'good' ? 'text-emerald-300' : tone === 'bad' ? 'text-rose-300' : 'text-white')}>{value}</p>
    </div>
  )
}

function EvidenceRow({ label, value, detail }: { label: string; value: string; detail: string }) {
  return <div className="rounded-[18px] border border-[var(--line)] bg-white/55 p-3"><div className="flex items-center justify-between gap-3"><span className="text-xs font-semibold text-[var(--ink-strong)]">{label}</span><span className="font-mono text-sm font-bold text-[var(--ink-strong)]">{value}</span></div><p className="mt-1 text-[10px] text-[var(--ink-muted)]">{detail}</p></div>
}

function KeyValue({ label, value }: { label: string; value: string }) {
  return <div><p className="text-[9px] uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p><p className="mt-1 font-mono font-semibold text-[var(--ink-strong)]">{value}</p></div>
}

function EmptyState({ label }: { label: string }) {
  return <div className="rounded-[20px] border border-dashed border-[var(--line)] bg-white/40 p-6 text-sm text-[var(--ink-muted)]">{label}</div>
}

function InlineAlert({ children }: { children: React.ReactNode }) {
  return <div className="mb-5 flex items-center gap-2 rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-900">{children}</div>
}

function pretty(value: string) {
  return value.toLowerCase().replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function formatOdds(value: number | null | undefined) {
  if (value == null) return '—'
  return value > 0 ? `+${value}` : String(value)
}

function formatProbability(value: number | null | undefined) {
  return value == null ? '—' : `${(value * 100).toFixed(1)}%`
}

function formatPctPoints(value: number | null | undefined) {
  return value == null ? '—' : `${value.toFixed(1)}%`
}

function formatSignedPctPoints(value: number | null | undefined) {
  if (value == null) return '—'
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)}%`
}

function formatSignedCurrency(value: number | null | undefined) {
  if (value == null) return '—'
  return `${value >= 0 ? '+' : '−'}$${Math.abs(value).toFixed(2)}`
}

function formatInterval(low: number | null | undefined, high: number | null | undefined) {
  return low == null || high == null ? 'Needs more data' : `${low.toFixed(1)}–${high.toFixed(1)}%`
}

function formatInt(value: number | null | undefined) {
  return value == null ? '—' : value.toLocaleString()
}

function formatSigned(value: number, digits = 2) {
  return `${value >= 0 ? '+' : ''}${value.toFixed(digits)}`
}

function formatDateTime(value: string | null) {
  if (!value) return 'Time pending'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}
