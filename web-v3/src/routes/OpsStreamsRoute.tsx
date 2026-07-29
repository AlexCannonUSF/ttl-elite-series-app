import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  ArrowLeft,
  Cpu,
  DollarSign,
  Eye,
  FileText,
  Radio,
  RefreshCcw,
  Route,
  ShieldCheck,
  Zap,
} from 'lucide-react'
import { Link } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchOpsStreams } from '@/features/ops-feeds/api'
import type { OpsStreamWorker, OpsStreamsResponse } from '@/features/ops-feeds/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 5000

const workerIcons: Record<string, typeof Cpu> = {
  ROUTER: Route,
  FETCH: Radio,
  SAMPLER: Activity,
  LOCATOR: Eye,
  OCR: FileText,
  VLM: Zap,
}

const statusTone: Record<string, string> = {
  READY: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  OFF: 'border-slate-200 bg-slate-50 text-slate-700',
}

export function OpsStreamsRoute() {
  const [data, setData] = useState<OpsStreamsResponse | null>(null)
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

  const loadStreams = useCallback(async (background: boolean) => {
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    try {
      const next = await fetchOpsStreams()
      if (!mountedRef.current) {
        return
      }
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to load stream worker health right now.')
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
    void loadStreams(false)
    const interval = window.setInterval(() => {
      void loadStreams(true)
    }, REFRESH_INTERVAL_MS)

    return () => {
      window.clearInterval(interval)
    }
  }, [loadStreams])

  const disabledWorkers = useMemo(() => data?.workers.filter((worker) => !worker.enabled) ?? [], [data])

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Stream Workers"
      description="Stream-CV worker readiness, route/template inventory, and VLM fallback pressure for operators watching the video-derived score path."
      badges={
        <>
          <Badge variant="accent">Stream Workers</Badge>
          <Badge>Auto Refresh 5s</Badge>
        </>
      }
      actions={
        <>
          <Button variant="ghost" asChild>
            <Link to="/ops/feeds">
              <ArrowLeft className="size-4" />
              Ops Feeds
            </Link>
          </Button>
          <Button variant="secondary" onClick={() => void loadStreams(true)} disabled={loading || refreshing}>
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
              Worker Summary
            </Badge>
            <CardTitle>Stream-CV readiness at a glance</CardTitle>
            <CardDescription>
              Route resolution, fetch planning, frame sampling, board location, OCR, and VLM fallback are checked as
              separate operator-visible workers.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            {loading && !data ? (
              <div className="rounded-[24px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
                Loading stream worker snapshot…
              </div>
            ) : null}

            {data ? (
              <>
                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                  <MetricTile label="Workers Ready" value={`${data.summary.enabledWorkers}/${data.summary.totalWorkers}`} icon={Cpu} />
                  <MetricTile label="Route Overrides" value={String(data.summary.routeOverrides)} icon={Route} />
                  <MetricTile label="ROI Templates" value={String(data.summary.roiTemplates)} icon={Eye} />
                  <MetricTile label="Force VLM" value={String(data.summary.activeForceRequests)} icon={Zap} />
                </div>
                <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">
                        Snapshot generated
                      </p>
                      <p className="mt-2 text-sm font-medium text-[var(--ink-strong)]">{formatDateTime(data.generatedAt)}</p>
                    </div>
                    <div className="text-right text-sm text-[var(--ink-muted)]">
                      <p>{refreshing ? 'Refreshing in place…' : 'Live polling active'}</p>
                      <p className="mt-1">{data.summary.routeWarnings} route catalog warning(s)</p>
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
            <Badge className="w-fit">VLM Usage</Badge>
            <CardTitle>Fallback pressure and metering state</CardTitle>
            <CardDescription>
              Operator force requests separated from paid VLM calls; VLM call metering arrives when the upstream client is wired.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            {data ? (
              <>
                <div className="grid gap-3 sm:grid-cols-2">
                  <MiniMetric label="Metering" value={formatState(data.vlmUsage.meteringState)} icon={ShieldCheck} />
                  <MiniMetric label="Active Force" value={String(data.vlmUsage.activeForceRequests)} icon={Zap} />
                  <MiniMetric label="Frames Sent" value={String(data.vlmUsage.framesSentToday)} icon={Activity} />
                  <MiniMetric label="Spend Today" value={formatMoney(data.vlmUsage.estimatedCostUsdToday)} icon={DollarSign} />
                </div>
                <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4 text-sm leading-6 text-[var(--ink-muted)]">
                  {data.vlmUsage.detail}
                </div>
              </>
            ) : (
              <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
                No VLM usage snapshot available yet.
              </div>
            )}
          </CardContent>
        </Card>
      </section>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Worker Inventory
          </Badge>
          <CardTitle>Stream-CV pipeline components</CardTitle>
          <CardDescription>
            Each row is a backend component that can be promoted, disabled, or inspected independently as the video
            score path moves toward advisory operation.
          </CardDescription>
        </CardHeader>
        <CardContent className="mt-5 overflow-x-auto">
          {data ? (
            <table className="min-w-full border-separate border-spacing-y-3">
              <thead>
                <tr className="text-left text-xs uppercase tracking-[0.22em] text-[var(--ink-muted)]">
                  <th className="px-3 pb-1 font-semibold">Worker</th>
                  <th className="px-3 pb-1 font-semibold">Status</th>
                  <th className="px-3 pb-1 font-semibold">Rollout</th>
                  <th className="px-3 pb-1 font-semibold">Detail</th>
                </tr>
              </thead>
              <tbody>
                {data.workers.map((worker) => (
                  <WorkerRow key={worker.component} worker={worker} />
                ))}
              </tbody>
            </table>
          ) : (
            <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
              No worker data available yet.
            </div>
          )}
        </CardContent>
      </Card>

      {disabledWorkers.length > 0 ? (
        <Card className="mt-5">
          <CardHeader>
            <Badge className="w-fit">Disabled Workers</Badge>
            <CardTitle>What is not active yet</CardTitle>
            <CardDescription>
              These workers are visible but held by rollout flags or local configuration.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {disabledWorkers.map((worker) => (
              <div key={worker.component} className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                <p className="font-medium text-[var(--ink-strong)]">{worker.component}</p>
                <p className="mt-1 text-sm text-[var(--ink-muted)]">{worker.detail}</p>
              </div>
            ))}
          </CardContent>
        </Card>
      ) : null}
    </V3Shell>
  )
}

function WorkerRow({ worker }: { worker: OpsStreamWorker }) {
  const Icon = workerIcons[worker.workerType] ?? Cpu
  return (
    <tr className="rounded-[24px] bg-[rgba(255,255,255,0.76)] shadow-[0_18px_48px_-40px_rgba(8,25,28,0.72)]">
      <td className="rounded-l-[22px] px-3 py-4 align-top">
        <div className="flex items-start gap-3">
          <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
            <Icon className="size-4" />
          </span>
          <div>
            <p className="font-medium text-[var(--ink-strong)]">{worker.component}</p>
            <p className="mt-1 text-sm text-[var(--ink-muted)]">{formatState(worker.workerType)}</p>
          </div>
        </div>
      </td>
      <td className="px-3 py-4 align-top">
        <StatusPill status={worker.status} />
      </td>
      <td className="px-3 py-4 align-top text-sm text-[var(--ink)]">
        <p className="font-medium text-[var(--ink-strong)]">{formatState(worker.rolloutState)}</p>
        <p className="mt-1 text-[var(--ink-muted)]">{worker.enabled ? 'Enabled by rollout' : 'Held by rollout'}</p>
      </td>
      <td className="rounded-r-[22px] px-3 py-4 align-top text-sm leading-6 text-[var(--ink-muted)]">
        {worker.detail}
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
  icon: typeof Cpu
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

function MiniMetric({
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
        <span className="inline-flex size-9 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <p className="text-xs font-semibold uppercase tracking-[0.22em]">{label}</p>
      </div>
      <p className="mt-3 text-sm font-medium text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function StatusPill({ status }: { status: string }) {
  return (
    <span
      className={cn(
        'inline-flex w-fit items-center rounded-full border px-3 py-1.5 text-xs font-semibold uppercase tracking-[0.22em]',
        statusTone[status] ?? 'border-amber-200 bg-amber-50 text-amber-800',
      )}
    >
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
  return value.replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (match) => match.toUpperCase())
}

function formatMoney(value: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 2,
  }).format(value)
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
