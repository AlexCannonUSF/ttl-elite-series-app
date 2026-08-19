import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  ArrowLeft,
  BarChart3,
  Check,
  CheckCircle2,
  Clock3,
  Database,
  Eye,
  RefreshCcw,
  Target,
  UserCheck,
  Workflow,
} from 'lucide-react'
import { Link, useLocation, useParams } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  approveModelCall,
  fetchMatchTimeline,
  fetchMatchupAnalysis,
  fetchModelCallTracking,
} from '@/features/live-studio/api'
import {
  formatAmerican,
  formatProbability,
  formatTime,
  pretty,
  stagePresentation,
} from '@/features/live-studio/ModelCallPipelineRow'
import { probabilityToAmericanOdds } from '@/features/live-studio/marketMath'
import type { MatchupAnalysis, ModelCallTracking, TrackedMatchObservation } from '@/features/live-studio/types'
import { cn } from '@/lib/utils'

const REFRESH_MS = 15_000

export function ModelCallTrackingRoute() {
  const location = useLocation()
  const adminMode = location.pathname.startsWith('/admin/')
  const { callId } = useParams()
  const parsedCallId = Number(callId)
  const [call, setCall] = useState<ModelCallTracking | null>(null)
  const [timeline, setTimeline] = useState<TrackedMatchObservation[]>([])
  const [matchup, setMatchup] = useState<MatchupAnalysis | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  const load = useCallback(async (background: boolean) => {
    if (!Number.isFinite(parsedCallId)) {
      setError('This tracking link is missing a valid model call id.')
      setLoading(false)
      return
    }
    background ? setRefreshing(true) : setLoading(true)
    try {
      const nextCall = await fetchModelCallTracking(parsedCallId)
      if (!mounted.current) return
      setCall(nextCall)
      setError(null)
      setLoading(false)
      // Score evidence is the time-sensitive part of this page. Render it as
      // soon as it arrives instead of holding it behind the heavier matchup
      // analytics request (which can take several seconds on a cold cache).
      const timelineRequest = fetchMatchTimeline(nextCall.eventKey)
        .then((nextTimeline) => { if (mounted.current) setTimeline(nextTimeline) })
        .catch(() => { if (mounted.current) setTimeline([]) })
      const matchupRequest = (nextCall.player1Id != null && nextCall.player2Id != null
        ? fetchMatchupAnalysis(nextCall.player1Id, nextCall.player2Id)
        : Promise.resolve(null))
        .then((nextMatchup) => { if (mounted.current) setMatchup(nextMatchup) })
        .catch(() => { if (mounted.current) setMatchup(null) })
      await Promise.allSettled([timelineRequest, matchupRequest])
    } catch (nextError) {
      if (mounted.current) setError(message(nextError))
    } finally {
      if (mounted.current) {
        setLoading(false)
        setRefreshing(false)
      }
    }
  }, [parsedCallId])

  useEffect(() => {
    void load(false)
    const interval = window.setInterval(() => void load(true), REFRESH_MS)
    return () => window.clearInterval(interval)
  }, [load])

  const status = stagePresentation(call?.pipelineStage ?? 'SCHEDULED')
  const StatusIcon = status.icon

  return (
    <V3Shell
      title="Match Progress"
      description="A complete, auditable view of the model call, live score evidence, your provisional grade, and trusted settlement."
      badges={call ? <Badge variant="accent">{call.pipelineLabel}</Badge> : <Badge>Loading</Badge>}
      actions={(
        <>
          <Button variant="secondary" size="sm" asChild><Link className="!text-slate-800" to={adminMode ? '/admin/pipeline' : '/user'}><ArrowLeft className="size-4" /> {adminMode ? 'Pipeline' : 'Live board'}</Link></Button>
          <Button variant="secondary" size="sm" disabled={loading || refreshing} onClick={() => void load(true)}>
            <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} /> Refresh
          </Button>
        </>
      )}
    >
      {error ? <div className="rounded-[20px] border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800">{error}</div> : null}
      {call ? (
        <>
          <section className="overflow-hidden rounded-[30px] border border-emerald-300/15 bg-[linear-gradient(145deg,#0b2a24,#071b18)] p-5 text-white shadow-2xl shadow-emerald-950/15 sm:p-7">
            <div className="grid gap-6 xl:grid-cols-[1.2fr_0.8fr] xl:items-end">
              <div>
                <div className="flex flex-wrap items-center gap-2">
                  <span className={cn('inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-[10px] font-bold uppercase tracking-[0.16em]', status.className)}>
                    <StatusIcon className={cn('size-3.5', call.pipelineStage === 'LIVE_MONITORING' && 'animate-pulse')} /> {call.pipelineLabel}
                  </span>
                  <span className="text-xs font-semibold uppercase tracking-[0.16em] text-emerald-200">{call.competitionName ?? 'Table Tennis'}</span>
                </div>
                <h2 className="mt-4 text-3xl font-semibold tracking-[-0.045em] sm:text-4xl">{call.player1Name} vs {call.player2Name}</h2>
                <p className="mt-3 max-w-3xl text-sm leading-6 text-slate-300">{call.pipelineDetail}</p>
                <div className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-xs text-slate-400">
                  <span>Scheduled {formatTime(call.startTimeIso)}</span>
                  <span>Last evidence {formatTimestamp(call.latestObservedAt)}</span>
                  <span>Source {call.latestSource ?? call.source ?? 'Waiting'}</span>
                </div>
              </div>
              <div className="rounded-[24px] border border-white/10 bg-white/[0.06] p-5">
                <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-emerald-300">Latest score</p>
                <p className="mt-2 font-mono text-5xl font-bold tracking-[-0.06em]">{call.systemScore ?? call.latestScore ?? '—'}</p>
                <p className="mt-2 text-sm text-slate-300">{pretty(call.latestPhase ?? call.pipelineStage)}</p>
              </div>
            </div>
          </section>

          <PipelineCard call={call} />

          <section className="mt-5 grid gap-5 xl:grid-cols-[1fr_0.9fr]">
            <PriceCard call={call} />
            <DecisionCard call={call} />
          </section>

          <section className="mt-5 grid gap-5 xl:grid-cols-[1.05fr_0.95fr]">
            <ViewerApprovalCard call={call} key={call.callId} onApproved={(next) => { setCall(next); void load(true) }} />
            <EvidenceCard call={call} timeline={timeline} />
          </section>

          <MatchupCard analysis={matchup} call={call} />
        </>
      ) : loading ? (
        <div className="grid min-h-[420px] place-items-center text-sm text-[var(--ink-muted)]">Loading complete match pipeline…</div>
      ) : null}
    </V3Shell>
  )
}

