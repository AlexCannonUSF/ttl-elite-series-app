import { useCallback, useEffect, useMemo, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import {
  AlertTriangle,
  ArrowRight,
  BarChart3,
  Bookmark,
  Check,
  ChevronDown,
  CircleHelp,
  GitCompareArrows,
  Download,
  Layers3,
  NotebookPen,
  RefreshCcw,
  Search,
  ShieldCheck,
  Target,
  X,
} from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import type { ModelCallTracking } from '@/features/live-studio/types'
import type { ModelRun } from '@/features/ml-quality/types'
import { addRunAnnotation, compareResearchRuns, fetchResearchRun, fetchResearchRuns } from '@/features/research/api'
import type { ResearchRunComparison, ResearchRunDetail } from '@/features/research/types'
import { cn } from '@/lib/utils'

type DetailTab = 'OVERVIEW' | 'CALLS' | 'SIGNALS' | 'RESEARCH' | 'IDENTITY'

type SavedRunView = { id: string; label: string; query: string; status: string; runId: number | null; compareIds: number[]; tab: DetailTab }

const metricDefinitions: Record<string, { title: string; detail: string }> = {
  accuracy: {
    title: 'Winner accuracy',
    detail: 'Correct settled model winner calls divided by all settled calls with a directional lean. Unresolved calls and no-leans are excluded.',
  },
  brier: {
    title: 'Brier score',
    detail: 'Mean squared error between the frozen win probability and the binary outcome. Lower is better; zero is perfect.',
  },
  roi: {
    title: 'Flat-$1 ROI',
    detail: 'Hypothetical net profit divided by $1 stakes on every priced model lean at its captured Hard Rock price. It is not the official policy record.',
  },
  readiness: {
    title: 'Evidence readiness',
    detail: 'Settled trusted calls divided by the 100-call directional target. It describes sample maturity, not model quality.',
  },
}

export function RunsRoute() {
  const [params, setParams] = useSearchParams()
  const [runs, setRuns] = useState<ModelRun[]>([])
  const [detail, setDetail] = useState<ResearchRunDetail | null>(null)
  const [comparison, setComparison] = useState<ResearchRunComparison | null>(null)
  const [query, setQuery] = useState(params.get('q') ?? '')
  const [status, setStatus] = useState(params.get('status') ?? 'ALL')
  const [tab, setTab] = useState<DetailTab>((params.get('tab') as DetailTab) ?? 'OVERVIEW')
  const [explainer, setExplainer] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [savedViews, setSavedViews] = useState<SavedRunView[]>(readSavedViews)

  const selectedRunId = numberParam(params.get('run'))
  const compareIds = useMemo(() => parseIds(params.get('compare')), [params])

  const updateParams = useCallback((updates: Record<string, string | null>) => {
    setParams((current) => {
      const next = new URLSearchParams(current)
      Object.entries(updates).forEach(([key, value]) => value ? next.set(key, value) : next.delete(key))
      return next
    }, { replace: true })
  }, [setParams])

  const loadRuns = useCallback(async () => {
    setLoading(true)
    try {
      const response = await fetchResearchRuns(100)
      setRuns(response.runs)
      setError(null)
      const latest = response.runs[0]
      if (!params.get('run') && latest) {
        updateParams({ run: String(latest.sessionId) })
      }
    } catch (nextError) {
      setError(message(nextError, 'Unable to load run history.'))
    } finally {
      setLoading(false)
    }
  }, [params, updateParams])

  useEffect(() => { void loadRuns() }, [loadRuns])

  useEffect(() => {
    if (!selectedRunId) {
      setDetail(null)
      return
    }
    const controller = new AbortController()
    fetchResearchRun(selectedRunId, controller.signal)
      .then(setDetail)
      .catch((nextError) => {
        if (!controller.signal.aborted) setError(message(nextError, 'Unable to load run detail.'))
      })
    return () => controller.abort()
  }, [selectedRunId])

  useEffect(() => {
    if (compareIds.length < 2) {
      setComparison(null)
      return
    }
    const controller = new AbortController()
    compareResearchRuns(compareIds, controller.signal)
      .then(setComparison)
      .catch((nextError) => {
        if (!controller.signal.aborted) setError(message(nextError, 'Unable to compare runs.'))
      })
    return () => controller.abort()
  }, [compareIds])

  const filteredRuns = useMemo(() => runs.filter((run) => {
    const text = `${run.label} ${run.effectiveModelVersion ?? ''} ${run.policyVersion ?? ''}`.toLowerCase()
    return (status === 'ALL' || run.status === status) && text.includes(query.trim().toLowerCase())
  }), [query, runs, status])

  function chooseRun(runId: number) {
    updateParams({ run: String(runId) })
  }

  function toggleCompare(runId: number) {
    const next = compareIds.includes(runId)
      ? compareIds.filter((id) => id !== runId)
      : [...compareIds, runId].slice(-5)
    updateParams({ compare: next.length ? next.join(',') : null })
  }

  function selectTab(next: DetailTab) {
    setTab(next)
    updateParams({ tab: next === 'OVERVIEW' ? null : next })
  }

  function saveCurrentView() {
    const view: SavedRunView = {
      id: `${Date.now()}`,
      label: query.trim() || (status === 'ALL' ? `Run ${selectedRunId ?? 'workspace'}` : `${pretty(status)} runs`),
      query,
      status,
      runId: selectedRunId,
      compareIds,
      tab,
    }
    const next = [view, ...savedViews].slice(0, 12)
    setSavedViews(next)
    window.localStorage.setItem('ttl-runs-saved-views', JSON.stringify(next))
  }

  function applySavedView(view: SavedRunView) {
    setQuery(view.query)
    setStatus(view.status)
    setTab(view.tab)
    setParams({
      ...(view.query ? { q: view.query } : {}),
      ...(view.status !== 'ALL' ? { status: view.status } : {}),
      ...(view.runId ? { run: String(view.runId) } : {}),
      ...(view.compareIds.length ? { compare: view.compareIds.join(',') } : {}),
      ...(view.tab !== 'OVERVIEW' ? { tab: view.tab } : {}),
    }, { replace: true })
  }

  function exportRuns() {
    const rows = filteredRuns.map((run) => [run.sessionId, run.label, run.status, run.createdAt ?? '',
      run.closedAt ?? '', run.effectiveModelVersion ?? '', run.policyVersion ?? '', run.modelCalls,
      run.wins, run.losses, run.roiPct, run.sampleReadinessPct])
    downloadCsv('ttl-run-cohort.csv', [['run_id', 'label', 'status', 'created_at', 'closed_at', 'model_version',
      'policy_version', 'model_calls', 'paper_wins', 'paper_losses', 'paper_roi_pct', 'readiness_pct'], ...rows])
  }

  return (
    <V3Shell
      title="Runs"
      description="Open any simulation as an immutable experiment, compare versions on visible cohorts, and drill from every aggregate into the exact model calls underneath it."
      badges={<><Badge variant="accent">Research ledger</Badge><Badge>{runs.length} runs</Badge></>}
      actions={<><Button variant="ghost" onClick={exportRuns} disabled={!filteredRuns.length}><Download className="size-4" />Export cohort</Button><Button variant="secondary" onClick={() => void loadRuns()} disabled={loading}><RefreshCcw className={cn('size-4', loading && 'animate-spin')} />Refresh</Button></>}
    >
      {error ? <InlineAlert><AlertTriangle className="size-4" />{error}<button className="ml-auto" onClick={() => setError(null)} aria-label="Dismiss error"><X className="size-4" /></button></InlineAlert> : null}

      <section className="grid gap-5 2xl:grid-cols-[390px_minmax(0,1fr)]">
        <Card className="h-fit 2xl:sticky 2xl:top-4">
          <CardHeader>
            <Badge variant="accent" className="w-fit"><Search className="mr-1 size-3" /> Run explorer</Badge>
            <CardTitle>Choose the evidence</CardTitle>
            <CardDescription>Open one run or check up to five for a synchronized comparison.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-[1fr_auto] gap-2">
              <label className="relative">
                <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-[var(--ink-muted)]" />
                <input
                  aria-label="Search runs"
                  className="h-10 w-full rounded-xl border border-[var(--line)] bg-white/70 pl-9 pr-3 text-sm outline-none focus:border-blue-400"
                  onChange={(event) => { setQuery(event.target.value); updateParams({ q: event.target.value || null }) }}
                  placeholder="Model, run, policy"
                  value={query}
                />
              </label>
              <label className="relative">
                <select
                  aria-label="Filter run status"
                  className="h-10 appearance-none rounded-xl border border-[var(--line)] bg-white/70 pl-3 pr-8 text-xs font-semibold outline-none"
                  onChange={(event) => { setStatus(event.target.value); updateParams({ status: event.target.value === 'ALL' ? null : event.target.value }) }}
                  value={status}
                >
                  <option value="ALL">All</option>
                  <option value="ACTIVE">Active</option>
                  <option value="CLOSED">Closed</option>
                </select>
                <ChevronDown className="pointer-events-none absolute right-2 top-1/2 size-3 -translate-y-1/2" />
              </label>
            </div>

            {compareIds.length ? (
              <div className="mt-3 flex items-center justify-between rounded-xl border border-blue-200 bg-blue-50 px-3 py-2 text-xs text-blue-950">
                <span><strong>{compareIds.length}</strong> selected for comparison</span>
                <button className="font-semibold" onClick={() => updateParams({ compare: null })}>Clear</button>
              </div>
            ) : null}

            <div className="mt-3 flex flex-wrap items-center gap-2">
              <button className="inline-flex items-center gap-1 rounded-full border border-blue-200 bg-blue-50 px-2.5 py-1.5 text-[10px] font-bold text-blue-900" onClick={saveCurrentView}><Bookmark className="size-3" />Save current view</button>
              {savedViews.slice(0, 4).map((view) => <button className="max-w-40 truncate rounded-full border border-[var(--line)] bg-white px-2.5 py-1.5 text-[10px] font-semibold" key={view.id} onClick={() => applySavedView(view)} title={view.label}>{view.label}</button>)}
            </div>

            <div className="mt-3 max-h-[calc(100vh-360px)] space-y-2 overflow-y-auto pr-1">
              {filteredRuns.map((run) => (
                <RunRow
                  comparing={compareIds.includes(run.sessionId)}
                  key={run.sessionId}
                  onCompare={() => toggleCompare(run.sessionId)}
                  onOpen={() => chooseRun(run.sessionId)}
                  run={run}
                  selected={selectedRunId === run.sessionId}
                />
              ))}
              {!loading && filteredRuns.length === 0 ? <EmptyState text="No runs match this cohort." /> : null}
            </div>
          </CardContent>
        </Card>

        <div className="min-w-0 space-y-5">
          {comparison ? <ComparisonPanel comparison={comparison} onOpen={chooseRun} /> : null}
          {detail ? (
            <RunDetail
              detail={detail}
              explainer={explainer}
              onExplain={setExplainer}
              onTab={selectTab}
              onAnnotation={(annotation) => setDetail((current) => current ? {
                ...current,
                foundation: { ...current.foundation, annotations: [annotation, ...current.foundation.annotations] },
              } : current)}
              tab={tab}
            />
          ) : <Card><CardContent className="py-16"><EmptyState text={loading ? 'Loading run evidence…' : 'Select a run to inspect it.'} /></CardContent></Card>}
        </div>
      </section>
    </V3Shell>
  )
}

function RunRow({ run, selected, comparing, onOpen, onCompare }: {
  run: ModelRun
  selected: boolean
  comparing: boolean
  onOpen: () => void
  onCompare: () => void
}) {
  return (
    <div className={cn('rounded-2xl border p-3 transition', selected ? 'border-blue-300 bg-blue-50/80' : 'border-[var(--line)] bg-white/55 hover:bg-white')}>
      <div className="flex gap-3">
        <button
          aria-label={`${comparing ? 'Remove' : 'Add'} ${run.label} ${comparing ? 'from' : 'to'} comparison`}
          className={cn('mt-0.5 grid size-5 shrink-0 place-items-center rounded-md border', comparing ? 'border-blue-500 bg-blue-600 text-white' : 'border-slate-300 bg-white')}
          onClick={onCompare}
        >{comparing ? <Check className="size-3" /> : null}</button>
        <button className="min-w-0 flex-1 text-left" onClick={onOpen}>
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <p className="truncate text-sm font-bold text-[var(--ink-strong)]">{run.label}</p>
              <p className="mt-0.5 truncate font-mono text-[10px] text-[var(--ink-muted)]">#{run.sessionId} · {run.effectiveModelVersion ?? 'Legacy identity'}</p>
            </div>
            <StatusPill status={run.status} />
          </div>
          <div className="mt-3 grid grid-cols-3 gap-2 text-[10px]">
            <Mini label="Calls" value={formatInt(run.modelCalls)} />
            <Mini label="Paper record" value={`${run.wins}-${run.losses}`} />
            <Mini label="Paper ROI" value={signedPct(run.roiPct)} tone={run.roiPct >= 0 ? 'good' : 'bad'} />
          </div>
          <p className="mt-2 text-[10px] text-[var(--ink-muted)]">{formatDate(run.createdAt)} · {run.sampleReadinessPct.toFixed(0)}% ready</p>
        </button>
      </div>
    </div>
  )
}

function ComparisonPanel({ comparison, onOpen }: { comparison: ResearchRunComparison; onOpen: (runId: number) => void }) {
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <Badge variant="accent" className="w-fit"><GitCompareArrows className="mr-1 size-3" /> Run comparison</Badge>
          <span className="text-xs font-semibold text-[var(--ink-muted)]">{comparison.sharedOpportunityCount} shared opportunities</span>
        </div>
        <CardTitle>Natural cohorts, side by side</CardTitle>
        <CardDescription>Shared coverage exposes whether headline differences could be caused by different match sets.</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="overflow-x-auto rounded-2xl border border-[var(--line)]">
          <table className="w-full min-w-[760px] text-left text-xs">
            <thead className="bg-slate-100/80 text-[10px] uppercase tracking-[0.14em] text-[var(--ink-muted)]">
              <tr><th className="px-3 py-3">Run</th><th className="px-3 py-3">Model</th><th className="px-3 py-3">Settled</th><th className="px-3 py-3">Accuracy</th><th className="px-3 py-3">Brier</th><th className="px-3 py-3">$1 ROI</th><th className="px-3 py-3">Shared coverage</th><th /></tr>
            </thead>
            <tbody>
              {comparison.runs.map(({ run, naturalCohort, sharedCoveragePct }) => (
                <tr className="border-t border-[var(--line)] bg-white/60" key={run.sessionId}>
                  <td className="px-3 py-3 font-semibold">{run.label}</td>
                  <td className="px-3 py-3 font-mono text-[10px]">{run.effectiveModelVersion ?? 'Unknown'}</td>
                  <td className="px-3 py-3">{naturalCohort.settledCalls}/{naturalCohort.totalCalls}</td>
                  <td className="px-3 py-3 font-mono font-bold">{pct(naturalCohort.accuracyPct)}</td>
                  <td className="px-3 py-3 font-mono">{naturalCohort.brierScore?.toFixed(3) ?? '—'}</td>
                  <td className={cn('px-3 py-3 font-mono font-bold', naturalCohort.flatStakeRoiPct >= 0 ? 'text-emerald-700' : 'text-rose-700')}>{signedPct(naturalCohort.flatStakeRoiPct)}</td>
                  <td className="px-3 py-3">{pct(sharedCoveragePct)}</td>
                  <td className="px-3 py-3"><button className="font-semibold text-blue-700" onClick={() => onOpen(run.sessionId)}>Open</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="mt-3 space-y-1">
          {comparison.cautions.map((caution) => <p className="flex gap-2 text-[11px] leading-5 text-amber-900" key={caution}><AlertTriangle className="mt-1 size-3 shrink-0" />{caution}</p>)}
        </div>
      </CardContent>
    </Card>
  )
}

function RunDetail({ detail, tab, onTab, explainer, onExplain, onAnnotation }: {
  detail: ResearchRunDetail
  tab: DetailTab
  onTab: (tab: DetailTab) => void
  explainer: string | null
  onExplain: (key: string | null) => void
  onAnnotation: (annotation: ResearchRunDetail['foundation']['annotations'][number]) => void
}) {
  const { run, analytics, integrity, pipeline } = detail
  return (
    <>
      <section className="admin-hero overflow-hidden rounded-[30px] border border-blue-300/15 p-5 text-white shadow-2xl shadow-black/20 sm:p-7">
        <div className="flex flex-col gap-6 xl:flex-row xl:items-end xl:justify-between">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2"><StatusPill status={run.status} /><Badge>{pretty(analytics.evidenceLabel)}</Badge><Badge>{integrity.status === 'REPRODUCIBLE' ? 'Integrity complete' : pretty(integrity.status)}</Badge></div>
            <h2 className="mt-4 truncate text-3xl font-semibold tracking-[-0.045em] sm:text-4xl">{run.label}</h2>
            <p className="mt-2 font-mono text-xs text-slate-400">Run #{run.sessionId} · {run.effectiveModelVersion ?? 'Unknown model'} · {run.policyVersion ?? 'Unknown policy'}</p>
            <p className="mt-4 max-w-3xl text-sm leading-6 text-slate-300">{integrity.explanation}</p>
          </div>
          <div className="grid min-w-[280px] grid-cols-2 gap-2">
            <HeroMetric label="Settled calls" value={`${analytics.settledCalls}/${analytics.totalCalls}`} />
            <HeroMetric label="Evidence" value={`${analytics.readinessPct.toFixed(0)}%`} />
            <HeroMetric label="Accuracy" value={pct(analytics.accuracyPct)} />
            <HeroMetric label="$1 ROI" value={signedPct(analytics.flatStakeRoiPct)} tone={analytics.flatStakeRoiPct >= 0 ? 'good' : 'bad'} />
          </div>
        </div>
      </section>

      <div className="flex gap-1 overflow-x-auto rounded-2xl border border-[var(--line)] bg-white/55 p-1.5">
        {(['OVERVIEW', 'CALLS', 'SIGNALS', 'RESEARCH', 'IDENTITY'] as DetailTab[]).map((item) => <button className={cn('shrink-0 rounded-xl px-4 py-2 text-xs font-bold', tab === item ? 'bg-slate-950 text-white' : 'text-[var(--ink-muted)] hover:bg-white')} key={item} onClick={() => onTab(item)}>{pretty(item)}</button>)}
      </div>

      {explainer && metricDefinitions[explainer] ? <MetricExplainer metric={metricDefinitions[explainer]} onClose={() => onExplain(null)} /> : null}
      {tab === 'OVERVIEW' ? <Overview detail={detail} onExplain={onExplain} /> : null}
      {tab === 'CALLS' ? <CallsTable calls={pipeline.calls} /> : null}
      {tab === 'SIGNALS' ? <SignalTables detail={detail} /> : null}
      {tab === 'RESEARCH' ? <ResearchFoundationPanel detail={detail} onAnnotation={onAnnotation} /> : null}
      {tab === 'IDENTITY' ? <IdentityPanel detail={detail} /> : null}
    </>
  )
}

function Overview({ detail, onExplain }: { detail: ResearchRunDetail; onExplain: (key: string) => void }) {
  const { analytics } = detail
  return (
    <div className="space-y-5">
      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <ExplainMetric label="Winner accuracy" value={pct(analytics.accuracyPct)} detail={`95% CI ${interval(analytics.accuracyCiLowPct, analytics.accuracyCiHighPct)}`} onClick={() => onExplain('accuracy')} />
        <ExplainMetric label="Brier score" value={analytics.brierScore?.toFixed(3) ?? '—'} detail={`${analytics.settledCalls} eligible outcomes`} onClick={() => onExplain('brier')} />
        <ExplainMetric label="Flat-$1 ROI" value={signedPct(analytics.flatStakeRoiPct)} detail={`95% CI ${interval(analytics.flatStakeRoiCiLowPct, analytics.flatStakeRoiCiHighPct)}`} onClick={() => onExplain('roi')} />
        <ExplainMetric label="Evidence readiness" value={`${analytics.readinessPct.toFixed(0)}%`} detail={`${analytics.settledCalls} of ${analytics.readinessTarget}`} onClick={() => onExplain('readiness')} />
      </section>
      <Card>
        <CardHeader><Badge variant="accent" className="w-fit"><BarChart3 className="mr-1 size-3" /> Resolution trend</Badge><CardTitle>Accuracy and hypothetical profit through time</CardTitle><CardDescription>Every point is one trusted resolved call; click a result below to inspect the frozen call.</CardDescription></CardHeader>
        <CardContent><TrendChart points={analytics.trend} /></CardContent>
      </Card>
      <CallsTable calls={detail.pipeline.calls.slice(0, 12)} compact />
    </div>
  )
}

function CallsTable({ calls, compact = false }: { calls: ModelCallTracking[]; compact?: boolean }) {
  return (
    <Card>
      <CardHeader><Badge className="w-fit"><Target className="mr-1 size-3" /> Underlying calls</Badge><CardTitle>{compact ? 'Recent decisions' : 'Complete call ledger'}</CardTitle><CardDescription>Score, captured price, model probability, decision, and current pipeline state remain attached to the call.</CardDescription></CardHeader>
      <CardContent>
        <div className="overflow-x-auto rounded-2xl border border-[var(--line)]">
          <table className="w-full min-w-[900px] text-left text-xs">
            <thead className="bg-slate-100/80 text-[10px] uppercase tracking-[0.14em] text-[var(--ink-muted)]"><tr><th className="px-3 py-3">Match</th><th className="px-3 py-3">Captured</th><th className="px-3 py-3">Lean</th><th className="px-3 py-3">Model</th><th className="px-3 py-3">Hard Rock</th><th className="px-3 py-3">Score</th><th className="px-3 py-3">Decision</th><th className="px-3 py-3">Pipeline</th><th /></tr></thead>
            <tbody>{calls.map((call) => <tr className="border-t border-[var(--line)] bg-white/60" key={call.callId}>
              <td className="max-w-[220px] px-3 py-3"><p className="truncate font-semibold">{call.eventName}</p><p className="mt-0.5 truncate text-[10px] text-[var(--ink-muted)]">{call.competitionName ?? 'TT Elite Series'}</p></td>
              <td className="px-3 py-3 text-[10px]">{formatDate(call.capturedAt)}</td>
              <td className="px-3 py-3 font-semibold">{call.predictedWinnerName ?? 'No lean'}</td>
              <td className="px-3 py-3 font-mono">{call.modelProbability == null ? '—' : pct(call.modelProbability * 100)}</td>
              <td className="px-3 py-3 font-mono">{american(call.hardRockAmericanOdds)}</td>
              <td className="px-3 py-3 font-mono">{call.systemScore ?? call.latestScore ?? '—'}</td>
              <td className="px-3 py-3"><Badge>{call.paperPickPlaced ? 'Paper pick' : pretty(call.decisionReason ?? 'Pass')}</Badge></td>
              <td className="px-3 py-3"><StatusPill status={call.pipelineStage} /></td>
              <td className="px-3 py-3"><Link className="inline-flex items-center gap-1 font-semibold text-blue-700" to={`/admin/pipeline/${call.callId}`}>Inspect<ArrowRight className="size-3" /></Link></td>
            </tr>)}</tbody>
          </table>
        </div>
        {!calls.length ? <EmptyState text="This run has no frozen model calls." /> : null}
      </CardContent>
    </Card>
  )
}

function SignalTables({ detail }: { detail: ResearchRunDetail }) {
  return <div className="grid gap-5 xl:grid-cols-2"><SegmentCard title="Trigger performance" rows={detail.analytics.triggers} /><SegmentCard title="Decision-gate counterfactuals" rows={detail.analytics.decisionReasons} /><Card className="xl:col-span-2"><CardHeader><CardTitle>Factor direction</CardTitle><CardDescription>Aligned contributions are evaluated against resolved outcomes; readiness prevents thin factors from being treated as proven.</CardDescription></CardHeader><CardContent><div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">{detail.analytics.factors.map((factor) => <div className="rounded-2xl border border-[var(--line)] bg-white/60 p-3" key={factor.factor}><p className="text-xs font-bold">{pretty(factor.factor)}</p><div className="mt-3 grid grid-cols-3 gap-2"><Mini label="Sample" value={String(factor.sampleSize)} /><Mini label="Direction" value={pct(factor.directionalAccuracyPct)} /><Mini label="Ready" value={pct(factor.readinessPct)} /></div></div>)}</div></CardContent></Card></div>
}

function ResearchFoundationPanel({ detail, onAnnotation }: {
  detail: ResearchRunDetail
  onAnnotation: (annotation: ResearchRunDetail['foundation']['annotations'][number]) => void
}) {
  const { foundation } = detail
  const [note, setNote] = useState('')
  const [tags, setTags] = useState('')
  const [saving, setSaving] = useState(false)
  const [noteError, setNoteError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!note.trim() || saving) return
    setSaving(true)
    try {
      const annotation = await addRunAnnotation(
        detail.run.sessionId,
        note.trim(),
        tags.split(',').map((tag) => tag.trim()).filter(Boolean),
      )
      onAnnotation(annotation)
      setNote('')
      setTags('')
      setNoteError(null)
    } catch (nextError) {
      setNoteError(message(nextError, 'Unable to save the annotation.'))
    } finally {
      setSaving(false)
    }
  }

  return <div className="space-y-5">
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <SummaryMetric label="Shared opportunities" value={formatInt(foundation.opportunityCount)} detail="One match opportunity, regardless of polling or lane count." />
      <SummaryMetric label="Synchronized" value={formatInt(foundation.synchronizedOpportunityCount)} detail={`${pct(foundation.telemetryCompletenessPct)} of legacy calls mapped`} />
      <SummaryMetric label="Model lanes" value={String(foundation.modelLanes.length)} detail="Champion and shadow identities stay separate." />
      <SummaryMetric label="Portfolios" value={String(foundation.portfolios.length)} detail="Official policy and research cohorts never mix." />
    </section>
    <div className="grid gap-5 xl:grid-cols-2">
      <Card><CardHeader><Badge variant="accent" className="w-fit"><Layers3 className="mr-1 size-3" /> Model lanes</Badge><CardTitle>Synchronized model identities</CardTitle><CardDescription>Every lane is graded on the same trusted winners. Accuracy uses all resolved leans; ROI uses only resolved leans with a captured Hard Rock price.</CardDescription></CardHeader><CardContent className="space-y-2">{foundation.modelLanes.map((lane) => <div className="rounded-2xl border border-[var(--line)] bg-white/60 p-3" key={lane.id}><div className="flex items-start justify-between gap-3"><div><p className="text-sm font-bold">{lane.displayName}</p><p className="mt-1 font-mono text-[10px] text-[var(--ink-muted)]">{lane.modelVersion ?? 'Identity pending'}</p></div><Badge variant={lane.primary ? 'accent' : 'neutral'}>{pretty(lane.role)}</Badge></div><div className="mt-3 grid grid-cols-3 gap-2 xl:grid-cols-6"><Mini label="Evaluations" value={formatInt(lane.evaluations)} /><Mini label="Resolved" value={formatInt(lane.resolved)} /><Mini label="Accuracy" value={pct(lane.accuracyPct)} /><Mini label="$1 ROI" value={signedPct(lane.flatStakeRoiPct)} /><Mini label="Brier" value={lane.brierScore == null ? '—' : lane.brierScore.toFixed(3)} /><Mini label="Coverage" value={pct(lane.opportunityCoveragePct)} /></div><p className="mt-2 text-[10px] text-[var(--ink-muted)]">{lane.pricedResolved} priced results · {signedMoney(lane.flatStakePnl)} flat-$1 P&amp;L · {pretty(lane.modelFamily ?? 'unknown')}</p></div>)}{!foundation.modelLanes.length ? <EmptyState text="No research lanes were recorded for this legacy run." /> : null}</CardContent></Card>
      <Card><CardHeader><Badge variant="accent" className="w-fit"><Target className="mr-1 size-3" /> Portfolios & benchmarks</Badge><CardTitle>Separated decision cohorts</CardTitle><CardDescription>Actioned policies, all model leans, and sportsbook benchmarks retain separate denominators and hypothetical returns.</CardDescription></CardHeader><CardContent className="space-y-2">{foundation.portfolios.map((portfolio) => <div className="rounded-2xl border border-[var(--line)] bg-white/60 p-3" key={portfolio.id}><div className="flex items-start justify-between gap-3"><p className="text-sm font-bold">{portfolio.displayName}</p><Badge variant={portfolio.primary ? 'accent' : 'neutral'}>{pretty(portfolio.type)}</Badge></div><div className="mt-3 grid grid-cols-3 gap-2 xl:grid-cols-6"><Mini label="Decisions" value={formatInt(portfolio.decisions)} /><Mini label="Actioned" value={formatInt(portfolio.actioned)} /><Mini label="Resolved" value={formatInt(portfolio.resolved)} /><Mini label="Accuracy" value={pct(portfolio.accuracyPct)} /><Mini label="$1 ROI" value={signedPct(portfolio.flatStakeRoiPct)} /><Mini label="Coverage" value={pct(portfolio.opportunityCoveragePct)} /></div><p className="mt-2 text-[10px] text-[var(--ink-muted)]">{portfolio.pricedResolved} priced results · {signedMoney(portfolio.flatStakePnl)} flat-$1 P&amp;L · {portfolio.passed} passes</p></div>)}{foundation.benchmarks.map((benchmark) => <div className="rounded-2xl border border-dashed border-blue-200 bg-blue-50/50 p-3" key={benchmark.benchmarkKey}><div className="flex items-center justify-between gap-3"><p className="text-xs font-bold">{pretty(benchmark.benchmarkKey)}</p><span className={cn('font-mono text-xs font-bold', benchmark.flatStakeRoiPct >= 0 ? 'text-emerald-700' : 'text-rose-700')}>{signedPct(benchmark.flatStakeRoiPct)}</span></div><p className="mt-1 text-[10px] text-[var(--ink-muted)]">{benchmark.correct}/{benchmark.resolved} correct · {pct(benchmark.accuracyPct)} · {benchmark.pricedResolved} priced · {signedMoney(benchmark.flatStakePnl)} P&amp;L · {pct(benchmark.opportunityCoveragePct)} coverage</p></div>)}</CardContent></Card>
    </div>
    <Card><CardHeader><Badge className="w-fit"><NotebookPen className="mr-1 size-3" /> Research notebook</Badge><CardTitle>Run annotations</CardTitle><CardDescription>Record hypotheses, anomalies, and conclusions beside the immutable evidence instead of losing them in a separate note.</CardDescription></CardHeader><CardContent><form className="grid gap-3 lg:grid-cols-[1fr_260px_auto]" onSubmit={submit}><textarea aria-label="Research annotation" className="min-h-24 rounded-xl border border-[var(--line)] bg-white/70 p-3 text-sm outline-none focus:border-blue-400" maxLength={2000} onChange={(event) => setNote(event.target.value)} placeholder="What did this run teach us?" value={note} /><input aria-label="Annotation tags" className="h-11 rounded-xl border border-[var(--line)] bg-white/70 px-3 text-sm outline-none focus:border-blue-400" onChange={(event) => setTags(event.target.value)} placeholder="Tags, comma separated" value={tags} /><Button className="h-11" disabled={saving || !note.trim()} type="submit">{saving ? 'Saving…' : 'Save note'}</Button></form>{noteError ? <p className="mt-2 text-xs text-rose-700">{noteError}</p> : null}<div className="mt-5 space-y-2">{foundation.annotations.map((annotation) => <article className="rounded-2xl border border-[var(--line)] bg-white/60 p-4" key={annotation.id}><div className="flex flex-wrap items-center gap-2"><Badge>{annotation.author}</Badge><span className="text-[10px] text-[var(--ink-muted)]">{formatDate(annotation.createdAt)}</span>{annotation.tags.map((tag) => <span className="rounded-full bg-slate-100 px-2 py-1 text-[9px] font-semibold" key={tag}>{tag}</span>)}</div><p className="mt-3 whitespace-pre-wrap text-sm leading-6">{annotation.text}</p></article>)}{!foundation.annotations.length ? <EmptyState text="No annotations yet." /> : null}</div></CardContent></Card>
  </div>
}

function SegmentCard({ title, rows }: { title: string; rows: ResearchRunDetail['analytics']['triggers'] }) {
  return <Card><CardHeader><CardTitle>{title}</CardTitle><CardDescription>Accuracy, calibration, price return, reliability, and sample maturity for each segment.</CardDescription></CardHeader><CardContent><div className="space-y-2">{rows.map((row) => <div className="rounded-2xl border border-[var(--line)] bg-white/60 p-3" key={row.segment}><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-bold">{pretty(row.segment)}</p><p className="mt-1 text-[10px] text-[var(--ink-muted)]">{row.sampleSize} outcomes · {row.readinessPct.toFixed(0)}% ready</p></div><span className={cn('font-mono text-sm font-bold', row.flatStakeRoiPct >= 0 ? 'text-emerald-700' : 'text-rose-700')}>{signedPct(row.flatStakeRoiPct)}</span></div><div className="mt-3 grid grid-cols-3 gap-2"><Mini label="Accuracy" value={pct(row.accuracyPct)} /><Mini label="Cal gap" value={signedPct(row.calibrationGapPct)} /><Mini label="Reliability" value={pct(row.averageReliabilityPct)} /></div></div>)}{!rows.length ? <EmptyState text="No resolved segment evidence yet." /> : null}</div></CardContent></Card>
}

function IdentityPanel({ detail }: { detail: ResearchRunDetail }) {
  const { run, integrity } = detail
  const rows = [
    ['Requested selector', run.requestedModelVersion], ['Effective model', run.effectiveModelVersion], ['Model family', run.effectiveModelFamily], ['Artifact checksum', run.effectiveArtifactChecksum], ['Feature schema', run.featureSchemaChecksum], ['Calibration', run.calibrationId], ['Policy', run.policyVersion], ['Code revision', run.codeRevision], ['Frozen summary', run.frozenRunSummaryChecksum],
  ]
  return <div className="grid gap-5 xl:grid-cols-[0.65fr_1.35fr]"><Card><CardHeader><Badge variant="accent" className="w-fit"><ShieldCheck className="mr-1 size-3" /> Integrity</Badge><CardTitle>{pretty(integrity.status)}</CardTitle><CardDescription>{integrity.explanation}</CardDescription></CardHeader><CardContent className="space-y-2"><IntegrityRow label="Closed-run immutability" ok={integrity.closedRunImmutable} detail={integrity.postCloseCallCount ? `${integrity.postCloseCallCount} post-close call(s)` : undefined} /><IntegrityRow label="Model identity" ok={integrity.modelIdentityComplete} /><IntegrityRow label="Dataset boundary" ok={integrity.datasetWindowKnown} /><IntegrityRow label="Settlement coverage" ok={integrity.settlementCoverageComplete} /><p className="pt-2 text-xs text-[var(--ink-muted)]">{pct(integrity.settlementCoveragePct)} of calls have trusted outcomes.</p></CardContent></Card><Card><CardHeader><CardTitle>Frozen artifact identity</CardTitle><CardDescription>These identifiers must remain attached to every exported metric and replay.</CardDescription></CardHeader><CardContent><dl className="divide-y divide-[var(--line)] rounded-2xl border border-[var(--line)] bg-white/60 px-4">{rows.map(([label, value]) => <div className="grid gap-1 py-3 sm:grid-cols-[180px_1fr]" key={label}><dt className="text-[10px] font-bold uppercase tracking-[0.14em] text-[var(--ink-muted)]">{label}</dt><dd className="break-all font-mono text-xs">{value ?? 'Not recorded'}</dd></div>)}</dl></CardContent></Card></div>
}

function TrendChart({ points }: { points: ResearchRunDetail['analytics']['trend'] }) {
  if (points.length < 2) return <EmptyState text="Two settled calls are needed to draw the trend." />
  const width = 900; const height = 230; const pad = 26
  const profits = points.map((point) => point.cumulativeNetProfit)
  const min = Math.min(0, ...profits); const max = Math.max(0, ...profits); const range = Math.max(1, max - min)
  const path = points.map((point, index) => `${index ? 'L' : 'M'} ${pad + index * (width - pad * 2) / Math.max(1, points.length - 1)} ${height - pad - (point.cumulativeNetProfit - min) * (height - pad * 2) / range}`).join(' ')
  const zeroY = height - pad - (0 - min) * (height - pad * 2) / range
  return <div><svg className="h-[230px] w-full" preserveAspectRatio="none" role="img" aria-label="Cumulative flat one dollar profit trend" viewBox={`0 0 ${width} ${height}`}><line x1={pad} x2={width - pad} y1={zeroY} y2={zeroY} stroke="rgba(100,116,139,.35)" strokeDasharray="4 5" /><path d={path} fill="none" stroke="#2563eb" strokeLinecap="round" strokeLinejoin="round" strokeWidth="4" /></svg><div className="mt-2 flex justify-between text-[10px] text-[var(--ink-muted)]"><span>Resolution 1</span><span>{points.length} trusted resolutions</span></div></div>
}

function ExplainMetric({ label, value, detail, onClick }: { label: string; value: string; detail: string; onClick: () => void }) {
  return <button className="group rounded-[22px] border border-[var(--line)] bg-white/65 p-4 text-left transition hover:-translate-y-0.5 hover:border-blue-300 hover:bg-white" onClick={onClick}><div className="flex items-center justify-between gap-2"><span className="text-[10px] font-bold uppercase tracking-[0.16em] text-[var(--ink-muted)]">{label}</span><CircleHelp className="size-4 text-slate-400 group-hover:text-blue-600" /></div><p className="mt-3 font-mono text-2xl font-bold text-[var(--ink-strong)]">{value}</p><p className="mt-2 text-[10px] text-[var(--ink-muted)]">{detail}</p></button>
}

function SummaryMetric({ label, value, detail }: { label: string; value: string; detail: string }) {
  return <div className="rounded-[22px] border border-[var(--line)] bg-white/65 p-4"><span className="text-[10px] font-bold uppercase tracking-[0.16em] text-[var(--ink-muted)]">{label}</span><p className="mt-3 font-mono text-2xl font-bold text-[var(--ink-strong)]">{value}</p><p className="mt-2 text-[10px] text-[var(--ink-muted)]">{detail}</p></div>
}

function MetricExplainer({ metric, onClose }: { metric: { title: string; detail: string }; onClose: () => void }) {
  return <div className="flex items-start gap-3 rounded-2xl border border-blue-200 bg-blue-50 p-4 text-blue-950"><CircleHelp className="mt-0.5 size-4 shrink-0" /><div><p className="text-sm font-bold">{metric.title}</p><p className="mt-1 text-xs leading-5">{metric.detail}</p></div><button className="ml-auto" onClick={onClose} aria-label="Close metric explanation"><X className="size-4" /></button></div>
}

function IntegrityRow({ label, ok, detail }: { label: string; ok: boolean; detail?: string }) { return <div className="flex items-center justify-between rounded-xl border border-[var(--line)] bg-white/60 px-3 py-2 text-xs"><span>{label}</span><span className={cn('font-bold', ok ? 'text-emerald-700' : 'text-amber-700')}>{detail ?? (ok ? 'Complete' : 'Needs data')}</span></div> }
function HeroMetric({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' }) { return <div className="rounded-2xl border border-white/10 bg-white/[0.06] p-3"><p className="text-[9px] font-bold uppercase tracking-[0.15em] text-slate-400">{label}</p><p className={cn('mt-2 font-mono text-lg font-bold', tone === 'good' ? 'text-emerald-300' : tone === 'bad' ? 'text-rose-300' : 'text-white')}>{value}</p></div> }
function Mini({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' }) { return <div><p className="text-[9px] font-bold uppercase tracking-[0.12em] text-[var(--ink-muted)]">{label}</p><p className={cn('mt-0.5 truncate font-mono text-xs font-bold', tone === 'good' ? 'text-emerald-700' : tone === 'bad' ? 'text-rose-700' : 'text-[var(--ink-strong)]')}>{value}</p></div> }
function StatusPill({ status }: { status: string }) { const good = ['ACTIVE', 'SYSTEM_CONFIRMED', 'REPRODUCIBLE'].includes(status); const alert = ['RESULT_CONFLICT', 'IDENTITY_INCOMPLETE'].includes(status); return <span className={cn('inline-flex shrink-0 rounded-full border px-2 py-1 text-[9px] font-bold uppercase tracking-[0.12em]', good ? 'border-emerald-300/50 bg-emerald-50 text-emerald-800' : alert ? 'border-rose-300 bg-rose-50 text-rose-800' : 'border-slate-300 bg-slate-50 text-slate-700')}>{pretty(status)}</span> }
function InlineAlert({ children }: { children: ReactNode }) { return <div className="mb-4 flex items-center gap-2 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-950">{children}</div> }
function EmptyState({ text }: { text: string }) { return <div className="py-8 text-center text-sm text-[var(--ink-muted)]">{text}</div> }
function interval(low: number | null, high: number | null) { return low == null || high == null ? 'Needs sample' : `${pct(low)}–${pct(high)}` }
function pct(value: number | null | undefined) { return value == null || !Number.isFinite(value) ? '—' : `${value.toFixed(1)}%` }
function signedPct(value: number | null | undefined) { return value == null || !Number.isFinite(value) ? '—' : `${value >= 0 ? '+' : ''}${value.toFixed(1)}%` }
function signedMoney(value: number | null | undefined) { const amount = value ?? 0; return `${amount >= 0 ? '+' : '−'}$${Math.abs(amount).toFixed(2)}` }
function american(value: number | null | undefined) { return value == null ? '—' : `${value > 0 ? '+' : ''}${value}` }
function formatInt(value: number | null | undefined) { return Math.round(value ?? 0).toLocaleString() }
function formatDate(value: string | null | undefined) { if (!value) return 'Unknown time'; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }) }
function pretty(value: string) { return value.replaceAll('_', ' ').replace(/\b\w/g, (character) => character.toUpperCase()) }
function numberParam(value: string | null) { const parsed = value ? Number(value) : NaN; return Number.isFinite(parsed) && parsed > 0 ? parsed : null }
function parseIds(value: string | null) { return [...new Set((value ?? '').split(',').map((id) => Number(id)).filter((id) => Number.isFinite(id) && id > 0))] }
function message(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }
function readSavedViews(): SavedRunView[] { try { const parsed = JSON.parse(window.localStorage.getItem('ttl-runs-saved-views') ?? '[]'); return Array.isArray(parsed) ? parsed : [] } catch { return [] } }
function downloadCsv(name: string, rows: Array<Array<string | number>>) { const csv = rows.map((row) => row.map((cell) => `"${String(cell).replaceAll('"', '""')}"`).join(',')).join('\n'); const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })); const anchor = document.createElement('a'); anchor.href = url; anchor.download = name; anchor.click(); URL.revokeObjectURL(url) }
