import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, ChevronLeft, ChevronRight, GitCompareArrows, RefreshCcw, ScanSearch, ShieldAlert } from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { fetchOpsDiffs } from '@/features/ops-diffs/api'
import type { OpsSettlementDiffFocus, OpsSettlementDiffRow, OpsSettlementDiffsResponse } from '@/features/ops-diffs/types'
import { cn } from '@/lib/utils'

const REFRESH_INTERVAL_MS = 5000
const PAGE_SIZE = 25
const focusOptions: Array<{ value: OpsSettlementDiffFocus; label: string; description: string }> = [
  { value: 'ALL', label: 'All Rows', description: 'Every replay row, newest first.' },
  { value: 'CONTRADICTION', label: 'Contradictions', description: 'Shadow rows with explicit contradiction flags.' },
  { value: 'AMBIGUITY', label: 'Ambiguity', description: 'Manual-review, ambiguous, or no-evidence shadow paths.' },
  { value: 'DISAGREEMENT', label: 'Disagreements', description: 'Anything that is not pure parity with the 2.0 path.' },
]

const diffTone: Record<string, string> = {
  AGREE: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  CONTRADICTION: 'border-rose-200 bg-rose-50 text-rose-800',
  OUTCOME_DIFF: 'border-amber-200 bg-amber-50 text-amber-900',
  CONFIDENCE_DIFF: 'border-sky-200 bg-sky-50 text-sky-900',
}

