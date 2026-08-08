import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  CircleDollarSign,
  Database,
  DatabaseZap,
  GitCompareArrows,
  RadioTower,
  RefreshCcw,
  ShieldCheck,
  TimerReset,
  type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchLiveSession } from '@/features/live-studio/api'
import type { PaperTradingSession } from '@/features/live-studio/types'
import { fetchOpsDiffs } from '@/features/ops-diffs/api'
import type { OpsSettlementDiffsResponse } from '@/features/ops-diffs/types'
import { fetchOpsFeeds, fetchOpsIngest, fetchOpsStreams } from '@/features/ops-feeds/api'
import type { OpsFeedsResponse, OpsIngestResponse, OpsStreamsResponse } from '@/features/ops-feeds/types'
import { fetchScrapeStatus } from '@/features/scrape/api'
import type { ScrapeStatus } from '@/features/scrape/types'
import { fetchScoreTruthReviewQueue } from '@/features/score-truth/api'
import type { ScoreTruthReviewQueueResponse } from '@/features/score-truth/types'
import { cn } from '@/lib/utils'

const LAG_WARN_THRESHOLD = 50

const REFRESH_INTERVAL_MS = 5000

type OpsConsoleSnapshot = {
  diffs: OpsSettlementDiffsResponse | null
  errors: string[]
  feeds: OpsFeedsResponse | null
  generatedAt: string
  ingest: OpsIngestResponse | null
  review: ScoreTruthReviewQueueResponse | null
  session: PaperTradingSession | null
  streams: OpsStreamsResponse | null
  scrape: ScrapeStatus | null
}

