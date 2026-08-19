import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  DatabaseZap,
  Gauge,
  ListRestart,
  RadioTower,
  RefreshCcw,
  ServerCrash,
  ShieldCheck,
  TimerReset,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchOpsIngest } from '@/features/ops-feeds/api'
import type { OpsIngestPartition, OpsIngestResponse } from '@/features/ops-feeds/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 5000

const statusTone: Record<string, string> = {
  HEALTHY: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  DEGRADED: 'border-amber-200 bg-amber-50 text-amber-800',
  DOWN: 'border-rose-200 bg-rose-50 text-rose-800',
  HOT: 'border-rose-200 bg-rose-50 text-rose-800',
  LAGGING: 'border-amber-200 bg-amber-50 text-amber-800',
  IDLE: 'border-slate-200 bg-slate-50 text-slate-700',
  OFF: 'border-slate-200 bg-slate-50 text-slate-700',
  UNAVAILABLE: 'border-slate-200 bg-slate-50 text-slate-700',
}

export function OpsIngestRoute() {
  const [data, setData] = useState<OpsIngestResponse | null>(null)
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

  const loadIngest = useCallback(async (background: boolean) => {
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    try {
      const next = await fetchOpsIngest()
      if (!mountedRef.current) {
        return
      }
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to load ingestion bus health right now.')
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
    void loadIngest(false)
    const interval = window.setInterval(() => {
      void loadIngest(true)
    }, REFRESH_INTERVAL_MS)

    return () => {
      window.clearInterval(interval)
    }
  }, [loadIngest])

  const maxLag = useMemo(() => {
    if (!data) {
      return null
    }
    return data.partitions.reduce<number | null>((current, partition) => {
      if (partition.lag === null) {
        return current
      }
      return current === null ? partition.lag : Math.max(current, partition.lag)
    }, null)
  }, [data])

  const laggingPartitions = useMemo(
    () => data?.partitions.filter((partition) => ['HOT', 'LAGGING', 'UNAVAILABLE'].includes(partition.status)) ?? [],
    [data],
  )

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Ops Ingest"
      description="Redis Streams bus state, dead-letter pressure, and stream partition lag."
      badges={
        <>
          <Badge variant="accent">Ingest</Badge>
          <Badge>Auto Refresh 5s</Badge>
        </>
      }
      actions={
        <>
          <Button variant="ghost" asChild>
            <Link to="/admin/feeds">
              <Activity className="size-4" />
              Ops Feeds
            </Link>
          </Button>
          <Button variant="secondary" onClick={() => void loadIngest(true)} disabled={loading || refreshing}>
            <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
            Refresh now
          </Button>
        </>
      }
    >
      <section className="grid gap-5 xl:grid-cols-[1.08fr_0.92fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Bus Health
            </Badge>
            <CardTitle>Ingestion delivery state</CardTitle>
            <CardDescription>
              The active bus, Redis reachability, and queue pressure are read from the backend contract that powers this
              route.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            {loading && !data ? (
              <div className="rounded-[24px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
                Loading ingestion snapshot...
              </div>
            ) : null}

            {data ? (
              <>
                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                  <MetricTile label="Bus" value={formatState(data.bus.mode)} icon={RadioTower} />
                  <MetricTile label="DLQ" value={formatNumber(data.dlq.totalDepth)} icon={ListRestart} />
                  <MetricTile label="Max Lag" value={formatLag(maxLag)} icon={Gauge} />
                  <MetricTile
                    label="Redis"
                    value={data.bus.redisAvailable ? 'Online' : 'Offline'}
                    icon={data.bus.redisAvailable ? DatabaseZap : ServerCrash}
                  />
                </div>
                <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <StatusPill status={data.bus.status} />
                      <p className="mt-3 text-sm font-medium text-[var(--ink-strong)]">{data.bus.activeBus}</p>
                      <p className="mt-1 text-sm text-[var(--ink-muted)]">Prefix: {data.bus.streamPrefix}</p>
                    </div>
                    <div className="max-w-xl text-right text-sm leading-6 text-[var(--ink-muted)]">
                      <p>{data.bus.detail}</p>
                      <p className="mt-1">Snapshot: {formatDateTime(data.generatedAt)}</p>
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
            <Badge className="w-fit">Pressure</Badge>
            <CardTitle>What needs attention</CardTitle>
            <CardDescription>
              Lagging partitions and non-empty DLQ sources are grouped here before the detailed stream table.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            {data && laggingPartitions.length === 0 && data.dlq.sources.length === 0 ? (
              <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4 text-sm text-[var(--ink-muted)]">
                No partition or dead-letter pressure is visible in this snapshot.
              </div>
            ) : null}

            {laggingPartitions.map((partition) => (
              <div
                key={partition.streamKey}
                className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4"
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <p className="font-medium text-[var(--ink-strong)]">{partition.streamKey}</p>
                    <p className="mt-1 text-sm text-[var(--ink-muted)]">{partition.detail}</p>
                  </div>
                  <StatusPill status={partition.status} />
                </div>
              </div>
            ))}

            {data?.dlq.sources.map((source) => (
              <div
                key={source.sourceId}
                className="rounded-[22px] border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
              >
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="font-medium">{source.sourceId}</p>
                    <p className="mt-1">{formatTrustTier(source.trustTier)}</p>
                  </div>
                  <p className="font-serif text-2xl font-semibold">{formatNumber(source.depth)}</p>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      </section>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">Parity & Soak</Badge>
          <CardTitle>Redis process-lifetime delivery evidence</CardTitle>
          <CardDescription>
            Publisher, decoder, acknowledgement, rejection, and soak counters are shown separately. A seven-day pass
            is never inferred from an empty stream or a restarted process.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {data ? (
            <>
              <MetricTile label="Published / Ack" value={`${formatNumber(data.telemetry.published)} / ${formatNumber(data.telemetry.acknowledged)}`} icon={RadioTower} />
              <MetricTile label="Parity Delta" value={formatNumber(data.telemetry.parityDelta)} icon={Gauge} />
              <MetricTile label="Rejected / DLQ" value={`${formatNumber(data.telemetry.rejected)} / ${formatNumber(data.telemetry.dlq)}`} icon={ListRestart} />
              <MetricTile label="Soak" value={formatState(data.telemetry.soakStatus)} icon={DatabaseZap} />
              <MetricTile label="Validated / Sent" value={`${formatNumber(data.telemetry.validated)} / ${formatNumber(data.telemetry.dispatched)}`} icon={ShieldCheck} />
              <MetricTile label="Throughput" value={`${formatDecimal(data.telemetry.throughputPerMinute)} / min`} icon={Activity} />
              <MetricTile label="Consumer Heartbeat" value={formatDateTime(data.telemetry.consumerHeartbeatAt)} icon={TimerReset} />
              <MetricTile label="Latest Event Age" value={formatMilliseconds(data.telemetry.latestEventAgeMs)} icon={Gauge} />
            </>
          ) : null}
        </CardContent>
      </Card>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Redis Streams
          </Badge>
          <CardTitle>Partition lag by stream family</CardTitle>
          <CardDescription>
            Stream length, consumer group count, pending entries, and computed lag are shown for each ingestion family.
          </CardDescription>
        </CardHeader>
        <CardContent className="mt-5 overflow-x-auto">
          {data ? (
            <table className="min-w-full border-separate border-spacing-y-3">
              <thead>
                <tr className="text-left text-xs uppercase text-[var(--ink-muted)]">
                  <th className="px-3 pb-1 font-semibold">Stream</th>
                  <th className="px-3 pb-1 font-semibold">Status</th>
                  <th className="px-3 pb-1 font-semibold">Length</th>
                  <th className="px-3 pb-1 font-semibold">Groups</th>
                  <th className="px-3 pb-1 font-semibold">Pending</th>
                  <th className="px-3 pb-1 font-semibold">Lag</th>
                  <th className="px-3 pb-1 font-semibold">Last Id</th>
                </tr>
              </thead>
              <tbody>
                {data.partitions.map((partition) => (
                  <PartitionRow key={partition.streamKey} partition={partition} />
                ))}
              </tbody>
            </table>
          ) : (
            <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
              No ingestion partition data available yet.
            </div>
          )}
        </CardContent>
      </Card>
    </V3Shell>
  )
}

function PartitionRow({ partition }: { partition: OpsIngestPartition }) {
  return (
    <tr className="rounded-[24px] bg-[rgba(255,255,255,0.76)] shadow-[0_18px_48px_-40px_rgba(8,25,28,0.72)]">
      <td className="rounded-l-[22px] px-3 py-4 align-top">
        <p className="font-medium text-[var(--ink-strong)]">{partition.streamKey}</p>
        <p className="mt-1 text-sm text-[var(--ink-muted)]">{partition.family}</p>
      </td>
      <td className="px-3 py-4 align-top">
        <StatusPill status={partition.status} />
      </td>
      <td className="px-3 py-4 align-top text-sm font-medium text-[var(--ink-strong)]">
        {formatNumber(partition.streamLength)}
      </td>
      <td className="px-3 py-4 align-top text-sm text-[var(--ink)]">{formatNumber(partition.consumerGroups)}</td>
      <td className="px-3 py-4 align-top text-sm text-[var(--ink)]">
        <p>{formatNumber(partition.pendingCount)}</p>
        <p className="mt-1 text-xs text-[var(--ink-muted)]">
          {partition.oldestPendingAgeSeconds === null ? 'No pending age' : `oldest ${formatSeconds(partition.oldestPendingAgeSeconds)}`}
          {' · '}{formatNumber(partition.redeliveryCount)} redeliveries
        </p>
      </td>
      <td className="px-3 py-4 align-top text-sm font-medium text-[var(--ink-strong)]">{formatLag(partition.lag)}</td>
      <td className="rounded-r-[22px] px-3 py-4 align-top text-sm text-[var(--ink-muted)]">
        {partition.lastGeneratedId ?? 'None'}
      </td>
    </tr>
  )
}

function MetricTile({
  label,
  value,
  icon: Icon,
}: {
  label: string
  value: string
  icon: typeof RadioTower
}) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3 text-[var(--ink-muted)]">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <p className="text-xs font-semibold uppercase">{label}</p>
      </div>
      <p className="mt-4 font-serif text-2xl font-semibold text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function StatusPill({ status }: { status: string }) {
  return (
    <span
      className={cn(
        'inline-flex w-fit items-center gap-2 rounded-full border px-3 py-1.5 text-xs font-semibold uppercase',
        statusTone[status] ?? 'border-slate-200 bg-slate-50 text-slate-700',
      )}
    >
      <span className="size-2 rounded-full bg-current opacity-70" />
      {formatState(status)}
    </span>
  )
}

function InlineAlert({ children }: { children: ReactNode }) {
  return (
    <div className="inline-flex items-center gap-2 rounded-[18px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800" role="alert">
      {children}
    </div>
  )
}

function formatState(value: string) {
  return value
    .replaceAll('_', ' ')
    .replaceAll('-', ' ')
    .toLowerCase()
    .replace(/\b\w/g, (match) => match.toUpperCase())
}

function formatTrustTier(value: string) {
  return value
    .replace(/^T(\d)_/, 'T$1 ')
    .replaceAll('_', ' ')
    .toLowerCase()
    .replace(/\b\w/g, (match) => match.toUpperCase())
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('en-US').format(value)
}

function formatDecimal(value: number | null) {
  if (value === null) {
    return 'N/A'
  }
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 }).format(value)
}

function formatMilliseconds(value: number | null) {
  if (value === null) {
    return 'N/A'
  }
  return value < 1000 ? `${formatNumber(value)} ms` : formatSeconds(Math.round(value / 1000))
}

function formatSeconds(value: number) {
  if (value < 60) {
    return `${formatNumber(value)}s`
  }
  if (value < 3600) {
    return `${Math.floor(value / 60)}m ${value % 60}s`
  }
  return `${Math.floor(value / 3600)}h ${Math.floor((value % 3600) / 60)}m`
}

function formatLag(value: number | null) {
  if (value === null) {
    return 'N/A'
  }
  return formatNumber(value)
}

function formatDateTime(value: string | null) {
  if (!value) {
    return 'No snapshot'
  }

  return new Intl.DateTimeFormat('en-US', {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value))
}
