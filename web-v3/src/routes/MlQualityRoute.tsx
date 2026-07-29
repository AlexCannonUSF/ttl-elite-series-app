import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Activity, AlertTriangle, BarChart3, RefreshCcw, ShieldCheck } from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchMlQuality } from '@/features/ml-quality/api'
import type {
  DailyCount,
  HistogramBin,
  MlQualityResponse,
  ReliabilityBin,
  ReliabilitySnapshot,
} from '@/features/ml-quality/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 30000

export function MlQualityRoute() {
  const [data, setData] = useState<MlQualityResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mountedRef = useRef(true)

  useEffect(() => {
    return () => {
      mountedRef.current = false
    }
  }, [])

  const load = useCallback(async (background: boolean) => {
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }
    try {
      const next = await fetchMlQuality({ windowDays: 14, binCount: 10 })
      if (!mountedRef.current) return
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) return
      setError(nextError instanceof Error ? nextError.message : 'Unable to load ML quality right now.')
    } finally {
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
          <Badge>Auto Refresh 30s</Badge>
        </>
      }
      actions={
        <>
          <Button variant="ghost" asChild>
            <Link to="/">Back to Home</Link>
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
    </V3Shell>
  )
}

// ---- Subcomponents ---------------------------------------------------------

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