export function OpsConsoleRoute() {
  const [snapshot, setSnapshot] = useState<OpsConsoleSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadConsole = useCallback(async (background: boolean) => {
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    const [feeds, ingest, streams, diffs, review, session, scrape] = await Promise.allSettled([
      fetchOpsFeeds(),
      fetchOpsIngest(),
      fetchOpsStreams(),
      fetchOpsDiffs({ focus: 'DISAGREEMENT', page: 0, size: 8 }),
      fetchScoreTruthReviewQueue({ page: 0, size: 8 }),
      fetchLiveSession(),
      fetchScrapeStatus(),
    ])

    if (!mountedRef.current) {
      return
    }

    setSnapshot({
      diffs: settledValue(diffs),
      errors: [
        settledError('Feeds', feeds),
        settledError('Ingest', ingest),
        settledError('Streams', streams),
        settledError('Settlement diffs', diffs),
        settledError('Review queue', review),
        settledError('Live session', session),
        settledError('Scrape status', scrape),
      ].filter((value): value is string => Boolean(value)),
      feeds: settledValue(feeds),
      generatedAt: new Date().toISOString(),
      ingest: settledValue(ingest),
      review: settledValue(review),
      session: settledValue(session),
      streams: settledValue(streams),
      scrape: settledValue(scrape),
    })

    if (background) {
      setRefreshing(false)
    } else {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadConsole(false)
    const interval = window.setInterval(() => {
      void loadConsole(true)
    }, REFRESH_INTERVAL_MS)
    return () => window.clearInterval(interval)
  }, [loadConsole])

  const posture = useMemo(() => summarizePosture(snapshot), [snapshot])

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Ops Console"
      description="One control-room page for feed health, ingestion pressure, stream-worker readiness, settlement replay, and manual-review backlog."
      badges={
        <>
          <Badge variant="accent">Ops Console</Badge>
          <Badge>Auto Refresh 5s</Badge>
        </>
      }
      actions={
        <Button variant="secondary" onClick={() => void loadConsole(true)} disabled={loading || refreshing}>
          <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
          Refresh
        </Button>
      }
    >
      <section className="grid gap-5 xl:grid-cols-[1.1fr_0.9fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Console Snapshot
            </Badge>
            <CardTitle>Operational posture across v3 surfaces</CardTitle>
            <CardDescription>
              This page composes the already-promoted Review Queue, Ops Feeds, Ingest, Streams, and Settlement Diffs
              contracts into one fast operator pass.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            {loading && !snapshot ? <Placeholder label="Loading ops console snapshot..." /> : null}

            {snapshot ? (
              <>
                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                  <MetricTile icon={RadioTower} label="Feed Watch" value={String(posture.feedWatch)} />
                  <MetricTile icon={TimerReset} label="DLQ Depth" value={formatNumber(posture.dlqDepth)} />
                  <MetricTile icon={GitCompareArrows} label="Replay Diffs" value={formatNumber(posture.diffRows)} />
                  <MetricTile icon={ShieldCheck} label="Manual Review" value={formatNumber(posture.reviewRows)} />
                </div>

                <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.22em] text-[var(--ink-muted)]">
                        Snapshot generated
                      </p>
                      <p className="mt-2 text-sm font-semibold text-[var(--ink-strong)]">
                        {formatDateTime(snapshot.generatedAt)}
                      </p>
                    </div>
                    <PosturePill tone={posture.tone} label={posture.label} />
                  </div>
                </div>
              </>
            ) : null}

            {snapshot?.errors.length ? (
              <InlineAlert>
                {snapshot.errors.join(' | ')}
              </InlineAlert>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge className="w-fit">Attention Stack</Badge>
            <CardTitle>What to inspect first</CardTitle>
            <CardDescription>
              These are the shortest jumps into the underlying route when a number on the console needs investigation.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            <AttentionLink
              detail={`${posture.feedWatch} feed(s) outside healthy, ${formatNumber(posture.dlqDepth)} DLQ row(s).`}
              icon={RadioTower}
              label="Inspect feed health"
              to="/admin/feeds"
              tone={posture.feedWatch > 0 ? 'warn' : 'ok'}
            />
            <AttentionLink
              detail={`${formatNumber(posture.maxLag)} max stream lag (warn ≥ ${snapshot?.ingest?.bus.partitionLagWarning ?? LAG_WARN_THRESHOLD}), bus ${snapshot?.ingest?.bus.status ?? 'unknown'}.`}
              icon={DatabaseZap}
              label="Inspect ingestion bus"
              to="/admin/ingest"
              tone={
                posture.maxLag >= (snapshot?.ingest?.bus.partitionLagWarning ?? LAG_WARN_THRESHOLD)
                  || isIngestBusUnhealthy(snapshot?.ingest?.bus.status)
                  ? 'warn'
                  : 'ok'
              }
            />
            <AttentionLink
              detail={`${snapshot?.streams?.summary.activeWorkers ?? 0}/${snapshot?.streams?.summary.totalWorkers ?? 0} workers active; ${snapshot?.streams?.summary.availableComponents ?? 0} components configured.`}
              icon={Activity}
              label="Inspect stream workers"
              to="/admin/streams"
              tone="ok"
            />
            <AttentionLink
              detail={`${formatNumber(posture.diffRows)} filtered disagreement row(s), ${snapshot?.diffs?.summary.contradictionRows ?? 0} contradiction(s).`}
              icon={GitCompareArrows}
              label="Inspect settlement diffs"
              to="/admin/diffs?focus=DISAGREEMENT"
              tone={posture.diffRows > 0 ? 'warn' : 'ok'}
            />
            <AttentionLink
              detail={`${formatNumber(posture.reviewRows)} manual-review row(s) currently queued.`}
              icon={ShieldCheck}
              label="Open review queue"
              to="/admin/review"
              tone={posture.reviewRows > 0 ? 'warn' : 'ok'}
            />
          </CardContent>
        </Card>
      </section>

      <section className="mt-5 grid gap-5 xl:grid-cols-3">
        <ConsolePanel
          description="Per-source SLA, staleness, and DLQ depth."
          href="/admin/feeds"
          icon={RadioTower}
          metrics={[
            ['Healthy', `${snapshot?.feeds?.summary.healthySources ?? 0}/${snapshot?.feeds?.summary.activeSources ?? 0} active`],
            ['Degraded', String(snapshot?.feeds?.summary.degradedSources ?? 0)],
            ['Down', String(snapshot?.feeds?.summary.downSources ?? 0)],
          ]}
          title="Feeds"
        />
        <ConsolePanel
          description="Redis stream mode, lag, pending entries, and DLQ pressure."
          href="/admin/ingest"
          icon={DatabaseZap}
          metrics={[
            ['Bus', snapshot?.ingest?.bus.mode ?? 'N/A'],
            ['Status', snapshot?.ingest?.bus.status ?? 'N/A'],
            ['Partitions', String(snapshot?.ingest?.partitions.length ?? 0)],
          ]}
          title="Ingest"
        />
        <ConsolePanel
          description="Stream-CV components, VLM usage, and route/template inventory."
          href="/admin/streams"
          icon={Activity}
          metrics={[
            ['Active', `${snapshot?.streams?.summary.activeWorkers ?? 0}/${snapshot?.streams?.summary.totalWorkers ?? 0}`],
            ['ROI', String(snapshot?.streams?.summary.roiTemplates ?? 0)],
            ['VLM', String(snapshot?.streams?.summary.activeForceRequests ?? 0)],
          ]}
          title="Streams"
        />
        <ConsolePanel
          description="Primary settlement outcomes against shadow replay."
          href="/admin/diffs"
          icon={GitCompareArrows}
          metrics={[
            ['Rows', String(snapshot?.diffs?.summary.totalRows ?? 0)],
            ['Diffs', String(snapshot?.diffs?.summary.disagreementRows ?? 0)],
            ['Contra', String(snapshot?.diffs?.summary.contradictionRows ?? 0)],
          ]}
          title="Settlement Diffs"
        />
        <ConsolePanel
          description="Human triage for held or ambiguous Score Truth decisions."
          href="/admin/review"
          icon={ShieldCheck}
          metrics={[
            ['Total queued', String(snapshot?.review?.totalItems ?? 0)],
            ['Open this page', String(countOpenReviews(snapshot?.review))],
            ['Already reviewed', String(countResolvedReviews(snapshot?.review))],
          ]}
          title="Review Queue"
        />
        <ConsolePanel
          description="Paper-trade session health — bankroll, ROI, open exposure, settled win/loss tally."
          href="/admin"
          icon={CircleDollarSign}
          metrics={[
            ['Bankroll', snapshot?.session ? `$${snapshot.session.currentBankroll.toFixed(2)}` : '—'],
            ['ROI', snapshot?.session ? `${snapshot.session.roiPct.toFixed(2)}%` : '—'],
            ['W / L / Open', snapshot?.session ? `${snapshot.session.wins} / ${snapshot.session.losses} / ${snapshot.session.openBets}` : '—'],
          ]}
          title="Live Session"
        />
        <ConsolePanel
          description="Tt-series.com match-table backfill. Auto-rebuilds Elo / TS-2 / Weng-Lin / Glicko-2 when new rows land."
          href="/admin/scrape"
          icon={Database}
          metrics={[
            ['State', snapshot?.scrape?.currentState ?? '—'],
            ['Last outcome', snapshot?.scrape?.lastRunStatus ?? '—'],
            ['Saved (last run)', snapshot?.scrape ? formatNumber(snapshot.scrape.savedMatches) : '—'],
            ['Mode', snapshot?.scrape?.mode ?? '—'],
          ]}
          title="Scraper"
        />
      </section>
    </V3Shell>
  )
}

function ConsolePanel({
  description,
  href,
  icon: Icon,
  metrics,
  title,
}: {
  description: string
  href: string
  icon: LucideIcon
  metrics: Array<[string, string]>
  title: string
}) {
  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div>
            <Badge className="w-fit">{title}</Badge>
            <CardTitle className="mt-3">{title}</CardTitle>
          </div>
          <span className="inline-flex size-11 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
            <Icon className="size-5" />
          </span>
        </div>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        <div className="grid gap-3 sm:grid-cols-3 xl:grid-cols-1 2xl:grid-cols-3">
          {metrics.map(([label, value]) => (
            <SmallMetric key={label} label={label} value={value} />
          ))}
        </div>
        <Button variant="secondary" asChild>
          <Link to={href}>Open {title}</Link>
        </Button>
      </CardContent>
    </Card>
  )
}