export function OpsDiffsRoute() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [data, setData] = useState<OpsSettlementDiffsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const mountedRef = useRef(true)
  const focus = parseFocus(searchParams.get('focus'))
  const page = parsePage(searchParams.get('page'))

  useEffect(() => {
    return () => {
      mountedRef.current = false
    }
  }, [])

  const loadDiffs = useCallback(async (background: boolean) => {
    if (mountedRef.current) {
      if (background) {
        setRefreshing(true)
      } else {
        setLoading(true)
      }
    }

    try {
      const next = await fetchOpsDiffs({ focus, page, size: PAGE_SIZE })
      if (!mountedRef.current) {
        return
      }
      setData(next)
      setError(null)
    } catch (nextError) {
      if (!mountedRef.current) {
        return
      }
      setError(nextError instanceof Error ? nextError.message : 'Unable to load settlement diffs right now.')
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
  }, [focus, page])

  useEffect(() => {
    void loadDiffs(false)
    const interval = window.setInterval(() => {
      void loadDiffs(true)
    }, REFRESH_INTERVAL_MS)
    return () => {
      window.clearInterval(interval)
    }
  }, [loadDiffs])

  const contradictionRows = useMemo(() => {
    if (!data) {
      return []
    }
    return data.rows.filter((row) => row.diffKind === 'CONTRADICTION').slice(0, 5)
  }, [data])

  const selectedFocus = data?.focus ?? focus
  const filteredRowsLabel = data ? `${data.filteredRows} rows in ${focusLabel(selectedFocus)}` : 'Current filter snapshot'
  const totalPages = data ? Math.max(1, data.totalPages) : 1

  const updateParams = useCallback((nextFocus: OpsSettlementDiffFocus, nextPage: number) => {
    const next = new URLSearchParams(searchParams)
    if (nextFocus === 'ALL') {
      next.delete('focus')
    } else {
      next.set('focus', nextFocus)
    }
    if (nextPage <= 0) {
      next.delete('page')
    } else {
      next.set('page', String(nextPage))
    }
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams])

  return (
    <V3Shell
      eyebrow="TTLElite Series 3.0"
      title="Settlement Diffs"
      description="Shadow-mode settlement replay for every attempted bet. This is where we inspect stalls, contradictions, and decision mismatches before Score Truth promotion."
      badges={
        <>
          <Badge variant="accent">Phase 02</Badge>
          <Badge>Shadow Replay</Badge>
          <Badge>Auto Refresh 5s</Badge>
        </>
      }
      actions={
        <Button variant="secondary" onClick={() => void loadDiffs(true)} disabled={loading || refreshing}>
          <RefreshCcw className={cn('size-4', refreshing && 'animate-spin')} />
          Refresh now
        </Button>
      }
    >
      <section className="grid gap-5 xl:grid-cols-[1.08fr_0.92fr]">
        <Card>
          <CardHeader>
            <Badge variant="accent" className="w-fit">
              Shadow Summary
            </Badge>
            <CardTitle>Decision parity at a glance</CardTitle>
            <CardDescription>
              We want agreement on benign paths and loud visibility on contradictions, missing evidence, or premature settlements.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-5">
            {loading && !data ? (
              <div className="rounded-[24px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
                Loading settlement diff snapshot…
              </div>
            ) : null}

            {data ? (
              <>
                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                  <MetricTile label="Rows Logged" value={String(data.summary.totalRows)} icon={GitCompareArrows} />
                  <MetricTile label="Agree" value={String(data.summary.agreeRows)} icon={ScanSearch} />
                  <MetricTile label="Diffs" value={String(data.summary.disagreementRows)} icon={ShieldAlert} />
                  <MetricTile label="Contradictions" value={String(data.summary.contradictionRows)} icon={AlertTriangle} />
                </div>
                <div className="rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">
                        Snapshot generated
                      </p>
                      <p className="mt-2 text-sm font-medium text-[var(--ink-strong)]">{formatDateTime(data.generatedAt)}</p>
                      <p className="mt-2 text-sm text-[var(--ink-muted)]">{filteredRowsLabel}</p>
                    </div>
                    <div className="text-right text-sm text-[var(--ink-muted)]">
                      <p>{refreshing ? 'Refreshing in place…' : 'Live polling active'}</p>
                      <p className="mt-1">Outcome diffs: {data.summary.outcomeDiffRows}</p>
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
            <Badge className="w-fit">Contradictions</Badge>
            <CardTitle>Rows to inspect first</CardTitle>
            <CardDescription>
              Contradictions are the highest-signal shadow rows because they usually mean the score timeline and a confirmation source are disagreeing.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            {contradictionRows.length === 0 && data ? (
              <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4 text-sm text-[var(--ink-muted)]">
                No contradiction rows are present in the current snapshot.
              </div>
            ) : null}

            {contradictionRows.map((row) => (
              <DiffCard key={`${row.betId}-${row.decidedAt ?? 'na'}`} row={row} />
            ))}
          </CardContent>
        </Card>
      </section>

      <Card className="mt-5">
        <CardHeader>
          <Badge variant="accent" className="w-fit">
            Recent Shadow Rows
          </Badge>
          <CardTitle>Latest replay output</CardTitle>
          <CardDescription>
            Each row is one settlement attempt replayed through the shadow Score Truth engine or explicitly marked when no evidence bundle could be built.
          </CardDescription>
        </CardHeader>
        <CardContent className="mt-5 overflow-x-auto">
          <div className="mb-5 flex flex-col gap-4 rounded-[24px] border border-[var(--line)] bg-[rgba(255,255,255,0.66)] p-4">
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.24em] text-[var(--ink-muted)]">Focus Filter</p>
              <p className="mt-2 text-sm text-[var(--ink-muted)]">
                Move between contradictions, ambiguity-heavy rows, and all disagreements without losing the current page context.
              </p>
            </div>
            <div className="flex flex-wrap gap-2">
              {focusOptions.map((option) => {
                const active = selectedFocus === option.value
                return (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => updateParams(option.value, 0)}
                    className={cn(
                      'rounded-full border px-4 py-2 text-left text-sm transition-colors',
                      active
                        ? 'border-[var(--accent-soft)] bg-[var(--accent-fade)] text-[var(--accent-ink)]'
                        : 'border-[var(--line)] bg-[var(--panel)] text-[var(--ink-muted)] hover:border-[var(--accent-soft)] hover:text-[var(--ink-strong)]',
                    )}
                  >
                    <span className="block font-semibold">{option.label}</span>
                    <span className="mt-1 block text-xs">{option.description}</span>
                  </button>
                )
              })}
            </div>
          </div>

          {data ? (
            <>
              {data.rows.length > 0 ? (
                <table className="min-w-full border-separate border-spacing-y-3">
                  <thead>
                    <tr className="text-left text-xs uppercase tracking-[0.22em] text-[var(--ink-muted)]">
                      <th className="px-3 pb-1 font-semibold">Bet</th>
                      <th className="px-3 pb-1 font-semibold">Diff</th>
                      <th className="px-3 pb-1 font-semibold">Legacy</th>
                      <th className="px-3 pb-1 font-semibold">Shadow</th>
                      <th className="px-3 pb-1 font-semibold">Winner</th>
                      <th className="px-3 pb-1 font-semibold">Decided</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((row) => (
                      <DiffRow key={`${row.betId}-${row.decidedAt ?? 'na'}`} row={row} />
                    ))}
                  </tbody>
                </table>
              ) : (
                <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
                  No rows matched the current focus filter.
                </div>
              )}

              <div className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.66)] px-4 py-3 text-sm text-[var(--ink-muted)]">
                <div>
                  Page {data.page + 1} of {totalPages}
                  <span className="ml-2">{data.filteredRows} filtered rows</span>
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={!data.hasPrevious}
                    onClick={() => updateParams(selectedFocus, Math.max(0, data.page - 1))}
                  >
                    <ChevronLeft className="size-4" />
                    Prev
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={!data.hasNext}
                    onClick={() => updateParams(selectedFocus, data.page + 1)}
                  >
                    Next
                    <ChevronRight className="size-4" />
                  </Button>
                </div>
              </div>
            </>
          ) : (
            <div className="rounded-[22px] border border-dashed border-[var(--line-strong)] bg-[rgba(255,255,255,0.58)] p-6 text-sm text-[var(--ink-muted)]">
              No settlement diff rows available yet.
            </div>
          )}
        </CardContent>
      </Card>
    </V3Shell>
  )
}

