import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronUp,
  ExternalLink,
  Info,
  Loader2,
  MessageSquare,
  RefreshCcw,
  ShieldCheck,
  ThumbsDown,
  ThumbsUp,
} from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveSession } from '@/features/live-studio/api'
import type { PaperTradeBet } from '@/features/live-studio/types'
import { fetchScoreTruthEvidence, fetchScoreTruthReviewQueue, submitScoreTruthReviewAction } from '@/features/score-truth/api'
import type {
  JsonValue,
  ScoreTruthEvidenceResponse,
  ScoreTruthReviewAction,
  ScoreTruthReviewItem,
  ScoreTruthReviewQueueResponse,
} from '@/features/score-truth/types'
import { cn } from '@/lib/utils'

const PAGE_SIZE = 20

export function ReviewRoute() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [data, setData] = useState<ScoreTruthReviewQueueResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [actionInFlight, setActionInFlight] = useState<number | null>(null)
  const [comments, setComments] = useState<Record<number, string>>({})
  const [reviewer, setReviewer] = useState('operator')
  const [betLookup, setBetLookup] = useState<Map<number, PaperTradeBet>>(new Map())
  const mountedRef = useRef(true)
  const page = parsePage(searchParams.get('page'))

  // One-shot session fetch so we can resolve betId → matchup name for any bet
  // that's still in the active session (covers ~the last 25 bets).
  useEffect(() => {
    void fetchLiveSession().then((session) => {
      if (!mountedRef.current) return
      const map = new Map<number, PaperTradeBet>()
      for (const bet of session.recentBets ?? []) {
        map.set(bet.id, bet)
      }
      for (const bet of session.openBetsList ?? []) {
        map.set(bet.id, bet)
      }
      setBetLookup(map)
    }).catch(() => {
      // Name lookup is a nice-to-have; absence is fine.
    })
  }, [])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadQueue = useCallback(async (background: boolean) => {
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    try {
      const next = await fetchScoreTruthReviewQueue({ page, size: PAGE_SIZE })
      if (!mountedRef.current) {
        return
      }
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to load review queue right now.')
    } finally {
      if (!mountedRef.current) {
        return
      }
      if (background) {
        setRefreshing(false)
      } else {
        setLoading(false)
      }
    }
  }, [page])

  useEffect(() => {
    void loadQueue(false)
  }, [loadQueue])

  const pageStats = useMemo(() => summarizePage(data), [data])
  const totalPages = data ? Math.max(1, data.totalPages) : 1

  const updatePage = useCallback((nextPage: number) => {
    const next = new URLSearchParams(searchParams)
    if (nextPage <= 0) {
      next.delete('page')
    } else {
      next.set('page', String(nextPage))
    }
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams])

  const submitAction = useCallback(async (item: ScoreTruthReviewItem, action: ScoreTruthReviewAction) => {
    const comment = (comments[item.decisionId] ?? '').trim()
    if ((action === 'REJECT' || action === 'COMMENT') && !comment) {
      setError('A comment is required before rejecting or commenting on a manual review item.')
      return
    }

    setActionInFlight(item.decisionId)
    try {
      await submitScoreTruthReviewAction(item.decisionId, {
        action,
        comment: comment || null,
        reviewer: reviewer.trim() || 'operator',
      })
      if (!mountedRef.current) {
        return
      }
      setComments((current) => ({ ...current, [item.decisionId]: '' }))
      await loadQueue(true)
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to save review action right now.')
    } finally {
      if (mountedRef.current) {
        setActionInFlight(null)
      }
    }
  }, [comments, loadQueue, reviewer])

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Manual Review Queue"
      description="Bets the system held back from auto-settling because the evidence was thin or conflicting. Your job is to accept, reject, or annotate each one."
      badges={
        <>
          <Badge variant="accent">Review</Badge>
          <Badge>{data ? `${data.totalItems} total` : '—'}</Badge>
          <Badge>{`${pageStats.open} open this page`}</Badge>
        </>
      }
      actions={
        <Button variant="secondary" onClick={() => void loadQueue(true)} disabled={loading || refreshing}>
          <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
          Refresh queue
        </Button>
      }
    >
      <Card>
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            <Info className="size-3" />
            How this page works
          </Badge>
          <CardTitle>What you're reviewing, and what each button does</CardTitle>
          <CardDescription>
            Every row below is a bet where the auto-settlement pipeline refused to close on its own.
            Your job: read the evidence, agree (Accept) or disagree (Reject), and leave a comment when context helps the next reviewer.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-5 xl:grid-cols-3">
          <GlossarySection title="The four metrics on each row">
            <GlossaryItem term="Coverage">
              How many evidence sources had something to say. <b>FULL</b> = market + stream-CV + post-close all chimed in.{' '}
              <b>PARTIAL</b> = some sources were silent. <b>NONE</b> = nothing matched — the riskiest to accept blindly.
            </GlossaryItem>
            <GlossaryItem term="Contradictions">
              Count of sources that disagreed about who won, the final score, or whether the match even finished.
              0 is good; any positive count means someone is wrong and you need to pick a side.
            </GlossaryItem>
            <GlossaryItem term="Ambiguity">
              0%–100% score of how mixed the evidence was. Higher = harder. Above ~30% usually means the bundle is genuinely ambiguous, not just thin.
            </GlossaryItem>
            <GlossaryItem term="Confidence">
              Blended confidence the v3 engine assigned to its own proposal. ≥ 90% means it's pretty sure;{' '}
              &lt; 70% means it's flagging itself as shaky and is asking you to confirm.
            </GlossaryItem>
          </GlossarySection>
          <GlossarySection title="What your buttons actually do">
            <GlossaryItem term="Accept">
              You agree with the v3 proposal. The bet will settle as proposed and the audit row records who signed off.
            </GlossaryItem>
            <GlossaryItem term="Reject">
              You disagree. The bet stays in held state and goes back for fresh evidence. A comment is required.
            </GlossaryItem>
            <GlossaryItem term="Comment">
              Leave a note without committing to accept or reject — useful when you want a second pair of eyes.
              A comment is required.
            </GlossaryItem>
          </GlossarySection>
          <GlossarySection title="Reading the evidence timeline">
            <GlossaryItem term="HR_MKT, HR_TGT…">
              The source of each observation. <b>HR_MKT</b> is the Hard Rock market feed, <b>HR_TGT</b> is targeted polling,{' '}
              <b>STREAM</b> is the Stream-CV pipeline (when present), <b>OFFICIAL</b> is the tournament page.
            </GlossaryItem>
            <GlossaryItem term="Score row">
              Reads as <code>gamesP1-gamesP2 (pointsP1-pointsP2)</code>. Empty score boxes mean the source only had a "in progress" signal.
            </GlossaryItem>
            <GlossaryItem term="phase">
              Where the match was — <b>LIVE_EARLY</b>, <b>LIVE_MID</b>, <b>LIVE_LATE</b>, <b>FINISHED</b>. Disagreement on phase usually drives the contradictions count.
            </GlossaryItem>
          </GlossarySection>
        </CardContent>
      </Card>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Queue Snapshot
          </Badge>
          <CardTitle>Where we stand right now</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4">
          {loading && !data ? (
            <div className="rounded-[24px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
              Loading manual review queue…
            </div>
          ) : null}

          {data ? (
            <>
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                <MetricTile label="Total in queue" value={String(data.totalItems)} icon={ShieldCheck} />
                <MetricTile label="Open this page" value={String(pageStats.open)} icon={AlertTriangle} />
                <MetricTile label="Already reviewed" value={String(pageStats.reviewed)} icon={ThumbsUp} />
                <MetricTile label="Oldest on page" value={formatDateTime(pageStats.oldest)} icon={MessageSquare} />
              </div>
              <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                <div className="grid gap-3 text-sm text-[var(--ink-muted)] sm:grid-cols-[1fr_1.2fr]">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-[0.24em]">Snapshot taken</p>
                    <p className="mt-2 font-medium text-[var(--ink-strong)]">{formatDateTime(data.generatedAt)}</p>
                  </div>
                  <label className="block">
                    <span className="text-xs font-semibold uppercase tracking-[0.24em]">Your reviewer handle</span>
                    <input
                      value={reviewer}
                      onChange={(event) => setReviewer(event.target.value)}
                      className="mt-2 w-full rounded-[18px] border border-[var(--line)] bg-[var(--panel)] px-3 py-2 text-sm text-[var(--ink-strong)] outline-none transition-colors focus:border-[var(--accent-soft)]"
                    />
                  </label>
                </div>
              </div>
            </>
          ) : null}

          {error ? (
            <InlineAlert>
              <AlertTriangle className="size-4" />
              <span>{error}</span>
            </InlineAlert>
          ) : null}
        </CardContent>
      </Card>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Queue Items
          </Badge>
          <CardTitle>ManualReview audit rows</CardTitle>
          <CardDescription>
            Each item is paged newest-first. Detail links open the unified match shell for evidence, prediction,
            history, and market context.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          {data && data.items.length === 0 ? (
            <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
              No manual-review decisions are in this page.
            </div>
          ) : null}

          {data?.items.map((item) => (
            <ReviewQueueItem
              key={item.decisionId}
              item={item}
              bet={betLookup.get(item.betId) ?? null}
              comment={comments[item.decisionId] ?? ''}
              disabled={actionInFlight === item.decisionId}
              onCommentChange={(nextComment) => setComments((current) => ({ ...current, [item.decisionId]: nextComment }))}
              onSubmit={(action) => void submitAction(item, action)}
            />
          ))}

          {data ? (
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.66)] px-4 py-3 text-sm text-[var(--ink-muted)]">
              <div>
                Page {data.page + 1} of {totalPages}
                <span className="ml-2">{data.totalItems} manual-review rows</span>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={!data.hasPrevious}
                  onClick={() => updatePage(Math.max(0, data.page - 1))}
                >
                  <ChevronLeft className="size-4" />
                  Prev
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={!data.hasNext}
                  onClick={() => updatePage(data.page + 1)}
                >
                  Next
                  <ChevronRight className="size-4" />
                </Button>
              </div>
            </div>
          ) : null}
        </CardContent>
      </Card>
    </V3Shell>
  )
}

