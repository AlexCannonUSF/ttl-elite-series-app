import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, Clock3, Radio, RefreshCcw, ShieldAlert, TimerReset, Waves } from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchOpsFeeds } from '@/features/ops-feeds/api'
import type { FeedStatus, OpsFeedStatus, OpsFeedsResponse } from '@/features/ops-feeds/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 5000

const statusTone: Record<FeedStatus, { pill: string; dot: string; label: string }> = {
  HEALTHY: {
    pill: 'border-emerald-200 bg-emerald-50 text-emerald-800',
    dot: 'bg-emerald-500',
    label: 'Healthy',
  },
  DEGRADED: {
    pill: 'border-amber-200 bg-amber-50 text-amber-800',
    dot: 'bg-amber-500',
    label: 'Degraded',
  },
  DOWN: {
    pill: 'border-rose-200 bg-rose-50 text-rose-800',
    dot: 'bg-rose-500',
    label: 'Down',
  },
  IDLE: {
    pill: 'border-slate-200 bg-slate-50 text-slate-700',
    dot: 'bg-slate-400',
    label: 'Idle',
  },
}

export function OpsFeedsRoute() {
  const [data, setData] = useState<OpsFeedsResponse | null>(null)
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

  const loadFeeds = useCallback(async (background: boolean) => {
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    try {
      const next = await fetchOpsFeeds()
      if (!mountedRef.current) {
        return
      }
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to load feed health right now.')
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
  }, [])

  useEffect(() => {
    void loadFeeds(false)
    const interval = window.setInterval(() => {
      void loadFeeds(true)
    }, REFRESH_INTERVAL_MS)

    return () => {
      window.clearInterval(interval)
    }
  }, [loadFeeds])

  const attentionFeeds = useMemo(() => {
    if (!data) {
      return []
    }
    return data.feeds.filter((feed) => feed.status !== 'HEALTHY').slice(0, 4)
  }, [data])

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Ops Feeds"
      description="Unified feed health for sportsbook, mirror, and confirmation sources. This page is the operational readout for latency, freshness, and queue pressure before settlement logic starts trusting a source."
      badges={
        <>
          <Badge variant="accent">Phase 01</Badge>
          <Badge>Live Health</Badge>
          <Badge>Auto Refresh 5s</Badge>
        </>
      }
      actions={
        <>
          <Button variant="ghost" asChild>
            <Link to="/ops/feeds/streams">
              <Radio className="size-4" />
              Stream Workers
            </Link>
          </Button>
          <Button variant="secondary" onClick={() => void loadFeeds(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
            Refresh now
          </Button>
        </>
      }
    >
      <section className="grid gap-5 xl:grid-cols-[1.18fr_0.82fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Feed Summary
            </Badge>
            <CardTitle>Current operational posture</CardTitle>
            <CardDescription>
              One card to answer whether the ingestion stack is fresh, stable, and safe to trust for downstream work.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-5">
            {loading && !data ? (
              <div className="rounded-[24px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
                Loading feed health snapshot…
              </div>
            ) : null}

            {data ? (
              <>
                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                  <MetricTile label="Total Sources" value={String(data.summary.totalSources)} icon={Waves} />
                  <MetricTile label="Healthy" value={String(data.summary.healthySources)} icon={Clock3} />
                  <MetricTile
                    label="Watchlist"
                    value={String(data.summary.degradedSources + data.summary.downSources)}
                    icon={ShieldAlert}
                  />
                  <MetricTile label="DLQ Depth" value={String(data.summary.totalDlqDepth)} icon={TimerReset} />
                </div>
                <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">
                        Snapshot generated
                      </p>
                      <p className="mt-2 text-sm font-medium text-[var(--ink-strong)]">
                        {formatDateTime(data.generatedAt)}
                      </p>
                    </div>
                    <div className="text-right text-sm text-[var(--ink-muted)]">
                      <p>{refreshing ? 'Refreshing in place…' : 'Live polling active'}</p>
                      <p className="mt-1">Next heartbeat every {REFRESH_INTERVAL_MS / 1000}s</p>
                    </div>
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
            <Badge className="w-fit">Attention Now</Badge>
            <CardTitle>Sources that need a closer look</CardTitle>
            <CardDescription>
              Degraded, down, or idle sources are surfaced here first so we can judge where score continuity or later
              fallback work may be vulnerable.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            {attentionFeeds.length === 0 && data ? (
              <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4 text-sm text-[var(--ink-muted)]">
                No feeds are currently outside the healthy band.
              </div>
            ) : null}

            {attentionFeeds.map((feed) => (
              <div
                key={feed.sourceId}
                className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4"
              >
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <p className="font-medium text-[var(--ink-strong)]">{feed.sourceId}</p>
                    <p className="mt-1 text-sm text-[var(--ink-muted)]">{formatTrustTier(feed.trustTier)}</p>
                  </div>
                  <StatusPill status={feed.status} liveTick={feed.liveTick} />
                </div>
                <div className="mt-3 grid gap-2 text-sm text-[var(--ink-muted)]">
                  <p>Last seen: {formatRelative(feed.lastSuccessAt, 'No live pull yet')}</p>
                  <p>DLQ depth: {feed.dlqDepth}</p>
                  <p>Backoff: {feed.backoffState ?? 'IDLE'}</p>
                  <p className="line-clamp-2">Last error: {feed.lastError ?? 'None recorded'}</p>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      </section>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Per-Source Feed Table
          </Badge>
          <CardTitle>Live health ticks</CardTitle>
          <CardDescription>
            This table combines live `FeedHealth` state, the latest persisted health sample, and current DLQ depth for
            each registered source.
          </CardDescription>
        </CardHeader>
        <CardContent className="mt-5 overflow-x-auto">
          {data ? (
            <table className="min-w-full border-separate border-spacing-y-3">
              <thead>
                <tr className="text-left text-xs uppercase tracking-[0.22em] text-[var(--ink-muted)]">
                  <th className="px-3 pb-1 font-semibold">Source</th>
                  <th className="px-3 pb-1 font-semibold">Health</th>
                  <th className="px-3 pb-1 font-semibold">Success 5m</th>
                  <th className="px-3 pb-1 font-semibold">Latency</th>
                  <th className="px-3 pb-1 font-semibold">Last Seen</th>
                  <th className="px-3 pb-1 font-semibold">DLQ</th>
                  <th className="px-3 pb-1 font-semibold">Backoff</th>
                </tr>
              </thead>
              <tbody>
                {data.feeds.map((feed) => (
                  <FeedRow key={feed.sourceId} feed={feed} />
                ))}
              </tbody>
            </table>
          ) : (
            <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
              No feed data available yet.
            </div>
          )}
        </CardContent>
      </Card>
    </V3Shell>
  )
}

type MetricTileProps = {
  label: string
  value: string
  icon: typeof Clock3
}

function MetricTile({ label, value, icon: Icon }: MetricTileProps) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">{label}</p>
          <p className="mt-2 font-serif text-2xl font-semibold tracking-[-0.04em] text-[var(--ink-strong)]">
            {value}
          </p>
        </div>
      </div>
    </div>
  )
}

function FeedRow({ feed }: { feed: OpsFeedStatus }) {
  return (
    <tr className="rounded-[24px] bg-[rgba(255,255,255,0.76)] shadow-[0_18px_48px_-40px_rgba(8,25,28,0.72)]">
      <td className="rounded-l-[22px] px-3 py-4 align-top">
        <div className="space-y-2">
          <div>
            <p className="font-medium text-[var(--ink-strong)]">{feed.sourceId}</p>
            <p className="mt-1 text-sm text-[var(--ink-muted)]">{formatTrustTier(feed.trustTier)}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            {feed.capabilities.map((capability) => (
              <Badge key={capability}>{capability.replaceAll('_', ' ')}</Badge>
            ))}
          </div>
        </div>
      </td>
      <td className="px-3 py-4 align-top">
        <div className="space-y-2">
          <StatusPill status={feed.status} liveTick={feed.liveTick} />
          <p className="text-sm text-[var(--ink-muted)]">
            Latest sample: {formatRelative(feed.lastSampleAt, 'Not persisted yet')}
          </p>
          {feed.lastError ? <p className="max-w-sm text-sm text-amber-700">{feed.lastError}</p> : null}
        </div>
      </td>
      <td className="px-3 py-4 align-top text-sm text-[var(--ink)]">
        <p className="font-medium text-[var(--ink-strong)]">{formatPercent(feed.successRate5m)}</p>
        <p className="mt-1 text-[var(--ink-muted)]">In flight: {feed.inFlight}</p>
      </td>
      <td className="px-3 py-4 align-top text-sm text-[var(--ink)]">
        <p className="font-medium text-[var(--ink-strong)]">p50 {formatLatency(feed.p50LatencyMs)}</p>
        <p className="mt-1 text-[var(--ink-muted)]">p95 {formatLatency(feed.p95LatencyMs)}</p>
      </td>
      <td className="px-3 py-4 align-top text-sm text-[var(--ink)]">
        <p className="font-medium text-[var(--ink-strong)]">{formatRelative(feed.lastSuccessAt, 'Never seen')}</p>
        <p className="mt-1 text-[var(--ink-muted)]">Staleness {formatStaleness(feed.stalenessSeconds)}</p>
        <p className="mt-1 text-[var(--ink-muted)]">Failure {formatRelative(feed.lastFailureAt, 'No failures')}</p>
      </td>
      <td className="px-3 py-4 align-top text-sm text-[var(--ink)]">
        <p
          className={cn(
            'font-medium',
            feed.dlqDepth > 0 ? 'text-amber-700' : 'text-[var(--ink-strong)]',
          )}
        >
          {feed.dlqDepth}
        </p>
        <p className="mt-1 text-[var(--ink-muted)]">{feed.dlqDepth > 0 ? 'Needs replay' : 'Queue clear'}</p>
      </td>
      <td className="rounded-r-[22px] px-3 py-4 align-top text-sm text-[var(--ink)]">
        <p className="font-medium text-[var(--ink-strong)]">{feed.backoffState ?? 'IDLE'}</p>
        <p className="mt-1 text-[var(--ink-muted)]">{formatDateTime(feed.lastSampleAt)}</p>
      </td>
    </tr>
  )
}

function StatusPill({ status, liveTick }: { status: FeedStatus; liveTick: boolean }) {
  const tone = statusTone[status]

  return (
    <span
      className={cn(
        'inline-flex w-fit items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-semibold uppercase tracking-[0.22em]',
        tone.pill,
      )}
    >
      <span className={cn('size-2 rounded-full', tone.dot, liveTick && 'animate-pulse')} />
      {tone.label}
    </span>
  )
}

function InlineAlert({ children }: { children: ReactNode }) {
  return (
    <div className="inline-flex items-center gap-2 rounded-[18px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
      {children}
    </div>
  )
}

function formatTrustTier(value: string) {
  return value
    .replace(/^T(\d)_/, 'T$1 ')
    .replaceAll('_', ' ')
    .toLowerCase()
    .replace(/\b\w/g, (match) => match.toUpperCase())
}

function formatPercent(value: number | null) {
  if (value === null) {
    return 'Waiting'
  }
  return `${(value * 100).toFixed(1)}%`
}

function formatLatency(value: number | null) {
  if (value === null) {
    return 'N/A'
  }
  return `${Math.round(value)} ms`
}

function formatStaleness(value: number | null) {
  if (value === null) {
    return 'N/A'
  }
  if (value < 60) {
    return `${value}s`
  }
  const minutes = Math.floor(value / 60)
  const seconds = value % 60
  return `${minutes}m ${seconds}s`
}

function formatRelative(value: string | null, fallback: string) {
  if (!value) {
    return fallback
  }

  const diffSeconds = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000))
  if (diffSeconds < 60) {
    return `${diffSeconds}s ago`
  }
  if (diffSeconds < 3600) {
    return `${Math.floor(diffSeconds / 60)}m ago`
  }
  const hours = Math.floor(diffSeconds / 3600)
  const minutes = Math.floor((diffSeconds % 3600) / 60)
  return `${hours}h ${minutes}m ago`
}

function formatDateTime(value: string | null) {
  if (!value) {
    return 'No sample'
  }

  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value))
}
