import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Database,
  Radio,
  RefreshCcw,
  ScanSearch,
  ShieldCheck,
  UserCheck,
  Workflow,
} from 'lucide-react'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchHardRockScoreStreamStatus, fetchModelCallMonitor } from '@/features/live-studio/api'
import { ModelCallPipelineRow } from '@/features/live-studio/ModelCallPipelineRow'
import type { HardRockScoreStreamStatus, ModelCallMonitor, ModelCallTracking } from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_MS = 15_000
type Filter = 'ALL' | 'WAITING' | 'LIVE' | 'REVIEW' | 'CONFIRMED' | 'CONFLICT'

export function ModelPipelineRoute() {
  const [data, setData] = useState<ModelCallMonitor | null>(null)
  const [scoreStream, setScoreStream] = useState<HardRockScoreStreamStatus | null>(null)
  const [filter, setFilter] = useState<Filter>('ALL')
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  const load = useCallback(async (background: boolean) => {
    background ? setRefreshing(true) : setLoading(true)
    try {
      const [next, nextScoreStream] = await Promise.all([
        fetchModelCallMonitor(200),
        fetchHardRockScoreStreamStatus(),
      ])
      if (!mounted.current) return
      setData(next)
      setScoreStream(nextScoreStream)
      setError(null)
    } catch (nextError) {
      if (mounted.current) setError(nextError instanceof Error ? nextError.message : 'Unable to load model pipeline.')
    } finally {
      if (mounted.current) {
        setLoading(false)
        setRefreshing(false)
      }
    }
  }, [])

  useEffect(() => {
    void load(false)
    const interval = window.setInterval(() => void load(true), REFRESH_MS)
    return () => window.clearInterval(interval)
  }, [load])

  const calls = useMemo(() => (data?.calls ?? []).filter((call) => matchesFilter(call, filter)), [data?.calls, filter])

  return (
    <V3Shell
      title="Decision Pipeline"
      description="Event-by-event observability from model capture through score tracking, viewer grading, and protected system settlement."
      badges={<><Badge variant="accent">{data?.totalCalls ?? 0} tracked</Badge><Badge>Auto 15s</Badge></>}
      actions={<Button variant="secondary" onClick={() => void load(true)} disabled={loading || refreshing}><RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} /> Refresh</Button>}
    >
      {error ? <div className="mb-5 rounded-[18px] border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{error}</div> : null}

      <section className="admin-hero overflow-hidden rounded-[30px] border border-blue-300/15 p-5 text-white shadow-2xl shadow-black/20 sm:p-7">
        <div className="grid gap-6 xl:grid-cols-[1.15fr_0.85fr] xl:items-end">
          <div>
            <span className="inline-flex items-center gap-2 rounded-full border border-blue-300/20 bg-blue-300/10 px-3 py-1.5 text-[10px] font-semibold uppercase tracking-[0.22em] text-blue-200"><Workflow className="size-3.5" /> End-to-end trace</span>
            <h2 className="mt-5 max-w-3xl text-3xl font-semibold tracking-[-0.045em] sm:text-4xl">No match disappears into “awaiting settlement.”</h2>
            <p className="mt-4 max-w-3xl text-sm leading-6 text-slate-300">Every observed call states what has arrived, what gate it passed, what evidence is missing, and whether the visible grade came from the viewer or the protected result system.</p>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            <DarkMetric label="Waiting / scheduled" value={data?.scheduled ?? 0} />
            <DarkMetric label="Live tracking" value={data?.liveTracking ?? 0} />
            <DarkMetric label="Terminal review" value={data?.settlementReview ?? 0} />
            <DarkMetric label="Viewer-approved" value={data?.viewerApproved ?? 0} />
            <DarkMetric label="System-confirmed" value={data?.systemConfirmed ?? 0} />
            <DarkMetric label="Conflicts" value={data?.conflicts ?? 0} danger={(data?.conflicts ?? 0) > 0} />
          </div>
        </div>
      </section>

      <Card className="mt-5 overflow-hidden">
        <CardContent className="grid gap-4 p-5 md:grid-cols-[minmax(0,1fr)_auto] md:items-center">
          <div className="flex min-w-0 items-start gap-4">
            <span className={cn('mt-0.5 grid size-11 shrink-0 place-items-center rounded-2xl', scoreStream?.connected ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-800')}>
              <Radio className={cn('size-5', scoreStream?.connected && 'animate-pulse')} />
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <p className="font-semibold text-[var(--ink-strong)]">Hard Rock final-score path</p>
                <Badge className={scoreStream?.connected ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-amber-200 bg-amber-50 text-amber-900'}>{scoreStream?.connected ? 'Connected' : scoreStream?.enabled === false ? 'Disabled' : 'Reconnecting'}</Badge>
              </div>
              <p className="mt-1 text-sm leading-6 text-[var(--ink-muted)]">Registered event IDs stay subscribed after wagering closes. Odds may lock or disappear; score, games, and the explicit final signal continue independently.</p>
              {scoreStream?.lastError ? <p className="mt-2 text-xs font-medium text-rose-700">Latest transport note: {scoreStream.lastError}</p> : null}
            </div>
          </div>
          <div className="grid grid-cols-3 gap-2 md:min-w-[360px]">
            <StreamMetric label="Tracked" value={scoreStream?.trackedEvents ?? 0} />
            <StreamMetric label="Live now" value={scoreStream?.liveEvents ?? 0} />
            <StreamMetric label="Finals cached" value={scoreStream?.completedEventsCached ?? 0} />
          </div>
          <div className="md:col-span-2 flex flex-wrap gap-x-5 gap-y-1 border-t border-[var(--line)] pt-3 text-[11px] text-[var(--ink-muted)]">
            <span>Last score: {formatTimestamp(scoreStream?.lastScoreAt)}</span>
            <span>Connected: {formatTimestamp(scoreStream?.connectedAt)}</span>
            <span>Reconnects: {scoreStream?.reconnectCount ?? 0}</span>
          </div>
        </CardContent>
      </Card>

      <Card className="mt-5">
        <CardHeader>
          <Badge className="w-fit"><ShieldCheck className="mr-1 size-3" /> Truth boundaries</Badge>
          <CardTitle>What each pipeline stage means</CardTitle>
          <CardDescription>These stages explain both normal waiting and actionable gaps. Viewer grades remain deliberately isolated from settlement, ROI, and training labels.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-3 xl:grid-cols-6">
          <Stage icon={Clock3} label="Scheduled / feed wait" detail="Call saved; waiting for a linked live score observation." />
          <Stage icon={Radio} label="Live tracking" detail="Score feed is active and the match is not terminal." tone="live" />
          <Stage icon={ScanSearch} label="Terminal review" detail="Finish-like evidence exists but is not trusted enough yet." tone="warning" />
          <Stage icon={UserCheck} label="Viewer-approved" detail="Immediate provisional grade; no canonical mutation." tone="viewer" />
          <Stage icon={CheckCircle2} label="System-confirmed" detail="Trusted terminal score or archive resolved the winner." tone="good" />
          <Stage icon={AlertTriangle} label="Conflict" detail="Viewer and trusted result disagree; both remain auditable." tone="danger" />
        </CardContent>
      </Card>

      <Card className="mt-5">
        <CardHeader>
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <Badge variant="accent" className="w-fit"><Database className="mr-1 size-3" /> Match ledger</Badge>
              <CardTitle className="mt-2">Every match in the current simulation</CardTitle>
              <CardDescription>Newest model capture first. Open a row for the score timeline, frozen odds, model decision, matchup evidence, and viewer approval.</CardDescription>
            </div>
            <div className="flex max-w-full flex-wrap gap-2">
              {(['ALL', 'WAITING', 'LIVE', 'REVIEW', 'CONFIRMED', 'CONFLICT'] as const).map((value) => (
                <Button key={value} size="sm" variant={filter === value ? 'primary' : 'secondary'} onClick={() => setFilter(value)}>{labelForFilter(value)}</Button>
              ))}
            </div>
          </div>
        </CardHeader>
        <CardContent className="grid gap-2">
          {calls.length ? calls.map((call) => <ModelCallPipelineRow call={call} key={call.callId} to={`/admin/pipeline/${call.callId}`} />) : (
            <div className="rounded-[18px] border border-dashed border-[var(--line-strong)] bg-slate-50 p-6 text-center text-sm text-[var(--ink-muted)]">{loading ? 'Loading tracked matches…' : 'No matches are in this stage.'}</div>
          )}
        </CardContent>
      </Card>
    </V3Shell>
  )
}

function DarkMetric({ danger = false, label, value }: { danger?: boolean; label: string; value: number }) {
  return <div className={cn('rounded-[18px] border border-white/10 bg-white/[0.05] p-4', danger && 'border-rose-400/30 bg-rose-400/10')}><p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-slate-400">{label}</p><p className={cn('mt-2 font-mono text-2xl font-bold', danger ? 'text-rose-300' : 'text-white')}>{value}</p></div>
}

function StreamMetric({ label, value }: { label: string; value: number }) {
  return <div className="rounded-[16px] border border-[var(--line)] bg-slate-50 px-3 py-3 text-center"><p className="text-[9px] font-bold uppercase tracking-[0.14em] text-[var(--ink-muted)]">{label}</p><p className="mt-1 font-mono text-xl font-bold text-[var(--ink-strong)]">{value}</p></div>
}

function formatTimestamp(value: string | null | undefined) {
  if (!value) return 'Not seen yet'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', second: '2-digit' })
}

function Stage({ detail, icon: Icon, label, tone = 'neutral' }: { detail: string; icon: typeof Clock3; label: string; tone?: 'neutral' | 'live' | 'warning' | 'viewer' | 'good' | 'danger' }) {
  const classes = { neutral: 'bg-slate-100 text-slate-700', live: 'bg-rose-100 text-rose-800', warning: 'bg-amber-100 text-amber-900', viewer: 'bg-violet-100 text-violet-800', good: 'bg-emerald-100 text-emerald-800', danger: 'bg-rose-100 text-rose-900' }
  return <div className="rounded-[18px] border border-[var(--line)] bg-white/70 p-4"><span className={cn('grid size-8 place-items-center rounded-xl', classes[tone])}><Icon className="size-4" /></span><p className="mt-3 text-xs font-bold text-[var(--ink-strong)]">{label}</p><p className="mt-1 text-[11px] leading-5 text-[var(--ink-muted)]">{detail}</p></div>
}

function matchesFilter(call: ModelCallTracking, filter: Filter) {
  if (filter === 'ALL') return true
  if (filter === 'WAITING') return call.pipelineStage === 'SCHEDULED' || call.pipelineStage === 'WAITING_FOR_FEED'
  if (filter === 'LIVE') return call.pipelineStage === 'LIVE_MONITORING'
  if (filter === 'REVIEW') return call.pipelineStage === 'SETTLEMENT_REVIEW' || call.pipelineStage === 'VIEWER_APPROVED'
  if (filter === 'CONFIRMED') return call.pipelineStage === 'SYSTEM_CONFIRMED'
  return call.pipelineStage === 'RESULT_CONFLICT'
}

function labelForFilter(filter: Filter) {
  return filter === 'ALL' ? 'All' : filter === 'WAITING' ? 'Waiting' : filter === 'LIVE' ? 'Live' : filter === 'REVIEW' ? 'Review' : filter === 'CONFIRMED' ? 'Confirmed' : 'Conflicts'
}
