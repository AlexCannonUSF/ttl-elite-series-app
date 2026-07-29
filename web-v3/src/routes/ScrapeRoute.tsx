import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  Database,
  Hash,
  LayoutList,
  Loader2,
  PlayCircle,
  RefreshCcw,
  Square,
  Timer,
} from 'lucide-react'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  fetchScrapeErrors,
  fetchScrapeRuns,
  fetchScrapeStatus,
  startScrapeById,
  startScrapeRange,
  startScrapeRun,
  stopScrape,
} from '@/features/scrape/api'
import type { ScrapeErrorRecord, ScrapeRunRecord, ScrapeStatus } from '@/features/scrape/types'
import { cn } from '@/lib/utils'

const STATUS_REFRESH_MS = 3000
const RUNS_REFRESH_MS = 6000

type Toast = { kind: 'info' | 'error'; text: string }

export function ScrapeRoute() {
  const [status, setStatus] = useState<ScrapeStatus | null>(null)
  const [runs, setRuns] = useState<ScrapeRunRecord[]>([])
  const [errors, setErrors] = useState<ScrapeErrorRecord[]>([])
  const [statusError, setStatusError] = useState<string | null>(null)
  const [toast, setToast] = useState<Toast | null>(null)
  const [busy, setBusy] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [fromPage, setFromPage] = useState('1')
  const [toPage, setToPage] = useState('5')
  const [postId, setPostId] = useState('')

  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadStatus = useCallback(async (background: boolean) => {
    if (mountedRef.current && background) setRefreshing(true)
    try {
      const next = await fetchScrapeStatus()
      if (!mountedRef.current) return
      setStatus(next)
      setStatusError(null)
    } catch (next) {
      if (!mountedRef.current) return
      setStatusError(next instanceof Error ? next.message : 'Unable to load scrape status.')
    } finally {
      if (mountedRef.current && background) setRefreshing(false)
    }
  }, [])

  const loadRunsAndErrors = useCallback(async () => {
    try {
      const [nextRuns, nextErrors] = await Promise.all([fetchScrapeRuns(20), fetchScrapeErrors(15)])
      if (!mountedRef.current) return
      setRuns(nextRuns)
      setErrors(nextErrors)
    } catch {
      // Status panel already surfaces connectivity errors; keep these silent.
    }
  }, [])

  useEffect(() => {
    void loadStatus(false)
    void loadRunsAndErrors()
    const statusInterval = window.setInterval(() => {
      void loadStatus(true)
    }, STATUS_REFRESH_MS)
    const runsInterval = window.setInterval(() => {
      void loadRunsAndErrors()
    }, RUNS_REFRESH_MS)
    return () => {
      window.clearInterval(statusInterval)
      window.clearInterval(runsInterval)
    }
  }, [loadStatus, loadRunsAndErrors])

  const runAction = useCallback(
    async (label: string, action: () => Promise<string>) => {
      if (busy) return
      setBusy(true)
      setToast(null)
      try {
        const message = await action()
        setToast({ kind: 'info', text: `${label}: ${message.trim() || 'started'}` })
        void loadStatus(true)
        void loadRunsAndErrors()
      } catch (error) {
        setToast({ kind: 'error', text: error instanceof Error ? error.message : `${label} failed` })
      } finally {
        if (mountedRef.current) setBusy(false)
      }
    },
    [busy, loadStatus, loadRunsAndErrors],
  )

  const handleRange = useCallback(
    (event: FormEvent) => {
      event.preventDefault()
      const from = Math.max(1, Number.parseInt(fromPage, 10) || 1)
      const to = Math.max(from, Number.parseInt(toPage, 10) || from)
      void runAction(`Pages ${from}–${to}`, () => startScrapeRange(from, to))
    },
    [fromPage, toPage, runAction],
  )

  const handleById = useCallback(
    (event: FormEvent) => {
      event.preventDefault()
      const id = Number.parseInt(postId, 10)
      if (!Number.isFinite(id) || id <= 0) {
        setToast({ kind: 'error', text: 'Enter a numeric tt-series post id (visible in the URL).' })
        return
      }
      void runAction(`Post #${id}`, () => startScrapeById(id))
    },
    [postId, runAction],
  )

  const running = Boolean(status?.running)

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Scraper"
      description="Trigger a tt-series.com refresh and watch it land in the match table. Every run dedupes; rerunning is safe and cheap."
      badges={
        <>
          <Badge variant="accent">Data</Badge>
          <Badge>{running ? 'Running' : 'Idle'}</Badge>
          <Badge>Auto Refresh 3s</Badge>
        </>
      }
      actions={
        <>
          {running ? (
            <Button
              variant="secondary"
              onClick={() => void runAction('Stop', stopScrape)}
              disabled={busy}
              className="border-rose-200 bg-rose-50 text-rose-800 hover:bg-rose-100"
            >
              <Square className="size-4" />
              Stop scrape
            </Button>
          ) : null}
          <Button variant="secondary" onClick={() => void loadStatus(true)} disabled={refreshing}>
            <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
            Refresh now
          </Button>
        </>
      }
    >
      <section className="grid gap-5 xl:grid-cols-[1.05fr_0.95fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Trigger
            </Badge>
            <CardTitle>Run a scrape</CardTitle>
            <CardDescription>
              "Scrape recent" pulls the most recent tt-series listing page. "Page range" walks more history. "Specific
              post" targets one tournament. All three return immediately; the scraper runs in the background.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className="font-medium text-[var(--ink-strong)]">Scrape recent (page 1)</p>
                  <p className="mt-1 text-sm text-[var(--ink-muted)]">
                    Uses the current <code>ttl.startPage</code> / <code>ttl.endPage</code> from <code>application.properties</code>.
                  </p>
                </div>
                <Button onClick={() => void runAction('Recent', startScrapeRun)} disabled={busy || running}>
                  <PlayCircle className="size-4" />
                  Run now
                </Button>
              </div>
            </div>

            <form
              onSubmit={handleRange}
              className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4"
            >
              <div className="flex flex-wrap items-end gap-3">
                <div>
                  <p className="font-medium text-[var(--ink-strong)]">Page range</p>
                  <p className="mt-1 text-sm text-[var(--ink-muted)]">
                    Page 1 is the most recent tournament listing. Pages 1–5 covers roughly the last few weeks.
                  </p>
                </div>
                <div className="flex flex-wrap items-end gap-3">
                  <NumberField label="From" value={fromPage} onChange={setFromPage} min={1} />
                  <NumberField label="To" value={toPage} onChange={setToPage} min={1} />
                  <Button type="submit" disabled={busy || running}>
                    <LayoutList className="size-4" />
                    Run range
                  </Button>
                </div>
              </div>
            </form>

            <form
              onSubmit={handleById}
              className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4"
            >
              <div className="flex flex-wrap items-end gap-3">
                <div>
                  <p className="font-medium text-[var(--ink-strong)]">Specific post id</p>
                  <p className="mt-1 text-sm text-[var(--ink-muted)]">
                    The numeric id at the end of a tt-series tournament URL.
                  </p>
                </div>
                <div className="flex flex-wrap items-end gap-3">
                  <NumberField label="Post id" value={postId} onChange={setPostId} min={1} placeholder="e.g. 12345" />
                  <Button type="submit" disabled={busy || running || !postId.trim()}>
                    <Hash className="size-4" />
                    Run one
                  </Button>
                </div>
              </div>
            </form>

            {toast ? (
              <div
                role="status"
                className={cn(
                  'rounded-[18px] border px-4 py-3 text-sm',
                  toast.kind === 'error'
                    ? 'border-rose-200 bg-rose-50 text-rose-800'
                    : 'border-emerald-200 bg-emerald-50 text-emerald-800',
                )}
              >
                <div className="flex items-center gap-2">
                  {toast.kind === 'error' ? (
                    <AlertTriangle className="size-4" />
                  ) : (
                    <CheckCircle2 className="size-4" />
                  )}
                  <span>{toast.text}</span>
                </div>
              </div>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Live
            </Badge>
            <CardTitle>Current status</CardTitle>
            <CardDescription>Polled every 3 seconds while this page is open.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            {statusError ? (
              <div className="rounded-[18px] border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800" role="alert">
                <div className="flex items-center gap-2">
                  <AlertTriangle className="size-4" />
                  <span>{statusError}</span>
                </div>
              </div>
            ) : null}
            <div className="grid gap-3 sm:grid-cols-2">
              <MetricTile
                label="State"
                value={running ? 'Running' : status?.mode ?? 'Idle'}
                icon={running ? Loader2 : PlayCircle}
                spin={running}
              />
              <MetricTile
                label="Saved matches (last run)"
                value={status ? formatNumber(status.savedMatches) : '—'}
                icon={Database}
              />
              <MetricTile label="Started" value={formatDateTime(status?.startedAt)} icon={Timer} />
              <MetricTile label="Finished" value={formatDateTime(status?.finishedAt)} icon={Timer} />
            </div>
            {status?.error ? (
              <div className="rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
                <div className="flex items-center gap-2">
                  <AlertTriangle className="size-4" />
                  <span className="font-medium">Last error</span>
                </div>
                <p className="mt-2 break-words font-mono text-xs leading-5">{status.error}</p>
              </div>
            ) : null}
          </CardContent>
        </Card>
      </section>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            History
          </Badge>
          <CardTitle>Recent runs</CardTitle>
          <CardDescription>The last 20 scrape runs from the audit table.</CardDescription>
        </CardHeader>
        <CardContent className="overflow-x-auto">
          {runs.length === 0 ? (
            <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
              No runs have been recorded yet.
            </div>
          ) : (
            <table className="min-w-full border-separate border-spacing-y-2">
              <thead>
                <tr className="text-left text-xs uppercase text-[var(--ink-muted)]">
                  <th className="px-3 pb-1 font-semibold">Run</th>
                  <th className="px-3 pb-1 font-semibold">Mode</th>
                  <th className="px-3 pb-1 font-semibold">Status</th>
                  <th className="px-3 pb-1 font-semibold">Saved</th>
                  <th className="px-3 pb-1 font-semibold">Started</th>
                  <th className="px-3 pb-1 font-semibold">Finished</th>
                  <th className="px-3 pb-1 font-semibold">Error</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr
                    key={`${run.runId}-${run.startedAt ?? ''}`}
                    className="rounded-[18px] bg-[rgba(255,255,255,0.76)]"
                  >
                    <td className="rounded-l-[18px] px-3 py-3 align-top text-sm font-medium text-[var(--ink-strong)]">
                      #{run.runId}
                    </td>
                    <td className="px-3 py-3 align-top text-sm text-[var(--ink)]">{run.mode}</td>
                    <td className="px-3 py-3 align-top text-sm">
                      <StatusPill status={run.status} />
                    </td>
                    <td className="px-3 py-3 align-top text-sm text-[var(--ink)]">{formatNumber(run.savedMatches)}</td>
                    <td className="px-3 py-3 align-top text-sm text-[var(--ink-muted)]">
                      {formatDateTime(run.startedAt)}
                    </td>
                    <td className="px-3 py-3 align-top text-sm text-[var(--ink-muted)]">
                      {formatDateTime(run.finishedAt)}
                    </td>
                    <td className="rounded-r-[18px] px-3 py-3 align-top text-sm text-rose-700">
                      {run.error ? truncate(run.error, 80) : ''}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>

      {errors.length > 0 ? (
        <Card className="mt-5">
          <CardHeader>
            <Badge className="w-fit">Errors</Badge>
            <CardTitle>Recent parse errors</CardTitle>
            <CardDescription>
              Pages or posts the parser couldn't read. Most are transient (network blips) and clear on the next run.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            {errors.map((error, index) => (
              <div
                key={`${error.runId}-${error.occurredAt ?? index}`}
                className="rounded-[22px] border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900"
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="font-medium">
                    Run #{error.runId} · {error.mode}
                  </span>
                  <span className="text-xs text-amber-800">{formatDateTime(error.occurredAt)}</span>
                </div>
                <p className="mt-2 font-mono text-xs leading-5">{error.message}</p>
                {error.url ? (
                  <p className="mt-1 break-all text-xs">
                    <span className="font-semibold">URL:</span> {error.url}
                  </p>
                ) : null}
                {error.context ? (
                  <p className="mt-1 text-xs">
                    <span className="font-semibold">Context:</span> {error.context}
                  </p>
                ) : null}
              </div>
            ))}
          </CardContent>
        </Card>
      ) : null}
    </V3Shell>
  )
}

