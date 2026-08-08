import { useCallback, useEffect, useRef, useState } from 'react'
import {
  BadgeDollarSign,
  CheckCircle2,
  CircleDotDashed,
  Eye,
  Gauge,
  MinusCircle,
  RefreshCcw,
  Target,
  UserCheck,
  XCircle,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchModelCallMonitor, fetchModelCallScorecard } from '@/features/live-studio/api'
import { ModelCallPipelineRow } from '@/features/live-studio/ModelCallPipelineRow'
import type { ModelCallMonitor, ModelCallResult, ModelCallScorecard } from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 30_000

export function ModelPerformanceScorecard() {
  const [data, setData] = useState<ModelCallScorecard | null>(null)
  const [monitor, setMonitor] = useState<ModelCallMonitor | null>(null)
  const [reviewOnly, setReviewOnly] = useState(false)
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
      const [next, nextMonitor] = await Promise.all([
        fetchModelCallScorecard(40, controller.signal),
        fetchModelCallMonitor(200, controller.signal),
      ])
      if (!mountedRef.current) return
      setData(next)
      setMonitor(nextMonitor)
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
  const visibleCalls = [...(monitor?.calls ?? [])]
    .filter((call) => !reviewOnly || call.canApprove)
    .sort(compareViewerCalls)

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
              Grades the higher-probability winner on every observed match and tracks a separate flat-$1 return at the captured Hard Rock price. The real paper trader still uses its own value and risk gates.
            </CardDescription>
          </div>
          <Button variant="secondary" size="sm" onClick={() => void load(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-3.5', refreshing && 'animate-spin')} />
            Refresh
          </Button>
        </div>
      </CardHeader>

      <CardContent className="grid gap-5 p-5 sm:p-6">
        <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-7">
          <ScoreMetric
            icon={UserCheck}
            label="Your live progress"
            value={(data?.viewerGradedCalls ?? 0) > 0 ? `${formatNumber(data?.viewerAccuracyPct, 1)}%` : 'Ready'}
            detail={`${data?.viewerCorrect ?? 0}–${data?.viewerIncorrect ?? 0} · ${data?.viewerApprovedPending ?? 0} provisional`}
            tone={(data?.viewerGradedCalls ?? 0) > 0 && (data?.viewerAccuracyPct ?? 0) >= 55 ? 'positive' : 'neutral'}
          />
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
            icon={BadgeDollarSign}
            label="$1 every model lean"
            value={
              (data?.flatStakeSettled ?? 0) > 0
                ? `${formatSignedNumber(data?.flatStakeRoiPct)}%`
                : 'Collecting'
            }
            detail={
              (data?.flatStakeSettled ?? 0) > 0
                ? `${formatSignedMoney(data?.flatStakeNetProfit)} net · ${data?.flatStakeWins ?? 0}–${data?.flatStakeLosses ?? 0}`
                : 'Flat-stake ROI after results settle'
            }
            tone={(data?.flatStakeRoiPct ?? 0) > 0 ? 'positive' : 'neutral'}
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

        {!loading && (monitor?.calls.length ?? 0) > 0 ? (
          <div>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
              <div>
                <p className="text-xs font-semibold uppercase tracking-[0.16em] text-[var(--ink-muted)]">Every observed match</p>
                <p className="mt-1 text-xs text-[var(--ink-muted)]">Open any match to see its score, odds, decision, evidence path, and exact settlement blocker.</p>
              </div>
              <div className="flex gap-2">
                <Button variant={reviewOnly ? 'secondary' : 'primary'} size="sm" onClick={() => setReviewOnly(false)}>
                  <Eye className="size-3.5" /> All {monitor?.totalCalls ?? 0}
                </Button>
                <Button variant={reviewOnly ? 'primary' : 'secondary'} size="sm" onClick={() => setReviewOnly(true)}>
                  <UserCheck className="size-3.5" /> Needs review {monitor?.calls.filter((call) => call.canApprove).length ?? 0}
                </Button>
              </div>
            </div>
            {visibleCalls.length ? (
              <div className="hide-scrollbar grid max-h-[620px] gap-2 overflow-y-auto pr-1">
                {visibleCalls.map((call) => (
                  <ModelCallPipelineRow call={call} key={call.callId} to={`/user/tracking/${call.callId}`} />
                ))}
              </div>
            ) : (
              <div className="rounded-[18px] border border-dashed border-[var(--line-strong)] bg-slate-50/70 px-5 py-6 text-center text-sm text-[var(--ink-muted)]">
                No unresolved matches need your review right now.
              </div>
            )}
          </div>
        ) : null}

        {!loading && (monitor?.calls.length ?? 0) === 0 ? (
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
  const flatStakeNet = flatStakeProfit(result)
  const OutcomeIcon = noLean ? MinusCircle : correct ? CheckCircle2 : XCircle
  return (
    <Link to={`/user/tracking/${result.callId}`} className="grid gap-3 rounded-[20px] border border-[var(--line)] bg-white/70 p-4 transition hover:border-emerald-300 hover:bg-white lg:grid-cols-[minmax(220px,1.3fr)_minmax(190px,1fr)_minmax(190px,0.95fr)_auto] lg:items-center">
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
        {flatStakeNet == null ? null : (
          <p
            className={cn(
              'mt-1 font-mono text-xs font-semibold',
              flatStakeNet > 0 ? 'text-emerald-700' : 'text-rose-700'
            )}
          >
            Flat $1 {formatSignedMoney(flatStakeNet)}
          </p>
        )}
      </div>

      <div className="min-w-[150px] rounded-[14px] bg-slate-50 px-3 py-2 lg:text-right">
        <p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">Actual winner</p>
        <p className="mt-1 truncate text-sm font-bold text-[var(--ink-strong)]">{result.actualWinnerName}</p>
        <p className="mt-1 font-mono text-xs text-[var(--ink-muted)]">Final {result.score}</p>
      </div>
    </Link>
  )
}

function compareViewerCalls(left: ModelCallMonitor['calls'][number], right: ModelCallMonitor['calls'][number]) {
  const priority: Record<string, number> = {
    LIVE_MONITORING: 0,
    RESULT_CONFLICT: 1,
    SETTLEMENT_REVIEW: 2,
    VIEWER_APPROVED: 3,
    WAITING_FOR_FEED: 4,
    SCHEDULED: 5,
    SYSTEM_CONFIRMED: 6,
  }
  const stageDelta = (priority[left.pipelineStage] ?? 9) - (priority[right.pipelineStage] ?? 9)
  if (stageDelta !== 0) return stageDelta
  const leftTime = Date.parse(left.startTimeIso ?? left.capturedAt ?? '')
  const rightTime = Date.parse(right.startTimeIso ?? right.capturedAt ?? '')
  if (Number.isFinite(leftTime) && Number.isFinite(rightTime) && leftTime !== rightTime) {
    return left.pipelineStage === 'SYSTEM_CONFIRMED' ? rightTime - leftTime : leftTime - rightTime
  }
  return right.callId - left.callId
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

function flatStakeProfit(result: ModelCallResult) {
  const odds = result.hardRockAmericanOdds
  if (result.outcome === 'NO_LEAN' || odds == null || !Number.isFinite(odds) || odds === 0) return null
  if (result.outcome !== 'CORRECT') return -1
  return odds > 0 ? odds / 100 : 100 / Math.abs(odds)
}

function formatSignedNumber(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '0.0'
  return `${value > 0 ? '+' : ''}${value.toFixed(1)}`
}

function formatSignedMoney(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value)) return '$0.00'
  return `${value > 0 ? '+' : value < 0 ? '−' : ''}$${Math.abs(value).toFixed(2)}`
}

function formatDate(value: string | null) {
  if (!value) return 'Date unavailable'
  const parsed = new Date(`${value}T12:00:00`)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' }).format(parsed)
}
