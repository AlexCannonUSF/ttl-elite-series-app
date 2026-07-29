import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, Brain, RefreshCcw, Shield, Sparkles } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchPredictionPanel, parseMatchKey } from '@/features/prediction/api'
import type {
  PredictionContribution,
  PredictionPanelResponse,
  ReliabilityBin,
} from '@/features/prediction/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 15000

export function MatchPredictionRoute() {
  const { id } = useParams()
  const parsed = useMemo(() => parseMatchKey(id), [id])

  const [data, setData] = useState<PredictionPanelResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mountedRef = useRef(true)

  useEffect(() => {
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadPanel = useCallback(async (background: boolean) => {
    if (!parsed) {
      if (mountedRef.current) {
        setError('Route id should look like "player1Id-player2Id" (e.g. /matches/10-20/prediction).')
        setLoading(false)
      }
      return
    }
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }
    try {
      const next = await fetchPredictionPanel({
        player1Id: parsed.player1Id,
        player2Id: parsed.player2Id,
        asOfDate: parsed.asOfDate,
        topK: 6,
      })
      if (!mountedRef.current) return
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) return
      setError(nextError instanceof Error ? nextError.message : 'Unable to load the prediction panel right now.')
    } finally {
      if (!mountedRef.current) return
      if (background) {
        setRefreshing(false)
      } else {
        setLoading(false)
      }
    }
  }, [parsed])

  useEffect(() => {
    void loadPanel(false)
    const interval = window.setInterval(() => void loadPanel(true), REFRESH_INTERVAL_MS)
    return () => window.clearInterval(interval)
  }, [loadPanel])

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Match Prediction"
      description="Calibrated probability for player 1, the Mondrian split-conformal interval around it, the SHAP top contributions powering the call, and the latest reliability curve from training."
      badges={
        <>
          <Badge variant="accent">Prediction</Badge>
          <Badge>Auto Refresh 15s</Badge>
        </>
      }
      actions={
        <>
          <Button variant="ghost" asChild>
            <Link to="/">Back to Home</Link>
          </Button>
          <Button variant="secondary" onClick={() => void loadPanel(true)} disabled={loading || refreshing}>
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
              Probability
            </Badge>
            <CardTitle>Model p_top with conformal interval</CardTitle>
            <CardDescription>
              The blue band is the model's calibrated confidence interval; the amber band overlays the Mondrian
              split-conformal interval at coverage 1−α.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-5">
            {loading && !data ? (
              <Placeholder label="Loading prediction…" />
            ) : null}
            {data ? <ProbabilityCard panel={data} /> : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Conformal</Badge>
            <CardTitle>Uncertainty envelope (Spec §8.4)</CardTitle>
            <CardDescription>
              Prediction set membership decides whether we treat the matchup as confident, ambiguous, or anomalous.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {data ? <ConformalCard panel={data} /> : <Placeholder label="No conformal data yet." />}
          </CardContent>
        </Card>
      </section>

      <section className="mt-5 grid gap-5 xl:grid-cols-[1fr_1fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              SHAP top contributions
            </Badge>
            <CardTitle>Why this prediction</CardTitle>
            <CardDescription>
              Top-K features by absolute contribution to player 1's calibrated probability. Positive bars push
              toward player 1; negative bars push toward player 2.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {data && data.topContributions.length > 0 ? (
              <ShapBars contributions={data.topContributions} />
            ) : (
              <Placeholder label="No feature contributions returned for this matchup." />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Reliability</Badge>
            <CardTitle>Latest training calibration curve</CardTitle>
            <CardDescription>
              The closer the bins hug the diagonal, the better calibrated the model. Bins are taken from the
              latest model-registry training run.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {data && data.reliabilityCurve.length > 0 ? (
              <ReliabilityCurve bins={data.reliabilityCurve} />
            ) : (
              <Placeholder label="No reliability curve in the latest training report yet." />
            )}
          </CardContent>
        </Card>
      </section>

      {data ? (
        <Card className="mt-5">
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Run metadata
            </Badge>
            <CardTitle>Model + calibration identifiers</CardTitle>
            <CardDescription>Pinned for traceability on every panel render.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetadataTile label="Model family" value={data.modelFamily} />
            <MetadataTile label="Model version" value={data.modelVersion} />
            <MetadataTile label="Calibration method" value={data.calibrationMethod} />
            <MetadataTile label="Conformal" value={data.conformal.method} />
            <MetadataTile label="Player 1 id" value={String(data.player1Id)} />
            <MetadataTile label="Player 2 id" value={String(data.player2Id)} />
            <MetadataTile label="Match key" value={data.matchKey} />
            <MetadataTile label="Computed at UTC" value={formatDateTime(data.computedAtUtc)} />
          </CardContent>
        </Card>
      ) : null}
    </V3Shell>
  )
}

// ---- Subcomponents ---------------------------------------------------------

function ProbabilityCard({ panel }: { panel: PredictionPanelResponse }) {
  const pTop = panel.pTop.value
  return (
    <>
      <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.78)] p-5">
        <div className="flex items-baseline gap-3">
          <Brain className="size-5 text-[var(--accent-ink)]" />
          <p className="font-serif text-5xl font-semibold tracking-[-0.05em] text-[var(--ink-strong)]">
            {toPercent(pTop)}
          </p>
          <p className="text-sm text-[var(--ink-muted)]">
            calibrated p_top · p_bot {toPercent(panel.pBot.value)}
          </p>
        </div>
        <IntervalBar
          legend="Model confidence"
          low={panel.pTop.intervalLow}
          high={panel.pTop.intervalHigh}
          point={pTop}
          accent="rgba(56, 130, 246, 0.18)"
          point_color="rgb(37, 99, 235)"
        />
        <IntervalBar
          legend={`Conformal · coverage ${toPercent(panel.conformal.coverage)} (α=${panel.conformal.alpha.toFixed(2)})`}
          low={panel.conformal.intervalLow}
          high={panel.conformal.intervalHigh}
          point={pTop}
          accent="rgba(234, 179, 8, 0.2)"
          point_color="rgb(202, 138, 4)"
        />
      </div>
    </>
  )
}

function IntervalBar({
  legend,
  low,
  high,
  point,
  accent,
  point_color,
}: {
  legend: string
  low: number
  high: number
  point: number
  accent: string
  point_color: string
}) {
  const safeLow = clamp01(low)
  const safeHigh = clamp01(high)
  const safePoint = clamp01(point)
  const left = `${safeLow * 100}%`
  const width = `${Math.max(0, safeHigh - safeLow) * 100}%`
  const tick = `${safePoint * 100}%`

  return (
    <div className="mt-5">
      <div className="flex items-center justify-between text-xs uppercase tracking-[0.22em] text-[var(--ink-muted)]">
        <span>{legend}</span>
        <span>{toPercent(safeLow)} – {toPercent(safeHigh)}</span>
      </div>
      <div className="relative mt-2 h-3 rounded-full bg-[rgba(15,23,42,0.06)]">
        <div
          className="absolute inset-y-0 rounded-full"
          style={{ left, width, backgroundColor: accent }}
        />
        <div
          className="absolute inset-y-[-2px] w-[2px] rounded-full"
          style={{ left: tick, backgroundColor: point_color }}
          aria-label="model probability marker"
        />
      </div>
    </div>
  )
}

function ConformalCard({ panel }: { panel: PredictionPanelResponse }) {
  const conformal = panel.conformal
  const label = conformal.label.replaceAll('_', ' ')
  const tone = labelTone(conformal.label)
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Shield className="size-5 text-[var(--accent-ink)]" />
          <p className="font-medium text-[var(--ink-strong)]">{label}</p>
        </div>
        <Badge className={tone}>{conformal.method}</Badge>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <KeyValue label="Coverage" value={toPercent(conformal.coverage)} />
        <KeyValue label="α" value={conformal.alpha.toFixed(2)} />
        <KeyValue label="Quantile q̂" value={conformal.quantile.toFixed(3)} />
        <KeyValue label="Group" value={conformal.groupKey || '—'} />
        <KeyValue label="Interval" value={`${toPercent(conformal.intervalLow)} – ${toPercent(conformal.intervalHigh)}`} />
        <KeyValue label="Prediction set" value={(conformal.predictionSet ?? []).join(', ') || '∅'} />
      </div>
    </div>
  )
}