function ReviewQueueItem({
  item,
  bet,
  comment,
  disabled,
  onCommentChange,
  onSubmit,
}: {
  item: ScoreTruthReviewItem
  bet: PaperTradeBet | null
  comment: string
  disabled: boolean
  onCommentChange: (value: string) => void
  onSubmit: (action: ScoreTruthReviewAction) => void
}) {
  const payload = asObject(item.payload)
  const contradictionCount = readNumber(payload?.contradictionCount) ?? readNumber(payload?.manualContradictions)
  const coverageState = readText(payload?.coverageState) ?? 'UNKNOWN'
  const ambiguity = readNumber(payload?.ambiguityScore)
  const decisionType = readText(payload?.decisionType) ?? 'MANUAL_REVIEW'

  const matchupTitle = bet?.eventName ?? 'Match name not in active session'
  const sidePicked = bet?.sideName ?? null
  const stake = bet?.stake ?? null

  return (
    <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.74)] p-4 shadow-[0_16px_48px_-34px_rgba(15,23,42,0.7)]">
      <div className="flex flex-col gap-3 xl:flex-row xl:items-start xl:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <ReviewStatusPill status={item.reviewStatus} />
            <Badge>Bet #{item.betId}</Badge>
            <span className="text-xs text-[var(--ink-muted)]">Decision #{item.decisionId}</span>
          </div>
          <p className="mt-3 text-lg font-semibold text-[var(--ink-strong)]">{matchupTitle}</p>
          {sidePicked != null ? (
            <p className="mt-1 text-sm text-[var(--ink-muted)]">
              We backed <span className="font-semibold text-[var(--ink-strong)]">{sidePicked}</span>
              {stake != null ? <> for <span className="font-semibold text-[var(--ink-strong)]">${stake.toFixed(2)}</span></> : null}
              {bet?.decimalOdds ? <> at <span className="font-semibold text-[var(--ink-strong)]">{bet.decimalOdds.toFixed(2)}</span></> : null}
            </p>
          ) : null}
          <p className="mt-2 rounded-[14px] bg-[var(--panel-soft)] px-3 py-2 text-sm text-[var(--ink)]">
            <span className="font-semibold">Why it's here:</span> {explainReason(item.reason, decisionType)}
          </p>
          <p className="mt-2 text-xs text-[var(--ink-muted)]">
            Tracked event: <code className="font-mono">{item.trackedEventId ?? '—'}</code> · {formatDateTime(item.decidedAt)}
          </p>
        </div>
        <Button variant="ghost" size="sm" asChild>
          <Link to={`/matches/${item.betId}/evidence`}>
            <ExternalLink className="size-4" />
            Open in match detail
          </Link>
        </Button>
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <MetricWithLegend
          label="Coverage"
          value={coverageState}
          legend={coverageLegend(coverageState)}
          tone={coverageTone(coverageState)}
        />
        <MetricWithLegend
          label="Contradictions"
          value={contradictionCount == null ? 'N/A' : String(contradictionCount)}
          legend={
            contradictionCount == null
              ? 'No contradiction count was attached to this row.'
              : contradictionCount === 0
                ? 'No source contradicted any other source.'
                : `${contradictionCount} source${contradictionCount === 1 ? '' : 's'} disagreed — read the timeline to identify which.`
          }
          tone={contradictionCount && contradictionCount > 0 ? 'warning' : 'neutral'}
        />
        <MetricWithLegend
          label="Ambiguity"
          value={ambiguity == null ? 'N/A' : toPercent(ambiguity)}
          legend={ambiguityLegend(ambiguity)}
          tone={ambiguity != null && ambiguity > 0.3 ? 'warning' : 'neutral'}
        />
        <MetricWithLegend
          label="Confidence"
          value={item.confidence == null ? 'N/A' : toPercent(item.confidence)}
          legend={confidenceLegend(item.confidence)}
          tone={item.confidence != null && item.confidence < 0.7 ? 'warning' : 'good'}
        />
      </div>

      <EvidenceExpander betId={item.betId} />

      {item.reviewStatus !== 'OPEN' ? (
        <div className="mt-4 rounded-[20px] border border-[var(--line)] bg-[var(--panel-soft)] px-4 py-3 text-sm text-[var(--ink-muted)]">
          <span className="font-medium text-[var(--ink-strong)]">{item.reviewer ?? 'operator'}</span>
          {' '}
          marked this <span className="font-medium">{item.reviewStatus.toLowerCase()}</span> at {formatDateTime(item.reviewedAt)}.
          {item.reviewComment ? <span> Comment: "{item.reviewComment}"</span> : null}
        </div>
      ) : null}

      <div className="mt-4 grid gap-3 xl:grid-cols-[1fr_auto] xl:items-end">
        <label className="block">
          <span className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">Your note</span>
          <textarea
            aria-label={`Operator comment for decision ${item.decisionId}`}
            value={comment}
            onChange={(event) => onCommentChange(event.target.value)}
            className="mt-2 min-h-20 w-full resize-y rounded-[18px] border border-[var(--line)] bg-[var(--panel)] px-3 py-2 text-sm leading-6 text-[var(--ink-strong)] outline-none transition-colors focus:border-[var(--accent-soft)]"
            placeholder="Required for Reject and Comment. Recommended on Accept when the evidence was thin."
          />
        </label>
        <div className="flex flex-wrap gap-2 xl:justify-end">
          <ActionButton
            label="Accept"
            sub="Settle as proposed"
            icon={ThumbsUp}
            variant="primary"
            onClick={() => onSubmit('ACCEPT')}
            disabled={disabled || item.reviewStatus !== 'OPEN'}
          />
          <ActionButton
            label="Reject"
            sub="Send back for evidence"
            icon={ThumbsDown}
            variant="secondary"
            onClick={() => onSubmit('REJECT')}
            disabled={disabled || item.reviewStatus !== 'OPEN' || !comment.trim()}
          />
          <ActionButton
            label="Comment"
            sub="Annotate only"
            icon={MessageSquare}
            variant="ghost"
            onClick={() => onSubmit('COMMENT')}
            disabled={disabled || item.reviewStatus !== 'OPEN' || !comment.trim()}
          />
        </div>
      </div>
    </div>
  )
}