function AttentionLink({
  detail,
  icon: Icon,
  label,
  to,
  tone,
}: {
  detail: string
  icon: LucideIcon
  label: string
  to: string
  tone: 'ok' | 'warn'
}) {
  return (
    <Link
      className={cn(
        'flex items-start gap-3 rounded-[20px] border p-4 transition-colors',
        tone === 'ok'
          ? 'border-emerald-100 bg-emerald-50/60 text-emerald-900 hover:bg-emerald-50'
          : 'border-amber-200 bg-amber-50/80 text-amber-950 hover:bg-amber-50',
      )}
      to={to}
    >
      <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-2xl bg-[rgba(255,255,255,0.72)]">
        <Icon className="size-4" />
      </span>
      <span>
        <span className="block font-semibold">{label}</span>
        <span className="mt-1 block text-sm leading-6 opacity-80">{detail}</span>
      </span>
    </Link>
  )
}

function MetricTile({
  icon: Icon,
  label,
  value,
}: {
  icon: LucideIcon
  label: string
  value: string
}) {
  return (
    <div className="rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <div className="min-w-0">
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">{label}</p>
          <p className="mt-1 truncate font-serif text-2xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">{value}</p>
        </div>
      </div>
    </div>
  )
}

function SmallMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 rounded-[18px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-3">
      <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-[var(--ink-muted)]">{label}</p>
      <p className="mt-1 truncate text-sm font-semibold text-[var(--ink-strong)]" title={value}>
        {value}
      </p>
    </div>
  )
}