function ShapBars({ contributions }: { contributions: PredictionContribution[] }) {
  const max = Math.max(0.01, ...contributions.map((c) => Math.abs(c.contribution)))
  return (
    <ul className="flex flex-col gap-3">
      {contributions.map((c) => {
        const ratio = Math.min(1.0, Math.abs(c.contribution) / max)
        const width = `${ratio * 100}%`
        const positive = c.contribution >= 0
        return (
          <li key={c.feature} className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.74)] p-4">
            <div className="flex items-center justify-between gap-3 text-sm">
              <span className="font-medium text-[var(--ink-strong)] truncate">{c.feature}</span>
              <span className={cn('font-semibold', positive ? 'text-emerald-700' : 'text-rose-700')}>
                {(c.contribution >= 0 ? '+' : '') + c.contribution.toFixed(3)}
              </span>
            </div>
            <div className="relative mt-2 h-3 rounded-full bg-[rgba(15,23,42,0.06)]">
              <div
                className={cn('absolute inset-y-0 rounded-full', positive ? 'left-1/2' : 'right-1/2')}
                style={{
                  width,
                  backgroundColor: positive ? 'rgba(16, 185, 129, 0.55)' : 'rgba(239, 68, 68, 0.55)',
                }}
              />
              <div className="absolute inset-y-[-2px] left-1/2 w-[2px] bg-[var(--ink-muted)]/40" />
            </div>
          </li>
        )
      })}
    </ul>
  )
}

