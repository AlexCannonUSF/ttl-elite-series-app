import { useCallback, useEffect, useMemo, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { AlertTriangle, Check, Clock3, FlaskConical, GitBranch, Play, RefreshCcw, ShieldCheck } from 'lucide-react'

import { V3Shell } from '@/components/layout/V3Shell'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import type { ModelRun } from '@/features/ml-quality/types'
import { branchReplay, createReplay, fetchReplay, fetchReplays, startReplay } from '@/features/replay/api'
import type { Replay } from '@/features/replay/types'
import { fetchResearchRuns } from '@/features/research/api'
import { cn } from '@/lib/utils'

export function ReplayLabRoute() {
  const [runs, setRuns] = useState<ModelRun[]>([])
  const [replays, setReplays] = useState<Replay[]>([])
  const [selected, setSelected] = useState<Replay | null>(null)
  const [runIds, setRunIds] = useState<number[]>([])
  const [label, setLabel] = useState('Frozen-call historical replay')
  const [mode, setMode] = useState('HISTORICAL_AS_KNOWN')
  const [bankroll, setBankroll] = useState(1000)
  const [loading, setLoading] = useState(true)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [history, replayHistory] = await Promise.all([fetchResearchRuns(100), fetchReplays()])
      const closed = history.runs.filter((run) => run.status === 'CLOSED' && run.modelCalls > 0)
      setRuns(closed)
      setReplays(replayHistory)
      setRunIds((current) => current.length ? current : closed.slice(0, 1).map((run) => run.sessionId))
      setSelected((current) => current ?? replayHistory[0] ?? null)
      setError(null)
    } catch (nextError) {
      setError(message(nextError, 'Unable to load Replay Lab.'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!runIds.length) return setError('Choose at least one closed source run.')
    setWorking(true)
    try {
      const draft = await createReplay({
        label,
        sourceRunIds: runIds,
        replayMode: mode,
        captureRule: 'FROZEN_ORIGINAL_CALL',
        modelLaneKeys: ['CHAMPION'],
        portfolioKeys: ['ALL_CALLS', 'CHAMPION_STRICT'],
        executionBook: 'HR_MKT',
        initialBankroll: bankroll,
        maxQuoteAgeSeconds: 45,
        deterministicSeed: 31415926,
      })
      const completed = await startReplay(draft.id)
      await load()
      setSelected(completed)
    } catch (nextError) {
      setError(message(nextError, 'Replay could not be completed.'))
    } finally {
      setWorking(false)
    }
  }

  async function openReplay(id: number) {
    try {
      setSelected(await fetchReplay(id))
    } catch (nextError) {
      setError(message(nextError, 'Replay detail could not be loaded.'))
    }
  }

  async function branch(id: number) {
    setWorking(true)
    try {
      const draft = await branchReplay(id)
      await load()
      setSelected(draft)
    } catch (nextError) {
      setError(message(nextError, 'Replay branch could not be created.'))
    } finally {
      setWorking(false)
    }
  }

  return (
    <V3Shell
      title="Replay Lab"
      description="Reproduce historical model decisions from frozen point-in-time receipts, inspect every event in order, and branch a definition without mutating its parent."
      badges={<><Badge variant="accent">Time machine</Badge><Badge>{replays.length} receipts</Badge></>}
      actions={<Button variant="secondary" disabled={loading} onClick={() => void load()}><RefreshCcw className={cn('size-4', loading && 'animate-spin')} />Refresh</Button>}
    >
      {error ? <div className="mb-5 flex items-center gap-2 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert"><AlertTriangle className="size-4" />{error}<button className="ml-auto font-semibold" onClick={() => setError(null)}>Dismiss</button></div> : null}

      <section className="grid gap-5 2xl:grid-cols-[420px_minmax(0,1fr)]">
        <div className="space-y-5">
          <Card>
            <CardHeader><Badge variant="accent" className="w-fit"><FlaskConical className="size-3.5" /> Replay builder</Badge><CardTitle>Freeze the question first</CardTitle><CardDescription>Only closed runs are selectable. Playback uses their original call time, probability, price, and identity.</CardDescription></CardHeader>
            <CardContent>
              <form className="space-y-4" onSubmit={submit}>
                <Field label="Replay name"><input className={inputClass} value={label} onChange={(event) => setLabel(event.target.value)} required /></Field>
                <Field label="Integrity mode"><select className={inputClass} value={mode} onChange={(event) => setMode(event.target.value)}><option value="HISTORICAL_AS_KNOWN">Historical as known</option><option disabled value="MODERN_MODEL_RETROSPECTIVE">Modern-model retrospective · requires training cutoff</option></select></Field>
                <Field label="Initial virtual bankroll"><input className={inputClass} min={1} type="number" value={bankroll} onChange={(event) => setBankroll(Number(event.target.value))} /></Field>
                <fieldset><legend className="mb-2 text-[10px] font-bold uppercase tracking-[0.18em] text-[var(--ink-muted)]">Closed source runs · choose up to 8</legend><div className="max-h-64 space-y-2 overflow-y-auto pr-1">{runs.map((run) => <RunChoice key={run.sessionId} checked={runIds.includes(run.sessionId)} disabled={!runIds.includes(run.sessionId) && runIds.length >= 8} run={run} onToggle={() => setRunIds((current) => current.includes(run.sessionId) ? current.filter((id) => id !== run.sessionId) : [...current, run.sessionId])} />)}</div></fieldset>
                <div className="rounded-2xl border border-blue-200 bg-blue-50 p-3 text-[11px] leading-5 text-blue-950"><strong>Leakage contract:</strong> this engine never rebuilds old features from current ratings or identities. Unresolved outcomes remain unresolved.</div>
                <Button className="w-full" disabled={working || !runIds.length} type="submit"><Play className="size-4" />{working ? 'Building immutable receipt…' : 'Create and run replay'}</Button>
              </form>
            </CardContent>
          </Card>

          <Card><CardHeader><CardTitle>Replay history</CardTitle><CardDescription>Completed receipts cannot be edited or overwritten.</CardDescription></CardHeader><CardContent className="space-y-2">{replays.map((replay) => <button className={cn('w-full rounded-2xl border p-3 text-left', selected?.id === replay.id ? 'border-blue-300 bg-blue-50' : 'border-[var(--line)] bg-white/70')} key={replay.id} onClick={() => void openReplay(replay.id)}><div className="flex items-start justify-between gap-2"><p className="font-semibold">{replay.label}</p><Badge variant={replay.status === 'COMPLETED' ? 'accent' : 'neutral'}>{pretty(replay.status)}</Badge></div><p className="mt-1 text-[11px] text-[var(--ink-muted)]">#{replay.id} · {replay.eventCount} events · runs {replay.sourceRunIds.join(', ')}</p></button>)}{!replays.length && !loading ? <p className="text-sm text-[var(--ink-muted)]">No replay receipts yet.</p> : null}</CardContent></Card>
        </div>

        {selected ? <ReplayDetail replay={selected} working={working} onBranch={() => void branch(selected.id)} /> : <Card><CardContent className="grid min-h-80 place-items-center text-sm text-[var(--ink-muted)]">Build or open a replay to inspect it.</CardContent></Card>}
      </section>
    </V3Shell>
  )
}

function ReplayDetail({ replay, working, onBranch }: { replay: Replay; working: boolean; onBranch: () => void }) {
  const resolvedPct = replay.eventCount ? replay.resolvedCount * 100 / replay.eventCount : 0
  const equity = useMemo(() => replay.events.reduce<number[]>((points, event) => [...points, points.at(-1)! + (event.flatStakeProfit ?? 0)], [replay.initialBankroll]), [replay])
  return <div className="space-y-5">
    <Card><CardHeader><div className="flex flex-wrap items-center justify-between gap-3"><div className="flex gap-2"><Badge variant="accent">{pretty(replay.status)}</Badge><Badge>{pretty(replay.leakageAuditStatus)}</Badge></div><Button variant="secondary" disabled={working} onClick={onBranch}><GitBranch className="size-4" />Branch definition</Button></div><CardTitle>{replay.label}</CardTitle><CardDescription>Receipt {replay.definitionChecksum.slice(0, 16)} · seed {replay.deterministicSeed} · {pretty(replay.replayMode)}</CardDescription></CardHeader><CardContent className="space-y-4"><div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5"><Stat label="Events" value={replay.eventCount} /><Stat label="Resolved" value={`${replay.resolvedCount} · ${resolvedPct.toFixed(1)}%`} /><Stat label="Accuracy" value={`${replay.accuracyPct.toFixed(1)}%`} /><Stat label="$1 P&L" value={money(replay.flatStakePnl)} tone={replay.flatStakePnl >= 0 ? 'good' : 'bad'} /><Stat label="$1 ROI" value={`${signed(replay.flatStakeRoiPct)}%`} detail={`${replay.pricedResolvedCount} priced results`} tone={replay.flatStakeRoiPct >= 0 ? 'good' : 'bad'} /></div><EquityStrip points={equity} /><div className="grid gap-2">{replay.integrityNotes.map((note) => <p className="flex gap-2 text-xs leading-5 text-[var(--ink-muted)]" key={note}><ShieldCheck className="mt-0.5 size-4 shrink-0 text-emerald-600" />{note}</p>)}</div></CardContent></Card>
    <Card><CardHeader><Badge className="w-fit"><Clock3 className="size-3.5" /> Deterministic event log</Badge><CardTitle>Exactly what the model knew, in order</CardTitle><CardDescription>Each row links back to one frozen source call; no polling row is treated as another sample.</CardDescription></CardHeader><CardContent><div className="max-h-[720px] overflow-auto rounded-2xl border border-[var(--line)]"><table className="w-full min-w-[940px] text-left text-xs"><thead className="sticky top-0 bg-slate-100 text-[10px] uppercase tracking-[0.14em] text-[var(--ink-muted)]"><tr><th className="px-3 py-3"># / Time</th><th className="px-3 py-3">Match</th><th className="px-3 py-3">Frozen lean</th><th className="px-3 py-3">Model</th><th className="px-3 py-3">Hard Rock</th><th className="px-3 py-3">Decision</th><th className="px-3 py-3">Outcome</th><th className="px-3 py-3">$1 P&L</th></tr></thead><tbody>{replay.events.map((event) => <tr className="border-t border-[var(--line)] bg-white/65" key={event.sourceCallId}><td className="px-3 py-3 font-mono text-[10px]">{event.sequenceNumber}<br />{formatDate(event.eventTime)}</td><td className="px-3 py-3"><p className="font-semibold">{event.eventName ?? 'Unknown match'}</p><p className="text-[10px] text-[var(--ink-muted)]">run {event.sourceRunId} · call {event.sourceCallId}</p></td><td className="px-3 py-3 font-semibold">{event.predictedWinnerName ?? 'No lean'}</td><td className="px-3 py-3 font-mono">{event.modelProbability == null ? '—' : `${(event.modelProbability * 100).toFixed(1)}%`}</td><td className="px-3 py-3 font-mono">{american(event.hardRockAmericanOdds)}</td><td className="px-3 py-3">{pretty(event.decisionStatus ?? 'unknown')}</td><td className="px-3 py-3"><span className={cn('font-semibold', event.effectiveOutcome === 'CORRECT' ? 'text-emerald-700' : event.effectiveOutcome === 'INCORRECT' ? 'text-rose-700' : 'text-slate-500')}>{pretty(event.effectiveOutcome ?? 'awaiting')}</span><p className="text-[10px] text-[var(--ink-muted)]">{event.outcomeSource ?? event.pipelineStage ?? '—'}</p></td><td className={cn('px-3 py-3 font-mono font-semibold', (event.flatStakeProfit ?? 0) > 0 ? 'text-emerald-700' : (event.flatStakeProfit ?? 0) < 0 ? 'text-rose-700' : '')}>{event.flatStakeProfit == null ? '—' : money(event.flatStakeProfit)}</td></tr>)}</tbody></table>{!replay.events.length ? <p className="p-6 text-sm text-[var(--ink-muted)]">This draft has not been run yet.</p> : null}</div></CardContent></Card>
  </div>
}

function RunChoice({ run, checked, disabled, onToggle }: { run: ModelRun; checked: boolean; disabled: boolean; onToggle: () => void }) { return <button className={cn('flex w-full items-center gap-3 rounded-xl border p-3 text-left', checked ? 'border-blue-300 bg-blue-50' : 'border-[var(--line)] bg-white/70', disabled && 'opacity-50')} disabled={disabled} onClick={onToggle} type="button"><span className={cn('grid size-5 place-items-center rounded-md border', checked ? 'border-blue-600 bg-blue-600 text-white' : 'border-slate-300')}>{checked ? <Check className="size-3" /> : null}</span><span className="min-w-0"><span className="block truncate text-xs font-semibold">#{run.sessionId} {run.label}</span><span className="block truncate text-[10px] text-[var(--ink-muted)]">{run.modelCalls} calls · {run.effectiveModelVersion ?? 'legacy identity'}</span></span></button> }
function Field({ label, children }: { label: string; children: ReactNode }) { return <label className="block"><span className="mb-1.5 block text-[10px] font-bold uppercase tracking-[0.18em] text-[var(--ink-muted)]">{label}</span>{children}</label> }
function Stat({ label, value, detail, tone }: { label: string; value: string | number; detail?: string; tone?: 'good' | 'bad' }) { return <div className="rounded-2xl border border-[var(--line)] bg-white/70 p-3"><p className="text-[9px] font-bold uppercase tracking-[0.16em] text-[var(--ink-muted)]">{label}</p><p className={cn('mt-1 font-mono text-lg font-bold', tone === 'good' ? 'text-emerald-700' : tone === 'bad' ? 'text-rose-700' : '')}>{value}</p>{detail ? <p className="mt-1 text-[10px] text-[var(--ink-muted)]">{detail}</p> : null}</div> }
function EquityStrip({ points }: { points: number[] }) { const min = Math.min(...points); const max = Math.max(...points); const range = Math.max(1, max - min); const path = points.map((point, index) => `${index ? 'L' : 'M'} ${(index / Math.max(1, points.length - 1) * 600).toFixed(1)} ${(110 - ((point - min) / range) * 90).toFixed(1)}`).join(' '); return <div><div className="mb-2 flex items-center justify-between text-[10px] font-bold uppercase tracking-[0.16em] text-[var(--ink-muted)]"><span>Flat-$1 equity path</span><span>{money(points.at(-1) ?? 0)}</span></div><svg aria-label="Flat one dollar replay equity curve" className="h-28 w-full rounded-2xl border border-[var(--line)] bg-white/60" preserveAspectRatio="none" viewBox="0 0 600 120"><path d={path} fill="none" stroke="rgb(37,99,235)" strokeWidth="3" vectorEffect="non-scaling-stroke" /></svg></div> }

const inputClass = 'h-11 w-full rounded-xl border border-[var(--line)] bg-white/75 px-3 text-sm outline-none focus:border-blue-400'
function pretty(value: string) { return value.toLowerCase().split('_').map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(' ') }
function message(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }
function money(value: number) { return new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD', signDisplay: 'always' }).format(value) }
function signed(value: number) { return `${value >= 0 ? '+' : ''}${value.toFixed(1)}` }
function american(value: number | null) { return value == null ? '—' : value > 0 ? `+${value}` : String(value) }
function formatDate(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(date) }