function PipelineCard({ call }: { call: ModelCallTracking }) {
  const steps = useMemo(() => pipelineSteps(call), [call])
  return (
    <Card className="mt-5">
      <CardHeader>
        <Badge variant="accent" className="w-fit"><Workflow className="mr-1 size-3" /> Decision pipeline</Badge>
        <CardTitle>Exactly where this match is now</CardTitle>
        <CardDescription>Each gate stays visible. Viewer approval supplies a provisional grade; only trusted result evidence completes official settlement.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 md:grid-cols-5">
        {steps.map((step, index) => (
          <div className={cn('relative rounded-[18px] border p-4', step.complete ? 'border-emerald-200 bg-emerald-50/70' : step.current ? 'border-amber-300 bg-amber-50/80' : 'border-[var(--line)] bg-slate-50/70')} key={step.label}>
            <div className="flex items-center justify-between">
              <span className="font-mono text-[10px] font-bold text-[var(--ink-muted)]">0{index + 1}</span>
              {step.complete ? <CheckCircle2 className="size-4 text-emerald-700" /> : step.current ? <Clock3 className="size-4 text-amber-700" /> : <span className="size-2 rounded-full bg-slate-300" />}
            </div>
            <p className="mt-3 text-sm font-bold text-[var(--ink-strong)]">{step.label}</p>
            <p className="mt-1 text-xs leading-5 text-[var(--ink-muted)]">{step.detail}</p>
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

function PriceCard({ call }: { call: ModelCallTracking }) {
  const predictedP1 = call.predictedWinnerPlayerId === call.player1Id
  const p1Model = call.modelProbability == null ? null : predictedP1 ? call.modelProbability : 1 - call.modelProbability
  const p2Model = p1Model == null ? null : 1 - p1Model
  const p1Book = predictedP1 ? call.hardRockAmericanOdds : call.opponentHardRockAmericanOdds
  const p2Book = predictedP1 ? call.opponentHardRockAmericanOdds : call.hardRockAmericanOdds
  return (
    <Card>
      <CardHeader>
        <Badge className="w-fit"><BarChart3 className="mr-1 size-3" /> Captured price</Badge>
        <CardTitle>Our probability vs. Hard Rock</CardTitle>
        <CardDescription>Our fair price has no sportsbook margin. Hard Rock’s two prices include a {call.hardRockMarginPct?.toFixed(2) ?? '—'}% total hold.</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="overflow-hidden rounded-[20px] border border-[var(--line)] bg-white/70">
          <PriceRow name={call.player1Name} model={p1Model} fair={p1Model == null ? null : probabilityToAmericanOdds(p1Model)} book={p1Book} selected={predictedP1} />
          <PriceRow name={call.player2Name} model={p2Model} fair={p2Model == null ? null : probabilityToAmericanOdds(p2Model)} book={p2Book} selected={!predictedP1 && call.predictedWinnerPlayerId != null} />
        </div>
        <p className="mt-3 text-xs leading-5 text-[var(--ink-muted)]">
          The comparison removes Hard Rock’s hold before measuring market belief. Edge and recommendations are based on the offered break-even probability, while fair odds show the model’s no-margin price.
        </p>
      </CardContent>
    </Card>
  )
}

function PriceRow({ book, fair, model, name, selected }: { book: number | null; fair: number | null; model: number | null; name: string; selected: boolean }) {
  return (
    <div className="grid grid-cols-[1fr_repeat(3,minmax(70px,0.55fr))] items-center gap-2 border-b border-[var(--line)] px-4 py-4 last:border-0">
      <div className="min-w-0"><p className="truncate font-semibold text-[var(--ink-strong)]">{name}</p>{selected ? <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-emerald-700">Model winner</span> : null}</div>
      <MetricCell label="Our chance" value={formatProbability(model)} />
      <MetricCell label="Fair odds" value={formatAmerican(fair)} />
      <MetricCell label="Hard Rock" value={formatAmerican(book)} />
    </div>
  )
}

function DecisionCard({ call }: { call: ModelCallTracking }) {
  return (
    <Card>
      <CardHeader>
        <Badge variant="accent" className="w-fit"><Target className="mr-1 size-3" /> Model decision</Badge>
        <CardTitle>{call.predictedWinnerName ?? 'No directional lean'}</CardTitle>
        <CardDescription>The winner call is recorded for every match even when the staking gate correctly chooses not to place a bet.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 sm:grid-cols-2">
        <DetailMetric label="Winner probability" value={formatProbability(call.modelProbability)} />
        <DetailMetric label="Model fair odds" value={formatAmerican(call.modelFairAmericanOdds)} />
        <DetailMetric label="Bet decision" value={pretty(call.decisionStatus ?? 'Recorded')} />
        <DetailMetric label="Decision reason" value={pretty(call.decisionReason ?? 'Not supplied')} />
        <DetailMetric label="Recommended" value={call.recommendedAtCapture ? 'Yes' : 'No'} />
        <DetailMetric label="Paper position" value={call.paperPickPlaced ? 'Placed' : 'No bet'} />
      </CardContent>
    </Card>
  )
}

function ViewerApprovalCard({ call, onApproved }: { call: ModelCallTracking; onApproved: (next: ModelCallTracking) => void }) {
  const [winnerId, setWinnerId] = useState<number | null>(call.viewerWinnerPlayerId)
  const [score, setScore] = useState(call.viewerScore ?? (call.completionSignalSeen ? call.latestScore ?? '' : ''))
  const [note, setNote] = useState(call.viewerNote ?? '')
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const submit = async () => {
    if (winnerId == null) {
      setNotice('Choose the winner you personally observed first.')
      return
    }
    setSaving(true)
    try {
      const next = await approveModelCall(call.callId, { winnerPlayerId: winnerId, score, note, reviewer: 'USER' })
      setNotice('Your provisional grade is saved. Trusted settlement and training truth were not changed.')
      onApproved(next)
    } catch (error) {
      setNotice(message(error))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className={cn(call.canApprove && 'border-violet-200')}>
      <CardHeader>
        <Badge className="w-fit border-violet-200 bg-violet-50 text-violet-800"><UserCheck className="mr-1 size-3" /> Viewer grade</Badge>
        <CardTitle>{call.viewerReviewedAt ? 'Your provisional result is recorded' : 'Approve the result you watched'}</CardTitle>
        <CardDescription>
          Use this after you personally see the final result. It updates your viewing scorecard immediately but cannot settle a bet, rewrite match truth, or train the model.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {call.systemWinnerPlayerId != null ? (
          <div className="rounded-[18px] border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-900">
            Trusted result: <strong>{call.systemWinnerName}</strong> · {call.systemScore ?? 'score unavailable'}. Viewer input is closed because the system result is already accepted.
          </div>
        ) : (
          <>
            <div className="grid gap-2 sm:grid-cols-2">
              <WinnerButton active={winnerId === call.player1Id} name={call.player1Name} onClick={() => setWinnerId(call.player1Id)} />
              <WinnerButton active={winnerId === call.player2Id} name={call.player2Name} onClick={() => setWinnerId(call.player2Id)} />
            </div>
            <label className="grid gap-1.5 text-xs font-semibold text-[var(--ink-muted)]">
              Final score you observed
              <input className="rounded-2xl border border-[var(--line-strong)] bg-white px-4 py-3 text-sm text-[var(--ink-strong)] outline-none focus:border-violet-400" maxLength={80} onChange={(event) => setScore(event.target.value)} placeholder="Example: 3:1" value={score} />
            </label>
            <label className="grid gap-1.5 text-xs font-semibold text-[var(--ink-muted)]">
              Optional note
              <textarea className="min-h-20 resize-y rounded-2xl border border-[var(--line-strong)] bg-white px-4 py-3 text-sm text-[var(--ink-strong)] outline-none focus:border-violet-400" maxLength={400} onChange={(event) => setNote(event.target.value)} placeholder="Where you verified it or anything unusual" value={note} />
            </label>
            <Button onClick={() => void submit()} disabled={saving || winnerId == null}>
              <Check className="size-4" /> {saving ? 'Saving provisional grade…' : call.viewerReviewedAt ? 'Save corrected viewer grade' : 'Approve observed winner'}
            </Button>
          </>
        )}
        {notice ? <p className="rounded-[14px] bg-slate-100 px-3 py-2 text-xs text-slate-700">{notice}</p> : null}
      </CardContent>
    </Card>
  )
}

function EvidenceCard({ call, timeline }: { call: ModelCallTracking; timeline: TrackedMatchObservation[] }) {
  const recent = [...timeline].reverse().slice(0, 8)
  return (
    <Card>
      <CardHeader>
        <Badge className="w-fit"><Database className="mr-1 size-3" /> Score evidence</Badge>
        <CardTitle>What the system has actually seen</CardTitle>
        <CardDescription>{timeline.length} observations linked to this event. Newest evidence is shown first.</CardDescription>
      </CardHeader>
      <CardContent className="grid max-h-[440px] gap-2 overflow-y-auto pr-1">
        {recent.length ? recent.map((item) => (
          <div className="grid grid-cols-[auto_1fr_auto] items-center gap-3 rounded-[16px] border border-[var(--line)] bg-white/70 px-3 py-3" key={item.id ?? `${item.observedAt}-${item.liveScore}`}>
            <span className={cn('size-2 rounded-full', item.matchCompleted || item.resulted ? 'bg-emerald-500' : item.live ? 'bg-rose-500' : 'bg-slate-400')} />
            <div className="min-w-0">
              <p className="truncate text-xs font-semibold text-[var(--ink-strong)]">{pretty(item.matchPhase ?? (item.live ? 'LIVE' : 'OBSERVED'))} · {item.source ?? 'Unknown source'}</p>
              <p className="mt-0.5 truncate text-[10px] text-[var(--ink-muted)]">{item.scoreDetail ?? `${Math.round(item.sourceConfidence * 100)}% source confidence`}</p>
            </div>
            <div className="text-right"><p className="font-mono text-sm font-bold">{item.liveScore ?? '—'}</p><p className="text-[9px] text-[var(--ink-muted)]">{formatTimestamp(item.observedAt)}</p></div>
          </div>
        )) : (
          <div className="rounded-[18px] border border-dashed border-[var(--line-strong)] bg-slate-50 p-5 text-sm text-[var(--ink-muted)]">No score observation is linked yet. The captured model call is safe, but settlement cannot advance until a score or result feed identifies this event.</div>
        )}
        {call.completionSignalSeen && call.systemWinnerPlayerId == null ? (
          <div className="flex gap-3 rounded-[18px] border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"><AlertTriangle className="mt-0.5 size-4 shrink-0" /><span>A terminal signal exists, but its winner evidence has not passed the trusted settlement gate. You may grade it provisionally above.</span></div>
        ) : null}
      </CardContent>
    </Card>
  )
}

function MatchupCard({ analysis, call }: { analysis: MatchupAnalysis | null; call: ModelCallTracking }) {
  return (
    <Card className="mt-5">
      <CardHeader>
        <Badge variant="accent" className="w-fit"><Eye className="mr-1 size-3" /> Matchup confidence</Badge>
        <CardTitle>Form, ratings, and head-to-head</CardTitle>
        <CardDescription>Context that helps explain the model call without hiding the amount of evidence behind it.</CardDescription>
      </CardHeader>
      <CardContent>
        {analysis ? (
          <div className="overflow-hidden rounded-[20px] border border-[var(--line)] bg-white/70">
            <ComparisonRow left={call.player1Name} label="Player" right={call.player2Name} heading />
            <ComparisonRow left={form(analysis.player1Form)} label={`Last ${analysis.player1Form.recentMatches}`} right={form(analysis.player2Form)} />
            <ComparisonRow left={form(analysis.player1Last50)} label={`Last ${Math.max(analysis.player1Last50.recentMatches, analysis.player2Last50.recentMatches)}`} right={form(analysis.player2Last50)} />
            <ComparisonRow left={Math.round(analysis.player1Ratings.elo).toString()} label="Elo" right={Math.round(analysis.player2Ratings.elo).toString()} />
            <ComparisonRow left={Math.round(analysis.player1Ratings.glicko).toString()} label="Glicko" right={Math.round(analysis.player2Ratings.glicko).toString()} />
            <ComparisonRow left={`${analysis.recentHeadToHead.player1Wins} wins`} label={`Recent H2H · ${analysis.recentHeadToHead.matches}`} right={`${analysis.recentHeadToHead.player2Wins} wins`} />
            <ComparisonRow left={`${analysis.headToHead.player1Wins} wins`} label={`All H2H · ${analysis.headToHead.totalMatches}`} right={`${analysis.headToHead.player2Wins} wins`} />
          </div>
        ) : <div className="rounded-[18px] border border-dashed border-[var(--line-strong)] bg-slate-50 p-5 text-sm text-[var(--ink-muted)]">Historical matchup detail is not available for this player identity yet. The recorded probability and price snapshot remain visible above.</div>}
      </CardContent>
    </Card>
  )
}

function WinnerButton({ active, name, onClick }: { active: boolean; name: string; onClick: () => void }) {
  return <button className={cn('rounded-[18px] border px-4 py-4 text-left text-sm font-semibold transition', active ? 'border-violet-400 bg-violet-50 text-violet-950 ring-2 ring-violet-200' : 'border-[var(--line-strong)] bg-white text-[var(--ink-strong)] hover:border-violet-300')} onClick={onClick} type="button"><span className="flex items-center justify-between gap-3">{name}{active ? <CheckCircle2 className="size-4 text-violet-700" /> : null}</span></button>
}

function MetricCell({ label, value }: { label: string; value: string }) {
  return <div className="text-right"><p className="text-[9px] font-semibold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p><p className="mt-1 font-mono text-sm font-bold text-[var(--ink-strong)]">{value}</p></div>
}

function DetailMetric({ label, value }: { label: string; value: string }) {
  return <div className="rounded-[18px] border border-[var(--line)] bg-white/70 p-4"><p className="text-[9px] font-semibold uppercase tracking-[0.14em] text-[var(--ink-muted)]">{label}</p><p className="mt-2 break-words text-sm font-bold text-[var(--ink-strong)]">{value}</p></div>
}

function ComparisonRow({ heading = false, label, left, right }: { heading?: boolean; label: string; left: string; right: string }) {
  return <div className={cn('grid grid-cols-[1fr_110px_1fr] items-center gap-2 border-b border-[var(--line)] px-4 py-3 last:border-0', heading && 'bg-slate-50')}><span className={cn('truncate font-mono text-sm', heading && 'font-sans font-bold')}>{left}</span><span className="text-center text-[9px] font-semibold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</span><span className={cn('truncate text-right font-mono text-sm', heading && 'font-sans font-bold')}>{right}</span></div>
}

function pipelineSteps(call: ModelCallTracking) {
  const hasObservation = call.latestObservedAt != null
  const viewer = call.viewerReviewedAt != null
  const system = call.systemWinnerPlayerId != null
  return [
    { label: 'Call captured', detail: `${pretty(call.captureType)} · ${formatTimestamp(call.capturedAt)}`, complete: true, current: false },
    { label: 'Decision gated', detail: `${pretty(call.decisionStatus ?? 'Recorded')} · ${pretty(call.decisionReason ?? 'No reason')}`, complete: true, current: false },
    { label: 'Score tracked', detail: hasObservation ? `${call.latestScore ?? 'No score'} · ${call.latestSource ?? 'source unknown'}` : 'Waiting for a linked score feed', complete: hasObservation, current: !hasObservation },
    { label: 'Viewer grade', detail: viewer ? `${call.viewerWinnerName} · ${call.viewerScore ?? 'score not entered'}` : system ? 'No viewer grade needed' : 'Optional provisional result', complete: viewer || system, current: !viewer && !system && call.canApprove },
    { label: 'Trusted result', detail: system ? `${call.systemWinnerName} · ${call.systemResultSource}` : 'Waiting for trusted terminal or archive evidence', complete: system, current: !system && call.pipelineStage === 'SETTLEMENT_REVIEW' },
  ]
}

function form(value: MatchupAnalysis['player1Form']) {
  return `${value.recentWins}-${Math.max(0, value.recentMatches - value.recentWins)} (${(value.recentWinPct * 100).toFixed(0)}%)`
}

function formatTimestamp(value: string | null) {
  if (!value) return 'Not seen'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit' }).format(parsed)
}

function message(error: unknown) {
  return error instanceof Error ? error.message : 'Unable to load this match.'
}