function DiffCard({ row }: { row: OpsSettlementDiffRow }) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="font-medium text-[var(--ink-strong)]">Bet #{row.betId}</p>
          <p className="mt-1 text-sm text-[var(--ink-muted)]">Legacy: {row.oldReason ?? 'N/A'}</p>
          <p className="text-sm text-[var(--ink-muted)]">Shadow: {row.newReason ?? 'N/A'}</p>
        </div>
        <DiffPill diffKind={row.diffKind} />
      </div>
      <div className="mt-3 flex items-center justify-between gap-3">
        <p className="text-sm text-[var(--ink-muted)]">{formatDateTime(row.decidedAt)}</p>
        <Button variant="ghost" size="sm" asChild>
          <Link to={`/matches/${row.betId}/evidence`}>Open Evidence</Link>
        </Button>
      </div>
    </div>
  )
}

function DiffRow({ row }: { row: OpsSettlementDiffRow }) {
  return (
    <tr className="rounded-[22px] bg-[rgba(255,255,255,0.74)] text-sm text-[var(--ink)] shadow-[0_12px_32px_-24px_rgba(15,23,42,0.45)]">
      <td className="rounded-l-[22px] px-3 py-4 font-medium text-[var(--ink-strong)]">#{row.betId}</td>
      <td className="px-3 py-4">
        <DiffPill diffKind={row.diffKind} />
      </td>
      <td className="px-3 py-4 text-[var(--ink-muted)]">{row.oldReason ?? 'N/A'}</td>
      <td className="px-3 py-4 text-[var(--ink-muted)]">{row.newReason ?? 'N/A'}</td>
      <td className="px-3 py-4 text-[var(--ink-muted)]">
        {row.oldWinner ?? 'N/A'} → {row.newWinner ?? 'N/A'}
      </td>
      <td className="rounded-r-[22px] px-3 py-4 text-[var(--ink-muted)]">
        <div className="flex flex-col gap-2">
          <span>{formatDateTime(row.decidedAt)}</span>
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/matches/${row.betId}/evidence`}>Open Evidence</Link>
          </Button>
        </div>
      </td>
    </tr>
  )
}

function DiffPill({ diffKind }: { diffKind: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border px-3 py-1 text-xs font-semibold uppercase tracking-[0.18em]',
        diffTone[diffKind] ?? 'border-slate-200 bg-slate-50 text-slate-700',
      )}
    >
      {diffKind.replaceAll('_', ' ')}
    </span>
  )
}

function MetricTile({
  label,
  value,
  icon: Icon,
}: {
  label: string
  value: string
  icon: typeof GitCompareArrows
}) {
  return (
    <div className="rounded-[22px] border border-[var(--line)] bg-[rgba(255,255,255,0.72)] p-4">
      <div className="flex items-center gap-3 text-[var(--ink-muted)]">
        <span className="inline-flex size-10 items-center justify-center rounded-2xl bg-[var(--panel-soft)] text-[var(--accent-ink)]">
          <Icon className="size-4" />
        </span>
        <p className="text-xs font-semibold uppercase tracking-[0.24em]">{label}</p>
      </div>
      <p className="mt-4 font-serif text-3xl font-semibold tracking-[-0.05em] text-[var(--ink-strong)]">{value}</p>
    </div>
  )
}

function InlineAlert({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-2 rounded-[18px] border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
      {children}
    </div>
  )
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

function parseFocus(value: string | null): OpsSettlementDiffFocus {
  if (value === 'CONTRADICTION' || value === 'AMBIGUITY' || value === 'DISAGREEMENT') {
    return value
  }
  return 'ALL'
}

function parsePage(value: string | null) {
  if (!value) {
    return 0
  }
  const numeric = Number.parseInt(value, 10)
  return Number.isNaN(numeric) || numeric < 0 ? 0 : numeric
}

function focusLabel(focus: OpsSettlementDiffFocus) {
  return focusOptions.find((option) => option.value === focus)?.label ?? 'All Rows'
}