function NumberField({
  label,
  value,
  onChange,
  min,
  placeholder,
}: {
  label: string
  value: string
  onChange: (next: string) => void
  min?: number
  placeholder?: string
}) {
  return (
    <label className="grid gap-1 text-xs font-semibold uppercase text-[var(--ink-muted)]">
      {label}
      <input
        type="number"
        inputMode="numeric"
        value={value}
        min={min}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        className="w-28 rounded-[12px] border border-[var(--line)] bg-white px-3 py-2 text-sm text-[var(--ink-strong)] focus:border-[var(--accent-soft)] focus:outline-none"
      />
    </label>
  )
}

function MetricTile({
  label,
  value,
  icon: Icon,
  spin,
}: {
  label: string
  value: string
  icon: typeof Database
  spin?: boolean
}) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3 text-[var(--ink-muted)]">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className={cn('size-4', spin && 'animate-spin')} />
        </span>
        <p className="text-xs font-semibold uppercase">{label}</p>
      </div>
      <p className="mt-3 font-serif text-xl font-semibold text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

const statusTone: Record<string, string> = {
  SUCCESS: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  RUNNING: 'border-sky-200 bg-sky-50 text-sky-800',
  FAILED: 'border-rose-200 bg-rose-50 text-rose-800',
  IDLE: 'border-slate-200 bg-slate-50 text-slate-700',
}

function StatusPill({ status }: { status: string }) {
  const key = (status ?? '').toUpperCase()
  return (
    <span
      className={cn(
        'inline-flex w-fit items-center gap-2 rounded-full border px-3 py-1 text-xs font-semibold uppercase',
        statusTone[key] ?? 'border-slate-200 bg-slate-50 text-slate-700',
      )}
    >
      <span className="size-2 rounded-full bg-current opacity-70" />
      {status || 'unknown'}
    </span>
  )
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('en-US').format(value)
}

function formatDateTime(value: string | null | undefined) {
  if (!value) return '—'
  try {
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      second: '2-digit',
    }).format(new Date(value))
  } catch {
    return value
  }
}

function truncate(value: string, max: number) {
  if (value.length <= max) return value
  return `${value.slice(0, max - 1)}…`
}