function PosturePill({ label, tone }: { label: string; tone: 'ok' | 'warn' | 'down' }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border px-3 py-1.5 text-xs font-semibold uppercase tracking-[0.18em]',
        tone === 'ok' && 'border-emerald-200 bg-emerald-50 text-emerald-800',
        tone === 'warn' && 'border-amber-200 bg-amber-50 text-amber-900',
        tone === 'down' && 'border-rose-200 bg-rose-50 text-rose-800',
      )}
    >
      {label}
    </span>
  )
}

function Placeholder({ label }: { label: string }) {
  return (
    <div className="rounded-[18px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-5 text-sm text-[var(--ink-muted)]">
      {label}
    </div>
  )
}

function InlineAlert({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-2 rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">
      <AlertTriangle aria-hidden="true" className="size-4" />
      <span>{children}</span>
    </div>
  )
}

function summarizePosture(snapshot: OpsConsoleSnapshot | null) {
  const feedWatch = snapshot
    ? (snapshot.feeds?.summary.degradedSources ?? 0)
      + (snapshot.feeds?.summary.downSources ?? 0)
    : 0
  const dlqDepth = (snapshot?.feeds?.summary.totalDlqDepth ?? 0) + (snapshot?.ingest?.dlq.totalDepth ?? 0)
  const diffRows = snapshot?.diffs?.summary.disagreementRows ?? 0
  const reviewRows = snapshot?.review?.totalItems ?? 0
  const maxLag = snapshot?.ingest?.partitions.reduce((current, partition) => {
    if (partition.lag == null) {
      return current
    }
    return Math.max(current, partition.lag)
  }, 0) ?? 0
  const errors = snapshot?.errors.length ?? 0

  if (errors > 0 || (snapshot?.feeds?.summary.downSources ?? 0) > 0 || isIngestBusUnhealthy(snapshot?.ingest?.bus.status)) {
    return { dlqDepth, diffRows, feedWatch, label: 'Needs attention', maxLag, reviewRows, tone: 'down' as const }
  }
  if (feedWatch > 0 || dlqDepth > 0 || diffRows > 0 || reviewRows > 0 || maxLag >= LAG_WARN_THRESHOLD) {
    return { dlqDepth, diffRows, feedWatch, label: 'Watchlist', maxLag, reviewRows, tone: 'warn' as const }
  }
  return { dlqDepth, diffRows, feedWatch, label: 'Nominal', maxLag, reviewRows, tone: 'ok' as const }
}

function isIngestBusUnhealthy(status: string | undefined): boolean {
  if (!status) return false
  const s = status.toUpperCase()
  return s === 'DOWN' || s === 'DEGRADED' || s === 'UNAVAILABLE' || s === 'HOT'
}

function settledValue<T>(result: PromiseSettledResult<T>) {
  return result.status === 'fulfilled' ? result.value : null
}

function settledError(label: string, result: PromiseSettledResult<unknown>) {
  if (result.status === 'fulfilled') {
    return null
  }
  const message = result.reason instanceof Error ? result.reason.message : 'request failed'
  return `${label}: ${message}`
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(value)
}

function countOpenReviews(review: ScoreTruthReviewQueueResponse | null | undefined): number {
  if (!review) return 0
  return review.items.filter((item) => item.reviewStatus === 'OPEN').length
}

function countResolvedReviews(review: ScoreTruthReviewQueueResponse | null | undefined): number {
  if (!review) return 0
  return review.items.filter((item) => item.reviewStatus !== 'OPEN').length
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
