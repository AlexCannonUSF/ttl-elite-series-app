import { useCallback, useEffect, useRef, useState } from 'react'
import { Activity, AlertTriangle, BarChart3, BrainCircuit, GitBranch, History, RefreshCcw, ShieldCheck } from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchMlQuality, fetchModelLearningAudit, fetchModelRegistry, fetchModelRunHistory } from '@/features/ml-quality/api'
import { fetchModelCallScorecard } from '@/features/live-studio/api'
import type { ModelCallScorecard } from '@/features/live-studio/types'
import type {
  DailyCount,
  HistogramBin,
  MlQualityResponse,
  ModelLearningAudit,
  ModelRegistryEntry,
  ModelRunHistory,
  ReliabilityBin,
  ReliabilitySnapshot,
} from '@/features/ml-quality/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 60000

export function MlQualityRoute() {
  const [data, setData] = useState<MlQualityResponse | null>(null)
  const [audit, setAudit] = useState<ModelLearningAudit | null>(null)
  const [history, setHistory] = useState<ModelRunHistory | null>(null)
  const [registry, setRegistry] = useState<ModelRegistryEntry[]>([])
  const [scorecard, setScorecard] = useState<ModelCallScorecard | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mountedRef = useRef(true)
  const inFlightRef = useRef(false)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const load = useCallback(async (background: boolean) => {
    // The quality hub combines several evidence-heavy endpoints. Never let a
    // timer enqueue another refresh while the previous snapshot is running.
    if (inFlightRef.current) return
    inFlightRef.current = true
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }
    try {
      const [next, nextAudit, nextHistory, nextRegistry, nextScorecard] = await Promise.allSettled([
        fetchMlQuality({ windowDays: 14, binCount: 10 }),
        fetchModelLearningAudit(180),
        fetchModelRunHistory(25),
        fetchModelRegistry(30),
        fetchModelCallScorecard(40),
      ])
      if (!mountedRef.current) return
      if (next.status === 'fulfilled') setData(next.value)
      if (nextAudit.status === 'fulfilled') setAudit(nextAudit.value)
      if (nextHistory.status === 'fulfilled') setHistory(nextHistory.value)
      if (nextRegistry.status === 'fulfilled') setRegistry(nextRegistry.value)
      if (nextScorecard.status === 'fulfilled') setScorecard(nextScorecard.value)
      const rejected = [next, nextAudit, nextHistory, nextRegistry, nextScorecard]
        .filter((result) => result.status === 'rejected')
      setError(rejected.length
        ? `${rejected.length} model-quality panel${rejected.length === 1 ? '' : 's'} could not refresh; the remaining evidence is current.`
        : null)
    } catch (nextError) {
      if (!mountedRef.current) return
      setError(nextError instanceof Error ? nextError.message : 'Unable to load ML quality right now.')
    } finally {
      inFlightRef.current = false
      if (!mountedRef.current) return
      if (background) {
        setRefreshing(false)
      } else {
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => {
    void load(false)
    const interval = window.setInterval(() => void load(true), REFRESH_INTERVAL_MS)
    return () => window.clearInterval(interval)
  }, [load])

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="ML Quality"
      description="Reliability and drift signals for the active prediction model. Training-time calibration is overlaid with the most recent settled paper-trade decisions so operators can spot calibration regressions early."
      badges={
        <>
          <Badge variant="accent">Model Quality</Badge>
          <Badge>Guarded Refresh 60s</Badge>
        </>
      }
      actions={
        <>
          <Button variant="ghost" asChild>
            <Link to="/admin">Admin Command</Link>
          </Button>
          <Button variant="secondary" onClick={() => void load(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
            Refresh now
          </Button>
        </>
      }
    >
      {error ? (
        <InlineAlert>
          <AlertTriangle className="size-4" />
          <span>{error}</span>
        </InlineAlert>
      ) : null}

      <ModelCommandCenter history={history} scorecard={scorecard} />

      <section className="mb-5 grid gap-5 xl:grid-cols-[1.15fr_0.85fr]">
        <RunHistoryPanel history={history} />
        <RegistryPanel registry={registry} />
      </section>

      <Card className="mb-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">Label trust gate</Badge>
          <CardTitle>Only verified outcomes shape the model</CardTitle>
          <CardDescription>
            Every settlement remains visible, while ambiguous archives, contradictory scores, invalid identities, and
            low-confidence outcomes are quarantined from calibration, trigger ROI, and regime tuning.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {audit ? (
            <div className="grid gap-4">
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <Stat label="Settled samples" value={String(audit.outcomeQuality.totalSamples)} />
                <Stat label="Trusted labels" value={String(audit.outcomeQuality.trustedSettledSamples)} />
                <Stat label="Excluded labels" value={String(audit.outcomeQuality.excludedSettledSamples)} />
                <Stat label="Trusted coverage" value={`${audit.outcomeQuality.eligibleCoveragePct.toFixed(1)}%`} />
              </div>
              {audit.outcomeQuality.exclusionReasons?.length ? (
                <div className="flex flex-wrap gap-2">
                  {(audit.outcomeQuality.exclusionReasons ?? []).map((item) => (
                    <span className="rounded-full border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-900" key={item.reason}>
                      {humanizeCode(item.reason)} · {item.count}
                    </span>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-[var(--ink-muted)]">No excluded settlement labels in this window.</p>
              )}
            </div>
          ) : <Placeholder label="Loading label eligibility…" />}
        </CardContent>
      </Card>

      <section className="grid gap-5 xl:grid-cols-[1.1fr_0.9fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Reliability overlay
            </Badge>
            <CardTitle>Training vs. recent calibration</CardTitle>
            <CardDescription>
              Blue dots come from the latest training run's calibration curve. Amber dots come from settled paper
              trades over the last {data?.windowDays ?? '…'} days. Bubble size scales with sample count.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {loading && !data ? (
              <Placeholder label="Loading reliability…" />
            ) : data ? (
              <ReliabilityOverlay training={data.training} recent={data.recent} />
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Drift KPIs</Badge>
            <CardTitle>Calibration regression vs. training</CardTitle>
            <CardDescription>
              Deltas are recent − training. Severity follows the &gt;=0.04 ECE drift / &gt;=0.05 observed-rate drift gate.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {data ? <DriftKpiTiles snapshot={data} /> : <Placeholder label="Drift signals load with data." />}
          </CardContent>
        </Card>
      </section>

      <section className="mt-5 grid gap-5 xl:grid-cols-[1fr_1fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Probability distribution
            </Badge>
            <CardTitle>Where the model lives</CardTitle>
            <CardDescription>
              10-bin histogram of model probabilities across recently settled decisions. Heavy tails indicate
              over-confident predictions; a missing middle indicates underexploration.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {data ? <ProbabilityHistogram bins={data.probabilityHistogram} /> : <Placeholder label="Loading histogram…" />}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Daily volume</Badge>
            <CardTitle>Settled decisions per day</CardTitle>
            <CardDescription>
              A flat or zero series typically means the paper-trade engine is paused or the scrape lost coverage.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {data ? <DailyVolume series={data.dailyVolume} /> : <Placeholder label="Loading volume…" />}
          </CardContent>
        </Card>
      </section>

      <SignalEvidence audit={audit} />
    </V3Shell>
  )
}

// ---- Subcomponents ---------------------------------------------------------

function ModelCommandCenter({ history, scorecard }: { history: ModelRunHistory | null; scorecard: ModelCallScorecard | null }) {
  const active = history?.runs.find((run) => run.status === 'ACTIVE') ?? history?.runs[0]
  const directionalProgress = Math.min(100, ((scorecard?.settledCalls ?? 0) / 100) * 100)
  return (
    <Card className="mb-5 overflow-hidden border-blue-200 bg-[linear-gradient(135deg,rgba(239,246,255,0.96),rgba(255,255,255,0.92))]">
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Badge variant="accent" className="w-fit"><BrainCircuit className="size-3.5" /> Active model run</Badge>
          <span className="text-xs font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">
            {active?.status ?? 'Loading'} · run {active?.sessionId ?? '—'}
          </span>
        </div>
        <CardTitle>{active?.label ?? 'Preparing model history…'}</CardTitle>
        <CardDescription>
          Artifact <span className="font-semibold text-[var(--ink-strong)]">{active?.effectiveModelVersion ?? 'awaiting first scored matchup'}</span>
          {' · '}policy {active?.policyVersion ?? 'legacy/unversioned'}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-6">
          <Stat label="All model calls" value={String(scorecard?.totalCalls ?? active?.modelCalls ?? 0)} />
          <Stat label="Resolved calls" value={String(scorecard?.settledCalls ?? 0)} />
          <Stat label="Winner accuracy" value={`${(scorecard?.accuracyPct ?? 0).toFixed(1)}%`} />
          <Stat label="$1 decision ROI" value={`${(scorecard?.flatStakeRoiPct ?? 0).toFixed(1)}%`} />
          <Stat label="Brier score" value={scorecard?.brierScore == null ? 'N/A' : scorecard.brierScore.toFixed(3)} />
          <Stat label="Paper W–L–open" value={`${active?.wins ?? 0}–${active?.losses ?? 0}–${active?.openBets ?? 0}`} />
        </div>
        <div>
          <div className="mb-2 flex items-center justify-between text-xs font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">
            <span>Directional evidence readiness</span><span>{scorecard?.settledCalls ?? 0} / 100</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-slate-200"><div className="h-full rounded-full bg-blue-600" style={{ width: `${directionalProgress}%` }} /></div>
        </div>
        <div className="flex flex-wrap gap-2 text-xs font-semibold">
          <span className="rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-emerald-800">$1 fixed research stake</span>
          <span className="rounded-full border border-blue-200 bg-blue-50 px-3 py-1.5 text-blue-800">Model win p ≥ 60%</span>
          <span className="rounded-full border border-blue-200 bg-blue-50 px-3 py-1.5 text-blue-800">Model-market gap ≤ 10 pp</span>
          <span className="rounded-full border border-blue-200 bg-blue-50 px-3 py-1.5 text-blue-800">Plus-money bets quarantined</span>
          <span className="rounded-full border border-slate-200 bg-white px-3 py-1.5 text-slate-700">Adaptive changes evidence-only</span>
        </div>
      </CardContent>
    </Card>
  )
}

function RunHistoryPanel({ history }: { history: ModelRunHistory | null }) {
  return (
    <Card>
      <CardHeader><Badge variant="accent" className="w-fit"><History className="size-3.5" /> Versioned run history</Badge><CardTitle>Every reset is an archive</CardTitle><CardDescription>Exact model, policy, samples, and return remain comparable across runs.</CardDescription></CardHeader>
      <CardContent className="grid gap-3">
        {history?.runs.slice(0, 8).map((run) => (
          <div key={run.sessionId} className="grid gap-2 rounded-[20px] border border-[var(--line)] bg-white/70 p-4 md:grid-cols-[1fr_auto]">
            <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><p className="font-semibold text-[var(--ink-strong)]">#{run.sessionId} {run.label}</p><Badge variant={run.status === 'ACTIVE' ? 'accent' : 'neutral'}>{run.status}</Badge></div><p className="mt-1 truncate text-xs text-[var(--ink-muted)]">{run.effectiveModelVersion ?? run.requestedModelVersion ?? 'legacy model identity'} · {run.policyVersion ?? 'legacy policy'}</p></div>
            <div className="grid grid-cols-3 gap-4 text-right text-sm"><MiniMetric label="Calls" value={run.modelCalls} /><MiniMetric label="W–L" value={`${run.wins}–${run.losses}`} /><MiniMetric label="ROI" value={`${run.roiPct.toFixed(1)}%`} /></div>
          </div>
        )) ?? <Placeholder label="Loading run history…" />}
      </CardContent>
    </Card>
  )
}

function RegistryPanel({ registry }: { registry: ModelRegistryEntry[] }) {
  return (
    <Card>
      <CardHeader><Badge className="w-fit"><GitBranch className="size-3.5" /> Artifact registry</Badge><CardTitle>Candidate models</CardTitle><CardDescription>Shadow means scoreable, not approved for user-facing picks.</CardDescription></CardHeader>
      <CardContent className="grid gap-3">
        {registry.slice(0, 8).map((model) => (
          <div key={model.id} className="rounded-[20px] border border-[var(--line)] bg-white/70 p-4">
            <div className="flex items-start justify-between gap-3"><div className="min-w-0"><p className="truncate text-sm font-semibold">{model.modelVersion}</p><p className="text-xs text-[var(--ink-muted)]">{model.modelFamily} · {model.calibrationMethod ?? 'uncalibrated'}</p></div><Badge variant={model.active ? 'accent' : 'neutral'}>{model.active ? 'Promoted' : 'Shadow'}</Badge></div>
            <div className="mt-3 grid grid-cols-3 gap-2 text-xs"><MiniMetric label="Accuracy" value={formatRatio(model.accuracy)} /><MiniMetric label="Brier" value={model.brierScore?.toFixed(3) ?? 'N/A'} /><MiniMetric label="Log loss" value={model.logLoss?.toFixed(3) ?? 'N/A'} /></div>
          </div>
        ))}
        {!registry.length ? <Placeholder label="No compatible artifacts registered yet." /> : null}
      </CardContent>
    </Card>
  )
}

function SignalEvidence({ audit }: { audit: ModelLearningAudit | null }) {
  return (
    <section className="mt-5 grid gap-5 xl:grid-cols-2">
      <Card><CardHeader><Badge variant="accent" className="w-fit">Trigger evidence</Badge><CardTitle>What is helping or hurting</CardTitle><CardDescription>Evidence only; low effective sample counts do not change production weights.</CardDescription></CardHeader><CardContent className="grid gap-2">{audit?.triggers.slice(0, 10).map((row) => <EvidenceRow key={row.segment} name={row.segment} sample={row.effectiveSampleSize} accuracy={row.winRate} detail={`${row.roiPct.toFixed(1)}% ROI`} />) ?? <Placeholder label="Waiting for trusted trigger outcomes…" />}</CardContent></Card>
      <Card><CardHeader><Badge className="w-fit">Factor direction</Badge><CardTitle>Predictor attribution</CardTitle><CardDescription>Directional accuracy beside contribution strength prevents a loud weak feature from looking proven.</CardDescription></CardHeader><CardContent className="grid gap-2">{audit?.factors.slice(0, 10).map((row) => <EvidenceRow key={row.factor} name={row.factor} sample={row.effectiveSampleSize} accuracy={row.directionalAccuracy} detail={`|impact| ${row.meanAbsoluteContribution.toFixed(3)}`} />) ?? <Placeholder label="Waiting for trusted factor outcomes…" />}</CardContent></Card>
    </section>
  )
}

function EvidenceRow({ name, sample, accuracy, detail }: { name: string; sample: number; accuracy: number; detail: string }) {
  return <div className="grid grid-cols-[1fr_auto_auto] items-center gap-3 rounded-[16px] border border-[var(--line)] bg-white/70 px-3 py-2 text-sm"><span className="font-medium">{name}</span><span className="text-[var(--ink-muted)]">nₑ {sample.toFixed(1)}</span><span className="font-semibold">{(accuracy * 100).toFixed(1)}% · {detail}</span></div>
}

function MiniMetric({ label, value }: { label: string; value: string | number }) { return <div><p className="text-[9px] font-semibold uppercase tracking-[0.18em] text-[var(--ink-muted)]">{label}</p><p className="font-semibold text-[var(--ink-strong)]">{value}</p></div> }

function formatRatio(value: number | null) { return value == null ? 'N/A' : `${(value * 100).toFixed(1)}%` }

function ReliabilityOverlay({ training, recent }: { training: ReliabilitySnapshot; recent: ReliabilitySnapshot }) {
  const width = 360
  const height = 260
  const pad = 32
  const xScale = (v: number) => pad + v * (width - 2 * pad)
  const yScale = (v: number) => height - pad - v * (height - 2 * pad)

  const trainingTotal = training.bins.reduce((acc, bin) => acc + bin.count, 0) || 1
  const recentTotal = recent.bins.reduce((acc, bin) => acc + bin.count, 0) || 1

  return (
    <div className="flex flex-col gap-4">
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full max-w-md text-[var(--ink-muted)]">
        <line x1={pad} y1={height - pad} x2={width - pad} y2={pad} stroke="currentColor" strokeDasharray="4 4" strokeWidth={1} />
        <line x1={pad} y1={height - pad} x2={width - pad} y2={height - pad} stroke="currentColor" strokeWidth={1} />
        <line x1={pad} y1={pad} x2={pad} y2={height - pad} stroke="currentColor" strokeWidth={1} />
        {training.bins.map((bin, idx) => (
          <BinDot
            key={`train-${idx}`}
            cx={xScale(bin.meanPredicted)}
            cy={yScale(bin.observedRate)}
            count={bin.count}
            total={trainingTotal}
            color="rgba(37, 99, 235, 0.55)"
            label={`Training ${binRange(bin)} · n=${bin.count}`}
          />
        ))}
        {recent.bins.map((bin, idx) => (
          <BinDot
            key={`recent-${idx}`}
            cx={xScale(bin.meanPredicted)}
            cy={yScale(bin.observedRate)}
            count={bin.count}
            total={recentTotal}
            color="rgba(217, 119, 6, 0.65)"
            label={`Recent ${binRange(bin)} · n=${bin.count}`}
          />
        ))}
        <text x={width / 2} y={height - 6} textAnchor="middle" className="fill-current text-[10px] uppercase tracking-[0.24em]">
          Mean predicted p
        </text>
        <text x={10} y={height / 2} transform={`rotate(-90 10 ${height / 2})`} textAnchor="middle" className="fill-current text-[10px] uppercase tracking-[0.24em]">
          Observed rate
        </text>
      </svg>

      <div className="grid gap-3 sm:grid-cols-2">
        <Stat label="Training samples" value={String(training.sampleCount)} />
        <Stat label="Recent samples" value={String(recent.sampleCount)} />
        <Stat label="Training ECE" value={formatProb(training.ece)} />
        <Stat label="Recent ECE" value={formatProb(recent.ece)} />
      </div>
    </div>
  )
}

function BinDot({ cx, cy, count, total, color, label }: { cx: number; cy: number; count: number; total: number; color: string; label: string }) {
  const radius = 3 + (count / Math.max(total, 1)) * 7
  return (
    <g>
      <circle cx={cx} cy={cy} r={radius} fill={color} />
      <title>{label}</title>
    </g>
  )
}

function DriftKpiTiles({ snapshot }: { snapshot: MlQualityResponse }) {
  const drift = snapshot.drift
  const severityTone = severityToneClass(drift.severity)
  return (
    <div className="flex flex-col gap-4">
      <div className={cn('rounded-[22px] border p-4', severityTone)}>
        <div className="flex items-center gap-3">
          <ShieldCheck className="size-5" />
          <p className="text-sm font-semibold uppercase tracking-[0.24em]">Drift severity</p>
          <span className="ml-auto font-serif text-2xl font-semibold">{drift.severity}</span>
        </div>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <KeyValue label="ECE Δ (recent − training)" value={formatDelta(drift.eceDelta)} />
        <KeyValue label="Mean predicted Δ" value={formatDelta(drift.meanPredictedDelta)} />
        <KeyValue label="Mean observed Δ" value={formatDelta(drift.meanObservedDelta)} />
        <KeyValue label="Window" value={`${snapshot.windowDays} days`} />
        <KeyValue label="Computed at UTC" value={formatDateTime(snapshot.computedAtUtc)} />
        <KeyValue label="Recent Brier" value={formatProb(snapshot.recent.brierScore)} />
      </div>
    </div>
  )
}

function ProbabilityHistogram({ bins }: { bins: HistogramBin[] }) {
  const max = Math.max(1, ...bins.map((bin) => bin.count))
  return (
    <ul className="flex flex-col gap-2">
      {bins.map((bin) => {
        const ratio = bin.count / max
        const width = `${ratio * 100}%`
        return (
          <li key={`${bin.lowerBound}-${bin.upperBound}`} className="rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.74)] p-3">
            <div className="flex items-center justify-between text-xs text-[var(--ink-muted)]">
              <span>{(bin.lowerBound * 100).toFixed(0)}–{(bin.upperBound * 100).toFixed(0)}%</span>
              <span>{bin.count}</span>
            </div>
            <div className="relative mt-2 h-3 rounded-full bg-[rgba(15,23,42,0.06)]">
              <div className="absolute inset-y-0 left-0 rounded-full" style={{ width, backgroundColor: 'rgba(37, 99, 235, 0.55)' }} />
            </div>
          </li>
        )
      })}
    </ul>
  )
}

function DailyVolume({ series }: { series: DailyCount[] }) {
  const width = 360
  const height = 180
  const pad = 28
  const max = Math.max(1, ...series.map((s) => s.predictions))
  const xStep = series.length > 1 ? (width - 2 * pad) / (series.length - 1) : 0
  const path = series
    .map((point, idx) => {
      const x = pad + xStep * idx
      const y = height - pad - (point.predictions / max) * (height - 2 * pad)
      return `${idx === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')

  return (
    <div className="flex flex-col gap-3">
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full max-w-md text-[var(--ink-muted)]">
        <line x1={pad} y1={height - pad} x2={width - pad} y2={height - pad} stroke="currentColor" strokeWidth={1} />
        <line x1={pad} y1={pad} x2={pad} y2={height - pad} stroke="currentColor" strokeWidth={1} />
        {series.length > 1 ? (
          <path d={path} fill="none" stroke="rgb(37, 99, 235)" strokeWidth={2} />
        ) : null}
        {series.map((point, idx) => {
          const x = pad + xStep * idx
          const y = height - pad - (point.predictions / max) * (height - 2 * pad)
          return (
            <g key={point.date}>
              <circle cx={x} cy={y} r={3} fill="rgba(37, 99, 235, 0.65)" />
              <title>{`${point.date} · ${point.predictions}`}</title>
            </g>
          )
        })}
        <text x={width / 2} y={height - 6} textAnchor="middle" className="fill-current text-[10px] uppercase tracking-[0.24em]">
          Day
        </text>
      </svg>
      <div className="flex items-center justify-between text-xs text-[var(--ink-muted)]">
        <span className="inline-flex items-center gap-2"><BarChart3 className="size-3.5" />Max {max}</span>
        <span className="inline-flex items-center gap-2"><Activity className="size-3.5" />Total {series.reduce((acc, s) => acc + s.predictions, 0)}</span>
      </div>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <p className="text-[10px] font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-2 font-serif text-2xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function KeyValue({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[20px] border border-[var(--line)] bg-[rgba(255,255,255,0.74)] p-3">
      <p className="text-[10px] font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-1 font-medium text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function Placeholder({ label }: { label: string }) {
  return (
    <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
      {label}
    </div>
  )
}

function InlineAlert({ children }: { children: React.ReactNode }) {
  return (
    <div className="mb-5 flex items-center gap-2 rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">
      {children}
    </div>
  )
}

function binRange(bin: ReliabilityBin) {
  return `${(bin.lowerBound * 100).toFixed(0)}-${(bin.upperBound * 100).toFixed(0)}%`
}

function formatProb(value: number | null) {
  if (value == null || Number.isNaN(value)) return 'N/A'
  return `${(value * 100).toFixed(2)}%`
}

function formatDelta(value: number | null) {
  if (value == null || Number.isNaN(value)) return 'N/A'
  const sign = value >= 0 ? '+' : '−'
  return `${sign}${(Math.abs(value) * 100).toFixed(2)}%`
}

function severityToneClass(severity: 'GREEN' | 'AMBER' | 'RED' | 'UNKNOWN') {
  switch (severity) {
    case 'GREEN':
      return 'border-emerald-200 bg-emerald-50 text-emerald-800'
    case 'AMBER':
      return 'border-amber-200 bg-amber-50 text-amber-800'
    case 'RED':
      return 'border-rose-200 bg-rose-50 text-rose-800'
    default:
      return 'border-[var(--line)] bg-[rgba(255,255,255,0.74)] text-[var(--ink-strong)]'
  }
}

function formatDateTime(value: string | null) {
  if (!value) return 'N/A'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

function humanizeCode(value: string) {
  return value
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}