function ReliabilityCurve({ bins }: { bins: ReliabilityBin[] }) {
  const width = 320
  const height = 220
  const pad = 28

  const xScale = (v: number) => pad + v * (width - 2 * pad)
  const yScale = (v: number) => height - pad - v * (height - 2 * pad)

  const points = bins.map((bin) => ({
    bin,
    x: xScale(bin.meanPredicted),
    y: yScale(bin.observedRate),
    count: bin.count,
    label: `${(bin.lowerBound * 100).toFixed(0)}-${(bin.upperBound * 100).toFixed(0)}%`,
  }))

  const path = points
    .map((p, idx) => `${idx === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`)
    .join(' ')

  const totalCount = bins.reduce((acc, bin) => acc + bin.count, 0) || 1

  return (
    <div className="flex flex-col gap-4">
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full max-w-md text-[var(--ink-muted)]">
        <line x1={pad} y1={height - pad} x2={width - pad} y2={pad} stroke="currentColor" strokeDasharray="4 4" strokeWidth={1} />
        <line x1={pad} y1={height - pad} x2={width - pad} y2={height - pad} stroke="currentColor" strokeWidth={1} />
        <line x1={pad} y1={pad} x2={pad} y2={height - pad} stroke="currentColor" strokeWidth={1} />
        {points.length > 1 ? (
          <path d={path} fill="none" stroke="rgb(37, 99, 235)" strokeWidth={2} />
        ) : null}
        {points.map((p, idx) => {
          const radius = 3 + (p.bin.count / totalCount) * 8
          return (
            <g key={`${p.label}-${idx}`}>
              <circle cx={p.x} cy={p.y} r={radius} fill="rgba(37, 99, 235, 0.65)" />
              <title>{`${p.label} · n=${p.bin.count}`}</title>
            </g>
          )
        })}
        <text x={width / 2} y={height - 6} textAnchor="middle" className="fill-current text-[10px] uppercase tracking-[0.24em]">
          Mean predicted p
        </text>
        <text
          x={10}
          y={height / 2}
          transform={`rotate(-90 10 ${height / 2})`}
          textAnchor="middle"
          className="fill-current text-[10px] uppercase tracking-[0.24em]"
        >
          Observed rate
        </text>
      </svg>
      <div className="flex items-center gap-2 text-xs text-[var(--ink-muted)]">
        <Sparkles className="size-3.5" />
        <span>Bubble size scales with the calibration bin count.</span>
      </div>
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

function MetadataTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <p className="text-[10px] font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-2 truncate font-medium text-[var(--ink-strong)]">{value}</p>
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

function clamp01(value: number) {
  if (!Number.isFinite(value)) return 0
  if (value < 0) return 0
  if (value > 1) return 1
  return value
}

function toPercent(value: number) {
  return `${(clamp01(value) * 100).toFixed(1)}%`
}

function labelTone(label: string) {
  switch (label) {
    case 'CONFIDENT_TOP':
      return 'border-emerald-200 bg-emerald-50 text-emerald-800'
    case 'CONFIDENT_BOT':
      return 'border-emerald-200 bg-emerald-50 text-emerald-800'
    case 'AMBIGUOUS':
      return 'border-amber-200 bg-amber-50 text-amber-800'
    case 'ANOMALOUS':
      return 'border-rose-200 bg-rose-50 text-rose-800'
    default:
      return ''
  }
}

function formatDateTime(value: string | null) {
  if (!value) return 'N/A'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
