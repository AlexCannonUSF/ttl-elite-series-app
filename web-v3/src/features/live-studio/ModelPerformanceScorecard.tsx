import { useCallback, useEffect, useRef, useState } from 'react'
import {
  CheckCircle2,
  CircleDotDashed,
  Gauge,
  MinusCircle,
  RefreshCcw,
  Target,
  XCircle,
} from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchModelCallScorecard } from '@/features/live-studio/api'
import type { ModelCallResult, ModelCallScorecard } from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 30_000

export function ModelPerformanceScorecard() {
  const [data, setData] = useState<ModelCallScorecard | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const load = useCallback(async (background: boolean) => {
    const controller = new AbortController()
    if (mountedRef.current) {
      setRefreshing(background)
      if (!background) setLoading(true)
    }
    try {
      const next = await fetchModelCallScorecard(40, controller.signal)
      if (!mountedRef.current) return
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) return
      setError(nextError instanceof Error ? nextError.message : 'Unable to load model performance.')
    } finally {
      if (!mountedRef.current) return
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    void load(false)
    const interval = window.setInterval(() => void load(true), REFRESH_INTERVAL_MS)
    return () => window.clearInterval(interval)
  }, [load])

  const hasSettled = (data?.settledCalls ?? 0) > 0

  return (
    <Card className="mt-5 overflow-hidden">
      <CardHeader className="border-b border-[var(--line)]">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <Badge variant="accent" className="w-fit">
              <Target className="mr-1 size-3" /> Every match · no bet required
            </Badge>
            <CardTitle className="mt-2">Model winner scorecard</CardTitle>
            <CardDescription className="mt-2 max-w-3xl">
              Grades the higher-probability winner on every observed match. This is prediction accuracy—not betting ROI—and a value bet can be on a different side.
            </CardDescription>
          </div>
          <Button variant="secondary" size="sm" onClick={() => void load(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-3.5', refreshing && 'animate-spin')} />
            Refresh
          </Button>
        </div>
      </CardHeader>

      <CardContent className="grid gap-5 p-5 sm:p-6">
        <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-5">
          <ScoreMetric
            icon={Target}
            label="Winner accuracy"
            value={hasSettled ? `${formatNumber(data?.accuracyPct, 1)}%` : 'Collecting'}
            detail={hasSettled ? `${data?.correct ?? 0} correct · ${data?.incorrect ?? 0} wrong` : 'Starts when results settle'}
            tone={hasSettled && (data?.accuracyPct ?? 0) >= 55 ? 'positive' : 'neutral'}
          />
          <ScoreMetric
            icon={CheckCircle2}
            label="Model record"
            value={`${data?.correct ?? 0}–${data?.incorrect ?? 0}`}
            detail={`${data?.settledCalls ?? 0} finished · ${data?.noLean ?? 0} no lean`}
            tone="neutral"
          />
          <ScoreMetric
            icon={Gauge}
            label="Pregame only"
            value={(data?.pregameSettled ?? 0) > 0 ? `${formatNumber(data?.pregameAccuracyPct, 1)}%` : '—'}
            detail={`${data?.pregameCorrect ?? 0}/${data?.pregameSettled ?? 0} correct`}
            tone="neutral"
          />
          <ScoreMetric
            icon={CircleDotDashed}
            label="Awaiting results"
            value={String(data?.awaitingResult ?? 0)}
            detail={`${data?.totalCalls ?? 0} matches observed`}
            tone="neutral"
          />
          <ScoreMetric
            icon={Gauge}
            label="Confidence quality"
            value={data?.brierScore == null ? '—' : data.brierScore.toFixed(3)}
            detail={data?.brierScore == null ? 'Brier score after settlement' : `Brier ↓ · avg call ${formatNumber(data.averageConfidencePct, 1)}%`}
            tone={data?.brierScore != null && data.brierScore <= 0.24 ? 'positive' : 'neutral'}
          />
        </div>

        {error ? (
          <div className="rounded-[16px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800" role="alert">
            {error}
          </div>
        ) : null}

        {!loading && (data?.recentResults.length ?? 0) === 0 ? (
          <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-slate-50/70 px-5 py-7 text-center">
            <CircleDotDashed className="mx-auto size-6 text-emerald-700" />
            <p className="mt-3 font-semibold text-[var(--ink-strong)]">Fresh simulation ready</p>
            <p className="mx-auto mt-1 max-w-xl text-sm text-[var(--ink-muted)]">
              Calls are being frozen as matches appear. Each completed match will land here with our win probability, fair odds, the Hard Rock price, final winner, and whether a paper bet was placed.
            </p>
          </div>
        ) : null}

        {(data?.recentResults.length ?? 0) > 0 ? (
          <div>
            <div className="mb-3 flex items-center justify-between gap-3">
              <p className="text-xs font-semibold uppercase tracking-[0.16em] text-[var(--ink-muted)]">Latest completed matches</p>
              <p className="text-xs text-[var(--ink-muted)]">Newest first · current simulation only</p>
            </div>
            <div className="hide-scrollbar grid max-h-[520px] gap-2 overflow-y-auto pr-1">
              {data?.recentResults.map((result) => <CompletedCallRow key={`${result.callId}-${result.matchId}`} result={result} />)}
            </div>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

function CompletedCallRow({ result }: { result: ModelCallResult }) {
  const correct = result.outcome === 'CORRECT'
  const noLean = result.outcome === 'NO_LEAN'
  const OutcomeIcon = noLean ? MinusCircle : correct ? CheckCircle2 : XCircle
  return (
    <div className="grid gap-3 rounded-[20px] border border-[var(--line)] bg-white/70 p-4 lg:grid-cols-[minmax(220px,1.3fr)_minmax(190px,1fr)_minmax(190px,0.95fr)_auto] lg:items-center">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cn(
            'inline-flex items-center gap-1 rounded-full px-2 py-1 text-[10px] font-bold uppercase tracking-[0.13em]',
            noLean ? 'bg-slate-100 text-slate-700' : correct ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800',
          )}>
            <OutcomeIcon className="size-3" /> {noLean ? 'No lean' : correct ? 'Correct' : 'Wrong'}
          </span>
          <span className="text-[10px] font-semibold uppercase tracking-[0.12em] text-[var(--ink-muted)]">
            {result.captureType === 'PREMATCH_CLOSE' ? 'Pregame close' : 'First live read'}
          </span>
          <span className={cn(
            'text-[10px] font-bold uppercase tracking-[0.12em]',
            result.paperPickPlaced ? 'text-amber-700' : 'text-slate-500',
          )}>
            {result.paperPickPlaced ? 'Paper pick placed' : 'No bet'}
          </span>
        </div>
        <p className="mt-2 truncate font-semibold text-[var(--ink-strong)]">{result.player1Name} vs {result.player2Name}</p>
        <p className="mt-1 truncate text-xs text-[var(--ink-muted)]">{result.competitionName ?? 'Table Tennis'} · {formatDate(result.matchDateIso)}</p>
      </div>

      <div>
        <p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">Our winner call</p>
        <p className="mt-1 truncate text-sm font-bold text-[var(--ink-strong)]">{result.predictedWinnerName ?? '50/50 · no lean'}</p>
        <p className="mt-1 font-mono text-xs text-[var(--ink-muted)]">
          {formatProbability(result.modelProbability)} · fair {formatAmerican(result.modelFairAmericanOdds)}
        </p>
      </div>

      <div>
        <p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">Hard Rock at capture</p>
        <p className="mt-1 font-mono text-sm font-bold text-[var(--ink-strong)]">{formatAmerican(result.hardRockAmericanOdds)}</p>
        <p className="mt-1 text-xs text-[var(--ink-muted)]">{formatProbability(result.hardRockNoVigProbability)} no-vig</p>
      </div>

      <div className="min-w-[150px] rounded-[14px] bg-slate-50 px-3 py-2 lg:text-right">
        <p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">Actual winner</p>
        <p className="mt-1 truncate text-sm font-bold text-[var(--ink-strong)]">{result.actualWinnerName}</p>
        <p className="mt-1 font-mono text-xs text-[var(--ink-muted)]">Final {result.score}</p>
      </div>
    </div>
  )
}

function ScoreMetric({
  detail,
  icon: Icon,
  label,
  tone,
  value,
}: {
  detail: string
  icon: typeof Target
  label: string
  tone: 'neutral' | 'positive'
  value: string
}) {
  return (
    <div className="rounded-[18px] border border-[var(--line)] bg-slate-50/75 p-4">
      <div className="flex items-center gap-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">
        <Icon className={cn('size-3.5', tone === 'positive' && 'text-emerald-700')} /> {label}
      </div>
      <p className={cn('mt-3 font-mono text-xl font-bold', tone === 'positive' ? 'text-emerald-700' : 'text-[var(--ink-strong)]')}>{value}</p>
      <p className="mt-1 truncate text-[10px] text-[var(--ink-muted)]">{detail}</p>
    </div>
  )
}

function formatNumber(value: number | null | undefined, digits: number) {
  return Number.isFinite(value) ? Number(value).toFixed(digits) : '0.0'
}

function formatProbability(value: number | null | undefined) {
  return value == null || !Number.isFinite(value) ? 'N/A' : `${(value * 100).toFixed(1)}%`
}

function formatAmerican(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return 'N/A'
  return value > 0 ? `+${value}` : String(value)
}

function formatDate(value: string | null) {
  if (!value) return 'Date unavailable'
  const parsed = new Date(`${value}T12:00:00`)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(parsed)
}
