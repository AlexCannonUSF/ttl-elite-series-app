import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
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
import { fetchScoreTruthReviewQueue, submitScoreTruthReviewAction } from '@/features/score-truth/api'
import type {
  JsonValue,
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
  const mountedRef = useRef(true)
  const page = parsePage(searchParams.get('page'))

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
      description="Operator queue for Score Truth decisions that need a human accept, reject, or comment before promotion confidence can be trusted."
      badges={
        <>
          <Badge variant="accent">Phase 03</Badge>
          <Badge>ManualReview</Badge>
          <Badge>Append-only Actions</Badge>
        </>
      }
      actions={
        <Button variant="secondary" onClick={() => void loadQueue(true)} disabled={loading || refreshing}>
          <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
          Refresh queue
        </Button>
      }
    >
      <section className="grid gap-5 xl:grid-cols-[1.05fr_0.95fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Queue Snapshot
            </Badge>
            <CardTitle>Manual-review decisions ready for triage</CardTitle>
            <CardDescription>
              The page reads directly from persisted Score Truth audit records and appends every operator action back
              into the same audit stream.
            </CardDescription>
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
                  <MetricTile label="Manual Reviews" value={String(data.totalItems)} icon={ShieldCheck} />
                  <MetricTile label="Open Page" value={String(pageStats.open)} icon={AlertTriangle} />
                  <MetricTile label="Reviewed Page" value={String(pageStats.reviewed)} icon={ThumbsUp} />
                  <MetricTile label="Oldest Page Row" value={formatDateTime(pageStats.oldest)} icon={MessageSquare} />
                </div>
                <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                  <div className="grid gap-3 text-sm text-[var(--ink-muted)] sm:grid-cols-[1fr_1.2fr]">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.24em]">Generated</p>
                      <p className="mt-2 font-medium text-[var(--ink-strong)]">{formatDateTime(data.generatedAt)}</p>
                    </div>
                    <label className="block">
                      <span className="text-xs font-semibold uppercase tracking-[0.24em]">Reviewer</span>
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

        <Card>
          <CardHeader>
            <Badge className="w-fit">Review Policy</Badge>
            <CardTitle>Decision handling</CardTitle>
            <CardDescription>
              Accept confirms the shadow decision. Reject and comment require operator context so later audits can
              distinguish evidence problems from operational notes.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            <PolicyRow icon={ThumbsUp} title="Accept" detail="Use when the manual-review evidence supports the Score Truth path." />
            <PolicyRow icon={ThumbsDown} title="Reject" detail="Use when the evidence bundle is wrong, incomplete, or contradicted." />
            <PolicyRow icon={MessageSquare} title="Comment" detail="Leave context without closing the item as accepted or rejected." />
          </CardContent>
        </Card>
      </section>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Queue Items
          </Badge>
          <CardTitle>ManualReview audit rows</CardTitle>
          <CardDescription>
            Each item is paged newest-first. Evidence links open the existing Score Truth viewer for the related bet.
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
  comment,
  disabled,
  onCommentChange,
  onSubmit,
}: {
  item: ScoreTruthReviewItem
  comment: string
  disabled: boolean
  onCommentChange: (value: string) => void
  onSubmit: (action: ScoreTruthReviewAction) => void
}) {
  const payload = asObject(item.payload)
  const contradictionCount = readNumber(payload?.contradictionCount) ?? readNumber(payload?.manualContradictions)
  const coverageState = readText(payload?.coverageState) ?? 'UNKNOWN'
  const ambiguity = readNumber(payload?.ambiguityScore)

  return (
    <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.74)] p-4 shadow-[0_16px_48px_-34px_rgba(15,23,42,0.7)]">
      <div className="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <ReviewStatusPill status={item.reviewStatus} />
            <p className="font-medium text-[var(--ink-strong)]">Bet #{item.betId}</p>
            <span className="text-sm text-[var(--ink-muted)]">Decision #{item.decisionId}</span>
          </div>
          <p className="mt-2 text-sm font-medium text-[var(--ink)]">{item.reason.replaceAll('_', ' ')}</p>
          <p className="mt-1 text-sm text-[var(--ink-muted)]">
            {item.trackedEventId ?? 'No tracked event'} · {formatDateTime(item.decidedAt)}
          </p>
        </div>
        <Button variant="ghost" size="sm" asChild>
          <Link to={`/matches/${item.betId}/evidence`}>
            <ExternalLink className="size-4" />
            Evidence
          </Link>
        </Button>
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <MiniMetric label="Coverage" value={coverageState} />
        <MiniMetric label="Contradictions" value={contradictionCount == null ? 'N/A' : String(contradictionCount)} />
        <MiniMetric label="Ambiguity" value={ambiguity == null ? 'N/A' : toPercent(ambiguity)} />
        <MiniMetric label="Confidence" value={item.confidence == null ? 'N/A' : toPercent(item.confidence)} />
      </div>

      {item.reviewStatus !== 'OPEN' ? (
        <div className="mt-4 rounded-[20px] border border-[var(--line)] bg-[var(--panel-soft)] px-4 py-3 text-sm text-[var(--ink-muted)]">
          <span className="font-medium text-[var(--ink-strong)]">{item.reviewer ?? 'operator'}</span>
          {' '}
          marked this {item.reviewStatus.toLowerCase()} {formatDateTime(item.reviewedAt)}.
          {item.reviewComment ? <span> Comment: {item.reviewComment}</span> : null}
        </div>
      ) : null}

      <div className="mt-4 grid gap-3 xl:grid-cols-[1fr_auto] xl:items-end">
        <label className="block">
          <span className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">Operator Comment</span>
          <textarea
            value={comment}
            onChange={(event) => onCommentChange(event.target.value)}
            className="mt-2 min-h-20 w-full resize-y rounded-[18px] border border-[var(--line)] bg-[var(--panel)] px-3 py-2 text-sm leading-6 text-[var(--ink-strong)] outline-none transition-colors focus:border-[var(--accent-soft)]"
            placeholder="Add context for reject/comment actions."
          />
        </label>
        <div className="flex flex-wrap gap-2 xl:justify-end">
          <Button size="sm" onClick={() => onSubmit('ACCEPT')} disabled={disabled}>
            <ThumbsUp className="size-4" />
            Accept
          </Button>
          <Button variant="secondary" size="sm" onClick={() => onSubmit('REJECT')} disabled={disabled || !comment.trim()}>
            <ThumbsDown className="size-4" />
            Reject
          </Button>
          <Button variant="ghost" size="sm" onClick={() => onSubmit('COMMENT')} disabled={disabled || !comment.trim()}>
            <MessageSquare className="size-4" />
            Comment
          </Button>
        </div>
      </div>
    </div>
  )
}

function PolicyRow({
  icon: Icon,
  title,
  detail,
}: {
  icon: typeof ThumbsUp
  title: string
  detail: string
}) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex gap-3">
        <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <div>
          <p className="font-medium text-[var(--ink-strong)]">{title}</p>
          <p className="mt-1 text-sm leading-6 text-[var(--ink-muted)]">{detail}</p>
        </div>
      </div>
    </div>
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

function MiniMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[18px] border border-[var(--line)] bg-[var(--panel-soft)] px-3 py-3">
      <p className="text-xs font-semibold uppercase tracking-[0.22em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-2 text-sm font-medium text-[var(--ink-strong)]">{value}</p>
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
    <div className="flex items-center gap-2 rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
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