function EvidenceExpander({ betId }: { betId: number }) {
  const [open, setOpen] = useState(false)
  const [data, setData] = useState<ScoreTruthEvidenceResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const toggle = useCallback(async () => {
    setOpen((current) => !current)
    if (!data && !loading) {
      setLoading(true)
      try {
        const next = await fetchScoreTruthEvidence(String(betId))
        setData(next)
        setError(null)
      } catch (nextError) {
        setError(nextError instanceof Error ? nextError.message : 'Unable to load evidence.')
      } finally {
        setLoading(false)
      }
    }
  }, [data, loading, betId])

  return (
    <div className="mt-4 rounded-[20px] border border-[var(--line)] bg-[rgba(255,255,255,0.6)]">
      <button
        type="button"
        onClick={() => void toggle()}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-3 rounded-[20px] px-4 py-3 text-left text-sm font-semibold text-[var(--ink-strong)] hover:bg-[rgba(255,255,255,0.85)]"
      >
        <span className="inline-flex items-center gap-2">
          {loading ? <Loader2 className="size-4 animate-spin" /> : open ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
          {open ? 'Hide evidence timeline' : 'View evidence timeline (last observations)'}
        </span>
        {data ? (
          <span className="text-xs font-normal text-[var(--ink-muted)]">
            {observationCount(data)} observations
          </span>
        ) : null}
      </button>
      {open ? (
        <div className="border-t border-[var(--line)] px-4 py-4">
          {error ? (
            <InlineAlert>
              <AlertTriangle className="size-4" />
              <span>{error}</span>
            </InlineAlert>
          ) : data ? (
            <EvidenceTimeline data={data} />
          ) : (
            <p className="text-sm text-[var(--ink-muted)]">Loading evidence…</p>
          )}
        </div>
      ) : null}
    </div>
  )
}

function EvidenceTimeline({ data }: { data: ScoreTruthEvidenceResponse }) {
  const payload = asObject(data.evidence.payload as JsonValue) ?? {}
  const live = asArray(payload.liveObservations)
  const stream = asArray(payload.streamObservations)
  const post = asArray(payload.postCloseObservations)
  const merged = [...live, ...stream, ...post]
    .map(asObject)
    .filter((entry): entry is { [key: string]: JsonValue } => entry != null)
    .sort((a, b) => observedAtMs(b) - observedAtMs(a))
    .slice(0, 8)

  const latest = merged[0]
  const latestScore = latest ? formatObservationScore(latest) : null
  const latestPhase = latest ? readText(latest.phase) : null

  return (
    <div className="grid gap-4">
      {latest ? (
        <div className="rounded-[16px] border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
          <span className="text-xs font-semibold uppercase tracking-[0.2em]">Most recent observation</span>
          <p className="mt-1 font-mono text-base font-semibold">
            {latestScore ?? '—'}
            {latestPhase ? <span className="ml-2 rounded-full bg-emerald-100 px-2 text-[10px] uppercase">{latestPhase}</span> : null}
          </p>
          <p className="mt-1 text-xs">
            from {readText(latest.source) ?? '?'} at {formatDateTime(readText(latest.observedAt))}
          </p>
        </div>
      ) : (
        <p className="text-sm text-[var(--ink-muted)]">No observations attached to this evidence bundle.</p>
      )}
      {merged.length > 0 ? (
        <table className="w-full border-separate border-spacing-y-1 text-sm">
          <thead>
            <tr className="text-left text-[11px] uppercase tracking-[0.18em] text-[var(--ink-muted)]">
              <th className="pb-1">When</th>
              <th className="pb-1">Source</th>
              <th className="pb-1">Score</th>
              <th className="pb-1">Phase</th>
              <th className="pb-1 text-right">Confidence</th>
            </tr>
          </thead>
          <tbody>
            {merged.map((obs, index) => (
              <tr key={`${index}-${readText(obs.observedAt) ?? index}`} className="rounded bg-[rgba(255,255,255,0.74)]">
                <td className="px-2 py-1 align-top text-xs text-[var(--ink-muted)]">{formatDateTime(readText(obs.observedAt))}</td>
                <td className="px-2 py-1 align-top text-xs font-semibold text-[var(--ink-strong)]">{readText(obs.source) ?? '?'}</td>
                <td className="px-2 py-1 align-top font-mono text-xs text-[var(--ink-strong)]">{formatObservationScore(obs) ?? '—'}</td>
                <td className="px-2 py-1 align-top text-xs text-[var(--ink-muted)]">{readText(obs.phase) ?? '—'}</td>
                <td className="px-2 py-1 align-top text-right text-xs">{percentOrDash(readNumber(obs.confidence))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {data.contradictions && data.contradictions.length > 0 ? (
        <div className="rounded-[16px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          <p className="text-xs font-semibold uppercase tracking-[0.2em]">
            {data.contradictions.length} contradiction{data.contradictions.length === 1 ? '' : 's'} recorded
          </p>
          <ul className="mt-2 list-disc space-y-1 pl-5">
            {data.contradictions.slice(0, 5).map((c) => (
              <li key={c.id}>
                <span className="font-semibold">{c.kind}</span>
                {' · severity '}
                {(c.severity * 100).toFixed(0)}%
                {c.resolutionNote ? ` — ${c.resolutionNote}` : ''}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}

function GlossarySection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <p className="text-xs font-semibold uppercase tracking-[0.22em] text-[var(--ink-muted)]">{title}</p>
      <dl className="mt-3 grid gap-3 text-sm">{children}</dl>
    </div>
  )
}

function GlossaryItem({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div>
      <dt className="font-semibold text-[var(--ink-strong)]">{term}</dt>
      <dd className="mt-0.5 text-[var(--ink-muted)] leading-6">{children}</dd>
    </div>
  )
}

function MetricWithLegend({
  label,
  value,
  legend,
  tone,
}: {
  label: string
  value: string
  legend: string
  tone: 'good' | 'warning' | 'neutral'
}) {
  const toneClass =
    tone === 'good'
      ? 'border-emerald-200 bg-emerald-50'
      : tone === 'warning'
        ? 'border-amber-200 bg-amber-50'
        : 'border-[var(--line)] bg-[var(--panel-soft)]'
  return (
    <div className={cn('rounded-[18px] border px-3 py-3', toneClass)}>
      <p className="text-xs font-semibold uppercase tracking-[0.22em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-2 text-base font-semibold text-[var(--ink-strong)]">{value}</p>
      <p className="mt-1 text-xs leading-5 text-[var(--ink-muted)]">{legend}</p>
    </div>
  )
}

function ActionButton({
  label,
  sub,
  icon: Icon,
  variant,
  onClick,
  disabled,
}: {
  label: string
  sub: string
  icon: typeof ThumbsUp
  variant: 'primary' | 'secondary' | 'ghost'
  onClick: () => void
  disabled: boolean
}) {
  return (
    <Button variant={variant} size="sm" onClick={onClick} disabled={disabled} aria-label={`${label} — ${sub}`}>
      <Icon className="size-4" />
      <span className="flex flex-col items-start leading-tight">
        <span>{label}</span>
        <span className="text-[10px] font-normal opacity-80">{sub}</span>
      </span>
    </Button>
  )
}

function explainReason(reason: string, decisionType: string): string {
  const r = (reason ?? '').toUpperCase()
  const d = (decisionType ?? '').toUpperCase()
  if (r === 'MANUAL_REVIEW_AWAITING') {
    return 'The auto-pipeline refused to close this on its own. It is waiting for a human signal before promoting any settlement.'
  }
  if (r.includes('CONTRADICTION')) {
    return 'Two or more evidence sources disagreed about who won or how the match ended. Pick the source you trust by Accept/Reject.'
  }
  if (r.includes('STREAM') || r === 'SCORE_BACKED_ONLY') {
    return 'Required Stream-CV evidence was missing after the market closed. Accept only if you can confirm the result from a trustworthy source.'
  }
  if (r.includes('AMBIGUOUS')) {
    return 'The evidence bundle is mixed enough that the v3 engine wants a human to decide which signal to trust.'
  }
  if (d === 'ESCALATE') {
    return 'The auto-pipeline escalated — it has a working theory but is asking for confirmation before committing.'
  }
  return `Decision type ${d || 'MANUAL_REVIEW'} with reason code "${reason}". See the timeline below for source-level detail.`
}

function coverageLegend(state: string): string {
  switch (state.toUpperCase()) {
    case 'FULL':
      return 'Market + stream-CV + post-close all reported. Strongest evidence base.'
    case 'PARTIAL':
      return 'Some sources were silent. Accept only if the present sources agree.'
    case 'NONE':
      return 'No sources matched. Treat with caution — reject unless you have outside info.'
    default:
      return 'Coverage tag missing from this row.'
  }
}

function coverageTone(state: string): 'good' | 'warning' | 'neutral' {
  switch (state.toUpperCase()) {
    case 'FULL':
      return 'good'
    case 'NONE':
      return 'warning'
    default:
      return 'neutral'
  }
}

function ambiguityLegend(score: number | null): string {
  if (score == null) return 'No ambiguity score on this row.'
  if (score < 0.15) return 'Sources agree closely.'
  if (score < 0.3) return 'Minor disagreement; usually safe to accept.'
  if (score < 0.5) return 'Real ambiguity — read the timeline before deciding.'
  return 'High ambiguity. Default to reject unless you can resolve it.'
}

function confidenceLegend(score: number | null): string {
  if (score == null) return 'No confidence value attached.'
  if (score >= 0.9) return 'v3 is very sure of its own proposal.'
  if (score >= 0.7) return 'v3 is reasonably sure but asked for a human check.'
  return 'v3 is flagging itself as shaky. Lean toward reject.'
}

function asArray(value: JsonValue | undefined): JsonValue[] {
  return Array.isArray(value) ? value : []
}

function percentOrDash(value: number | null): string {
  if (value == null || !Number.isFinite(value)) return '—'
  return toPercent(value)
}

function observedAtMs(obs: { [key: string]: JsonValue }): number {
  const iso = readText(obs.observedAt)
  if (!iso) return 0
  const time = new Date(iso).getTime()
  return Number.isFinite(time) ? time : 0
}

function formatObservationScore(obs: { [key: string]: JsonValue } | null): string | null {
  if (!obs) return null
  const score = asObject(obs.score)
  if (!score) return null
  const gP1 = readNumber(score.gamesP1)
  const gP2 = readNumber(score.gamesP2)
  const pP1 = readNumber(score.pointsP1)
  const pP2 = readNumber(score.pointsP2)
  if (gP1 == null && gP2 == null && pP1 == null && pP2 == null) {
    return null
  }
  const games = `${gP1 ?? '-'}-${gP2 ?? '-'}`
  const points = pP1 != null || pP2 != null ? ` (${pP1 ?? '-'}-${pP2 ?? '-'})` : ''
  return `${games}${points}`
}

function observationCount(data: ScoreTruthEvidenceResponse): number {
  const payload = asObject(data.evidence.payload as JsonValue) ?? {}
  return (
    asArray(payload.liveObservations).length
    + asArray(payload.streamObservations).length
    + asArray(payload.postCloseObservations).length
  )
}

function MetricTile({
  label,
  value,
  icon: Icon,
}: {
  label: string
  value: string
  icon: typeof ShieldCheck
}) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3 text-[var(--ink-muted)]">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <p className="text-xs font-semibold uppercase tracking-[0.24em]">{label}</p>
      </div>
      <p className="mt-4 font-serif text-2xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function ReviewStatusPill({ status }: { status: string }) {
  const tone = {
    OPEN: 'border-amber-200 bg-amber-50 text-amber-900',
    ACCEPTED: 'border-emerald-200 bg-emerald-50 text-emerald-800',
    REJECTED: 'border-rose-200 bg-rose-50 text-rose-800',
    COMMENTED: 'border-sky-200 bg-sky-50 text-sky-900',
  }[status] ?? 'border-slate-200 bg-slate-50 text-slate-700'

  return (
    <span className={cn('inline-flex items-center rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-[0.18em]', tone)}>
      {status.replaceAll('_', ' ')}
    </span>
  )
}

function InlineAlert({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-2 rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">
      {children}
    </div>
  )
}

function summarizePage(data: ScoreTruthReviewQueueResponse | null) {
  const items = data?.items ?? []
  const dates = items.map((item) => item.decidedAt).filter((value): value is string => Boolean(value))
  const oldest = dates.length > 0 ? dates.reduce((left, right) => left.localeCompare(right) <= 0 ? left : right) : null
  return {
    open: items.filter((item) => item.reviewStatus === 'OPEN').length,
    reviewed: items.filter((item) => item.reviewStatus !== 'OPEN').length,
    oldest,
  }
}

function asObject(value: JsonValue | null | undefined): Record<string, JsonValue> | null {
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    return null
  }
  return value as Record<string, JsonValue>
}

function readText(value: JsonValue | undefined) {
  return typeof value === 'string' ? value : null
}

function readNumber(value: JsonValue | undefined) {
  return typeof value === 'number' ? value : null
}

function toPercent(value: number) {
  return `${(value * 100).toFixed(1)}%`
}

function formatDateTime(value: string | null) {
  if (!value) {
    return 'N/A'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date)
}

function parsePage(value: string | null) {
  if (!value) {
    return 0
  }
  const numeric = Number.parseInt(value, 10)
  return Number.isNaN(numeric) || numeric < 0 ? 0 : numeric
}
